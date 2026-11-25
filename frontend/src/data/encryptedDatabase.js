const DATABASE_VERSION = 1
const KDF_ITERATIONS = 600000
const CHECK_VALUE = 'bom-wargames-local-database-v1'
const COLLECTIONS = ['campaigns', 'places', 'routes', 'events', 'armies', 'terrainRegions', 'rooms']
const encoder = new TextEncoder()
const decoder = new TextDecoder()

function bytesToBase64(bytes) {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function base64ToBytes(value) {
  return Uint8Array.from(atob(value), character => character.charCodeAt(0))
}

function randomBytes(length) {
  return crypto.getRandomValues(new Uint8Array(length))
}

function openRequest(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('IndexedDB request failed'))
  })
}

function completeTransaction(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error || new Error('IndexedDB transaction aborted'))
    transaction.onerror = () => reject(transaction.error || new Error('IndexedDB transaction failed'))
  })
}

export function validateLegacyExport(value) {
  if (!value || value.format !== 'bom-wargames-legacy-export' || value.version !== 1) {
    throw new Error('This is not a supported legacy data export')
  }
  for (const collection of COLLECTIONS.filter(name => name !== 'rooms')) {
    if (!Array.isArray(value[collection])) {
      throw new Error(`The legacy export is missing its ${collection} list`)
    }
  }
  if (value.rooms !== undefined && !Array.isArray(value.rooms)) {
    throw new Error('The legacy export has an invalid rooms list')
  }
  return Object.fromEntries(COLLECTIONS.map(collection => [
    collection,
    value[collection] ?? [],
  ]))
}

export class EncryptedDatabase {
  constructor(name = 'bom-wargames-local') {
    this.name = name
    this.databasePromise = null
    this.key = null
  }

  open() {
    if (!globalThis.indexedDB) throw new Error('This browser does not support IndexedDB')
    if (!this.databasePromise) {
      this.databasePromise = new Promise((resolve, reject) => {
        const request = indexedDB.open(this.name, DATABASE_VERSION)
        request.onupgradeneeded = () => {
          const database = request.result
          if (!database.objectStoreNames.contains('metadata')) {
            database.createObjectStore('metadata', { keyPath: 'key' })
          }
          if (!database.objectStoreNames.contains('records')) {
            database.createObjectStore('records', { keyPath: 'key' })
          }
        }
        request.onsuccess = () => {
          const database = request.result
          database.onversionchange = () => {
            database.close()
            this.databasePromise = null
          }
          resolve(database)
        }
        request.onerror = () => reject(request.error || new Error('Could not open the local database'))
        request.onblocked = () => reject(new Error('The local database is blocked by another open tab'))
      })
    }
    return this.databasePromise
  }

  async readRecord(storeName, key) {
    const database = await this.open()
    const transaction = database.transaction(storeName, 'readonly')
    const request = transaction.objectStore(storeName).get(key)
    return openRequest(request)
  }

  async writeRecords(storeName, records, clear = false) {
    const database = await this.open()
    const transaction = database.transaction(storeName, 'readwrite')
    const store = transaction.objectStore(storeName)
    if (clear) store.clear()
    for (const record of records) store.put(record)
    await completeTransaction(transaction)
  }

  async hasDatabase() {
    return Boolean(await this.readRecord('metadata', 'key-check'))
  }

  async deriveKey(passphrase, salt, iterations = KDF_ITERATIONS) {
    if (!globalThis.crypto?.subtle) {
      throw new Error('Web Crypto is unavailable; open the app on HTTPS or localhost')
    }
    const material = await crypto.subtle.importKey(
      'raw',
      encoder.encode(passphrase.normalize('NFKC')),
      'PBKDF2',
      false,
      ['deriveKey'],
    )
    return crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt, iterations, hash: 'SHA-256' },
      material,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt'],
    )
  }

  async create(passphrase, initialCollections = null) {
    if (passphrase.length < 12) throw new Error('Use a passphrase with at least 12 characters')
    if (initialCollections !== null
      && (!initialCollections || typeof initialCollections !== 'object' || Array.isArray(initialCollections))) {
      throw new Error('Initial local data must be a collection object')
    }
    if (await this.hasDatabase()) throw new Error('A local encrypted database already exists')

    const salt = randomBytes(16)
    const iv = randomBytes(12)
    const key = await this.deriveKey(passphrase, salt)
    const verification = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: encoder.encode('bom-wargames-key-check-v1') },
      key,
      encoder.encode(CHECK_VALUE),
    )
    const records = initialCollections
      ? await Promise.all(COLLECTIONS.map(name =>
        this.encryptCollection(name, initialCollections[name] ?? [], key)))
      : null
    const database = await this.open()
    const transaction = database.transaction(['metadata', 'records'], 'readwrite')
    transaction.objectStore('metadata').put({
      key: 'key-check',
      version: DATABASE_VERSION,
      salt: bytesToBase64(salt),
      iterations: KDF_ITERATIONS,
      iv: bytesToBase64(iv),
      verification: bytesToBase64(new Uint8Array(verification)),
    })
    const recordStore = transaction.objectStore('records')
    recordStore.clear()
    if (records) for (const record of records) recordStore.put(record)
    await completeTransaction(transaction)
    this.key = key
  }

  async unlock(passphrase) {
    const metadata = await this.readRecord('metadata', 'key-check')
    if (!metadata
      || metadata.version !== DATABASE_VERSION
      || !Number.isInteger(metadata.iterations)
      || metadata.iterations < 100000
      || metadata.iterations > 1000000) {
      throw new Error('No supported encrypted database was found')
    }
    const key = await this.deriveKey(
      passphrase,
      base64ToBytes(metadata.salt),
      metadata.iterations,
    )
    try {
      const plaintext = await crypto.subtle.decrypt(
        {
          name: 'AES-GCM',
          iv: base64ToBytes(metadata.iv),
          additionalData: encoder.encode('bom-wargames-key-check-v1'),
        },
        key,
        base64ToBytes(metadata.verification),
      )
      if (decoder.decode(plaintext) !== CHECK_VALUE) throw new Error('Verification did not match')
    } catch {
      throw new Error('Incorrect passphrase or damaged encrypted database')
    }
    this.key = key
  }

  lock() {
    this.key = null
  }

  async readCollection(name) {
    this.assertUnlocked()
    this.assertCollection(name)
    const record = await this.readRecord('records', name)
    if (!record) return []
    try {
      const plaintext = await crypto.subtle.decrypt(
        {
          name: 'AES-GCM',
          iv: base64ToBytes(record.iv),
          additionalData: encoder.encode(`${name}:v1`),
        },
        this.key,
        base64ToBytes(record.ciphertext),
      )
      const value = JSON.parse(decoder.decode(plaintext))
      if (!Array.isArray(value)) throw new Error('Stored collection is not a list')
      return value
    } catch {
      throw new Error(`Could not decrypt the local ${name} data`)
    }
  }

  async writeCollection(name, value) {
    const record = await this.encryptCollection(name, value)
    await this.writeRecords('records', [record])
  }

  async encryptCollection(name, value, key = this.key) {
    this.assertCollection(name)
    if (!key) throw new Error('Unlock the encrypted local database first')
    if (!Array.isArray(value)) throw new Error(`${name} must be a list`)
    const iv = randomBytes(12)
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: encoder.encode(`${name}:v1`) },
      key,
      encoder.encode(JSON.stringify(value)),
    )
    return {
      key: name,
      version: DATABASE_VERSION,
      iv: bytesToBase64(iv),
      ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
    }
  }

  async initializeCollections(collections) {
    const records = await Promise.all(COLLECTIONS.map(name =>
      this.encryptCollection(name, collections[name] ?? [])))
    await this.writeRecords('records', records, true)
  }

  async importLegacy(value) {
    await this.initializeCollections(validateLegacyExport(value))
  }

  async exportEncryptedBackup() {
    this.assertUnlocked()
    const database = await this.open()
    const transaction = database.transaction(['metadata', 'records'], 'readonly')
    const metadataRequest = transaction.objectStore('metadata').getAll()
    const recordsRequest = transaction.objectStore('records').getAll()
    const [metadata, records] = await Promise.all([
      openRequest(metadataRequest),
      openRequest(recordsRequest),
    ])
    return JSON.stringify({
      format: 'bom-wargames-encrypted-backup',
      version: DATABASE_VERSION,
      metadata,
      records,
    })
  }

  async restoreEncryptedBackup(value) {
    if (await this.hasDatabase()) throw new Error('Restore is only available before creating a local database')
    if (!value
      || value.format !== 'bom-wargames-encrypted-backup'
      || value.version !== DATABASE_VERSION
      || !Array.isArray(value.metadata)
      || !Array.isArray(value.records)
      || value.metadata.length !== 1
      || value.metadata[0]?.key !== 'key-check'
      || value.metadata[0]?.version !== DATABASE_VERSION
      || !Number.isInteger(value.metadata[0]?.iterations)
      || value.metadata[0].iterations < 100000
      || value.metadata[0].iterations > 1000000
      || typeof value.metadata[0]?.salt !== 'string'
      || typeof value.metadata[0]?.iv !== 'string'
      || typeof value.metadata[0]?.verification !== 'string'
      || value.records.length !== COLLECTIONS.length
      || new Set(value.records.map(record => record?.key)).size !== COLLECTIONS.length
      || value.records.some(record => !record
        || typeof record !== 'object'
        || !COLLECTIONS.includes(record.key)
        || record.version !== DATABASE_VERSION
        || typeof record.iv !== 'string'
        || typeof record.ciphertext !== 'string')
      || COLLECTIONS.some(name => !value.records.some(record => record?.key === name))) {
      throw new Error('This is not a supported encrypted backup')
    }
    const database = await this.open()
    const transaction = database.transaction(['metadata', 'records'], 'readwrite')
    const metadataStore = transaction.objectStore('metadata')
    const recordsStore = transaction.objectStore('records')
    metadataStore.clear()
    recordsStore.clear()
    for (const record of value.metadata) metadataStore.put(record)
    for (const record of value.records) recordsStore.put(record)
    await completeTransaction(transaction)
  }

  assertUnlocked() {
    if (!this.key) throw new Error('Unlock the encrypted local database first')
  }

  assertCollection(name) {
    if (!COLLECTIONS.includes(name)) throw new Error(`Unsupported local data collection: ${name}`)
  }

  async close() {
    this.lock()
    if (this.databasePromise) {
      const database = await this.databasePromise
      database.close()
      this.databasePromise = null
    }
  }
}

export const localDatabase = new EncryptedDatabase()
