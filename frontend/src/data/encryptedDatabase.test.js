import test from 'node:test'
import assert from 'node:assert/strict'
import 'fake-indexeddb/auto'
import { webcrypto } from 'node:crypto'
import { EncryptedDatabase, validateLegacyExport } from './encryptedDatabase.js'

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto })
}

if (!globalThis.btoa) {
  globalThis.btoa = value => Buffer.from(value, 'binary').toString('base64')
  globalThis.atob = value => Buffer.from(value, 'base64').toString('binary')
}

function createTestDatabase() {
  return new EncryptedDatabase(`test-${crypto.randomUUID()}`)
}

async function removeDatabase(database) {
  const name = database.name
  await database.close()
  await new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name)
    request.onsuccess = () => resolve()
    request.onerror = () => reject(request.error)
  })
}

test('local collections are encrypted and require the passphrase to unlock', async () => {
  const database = createTestDatabase()
  try {
    await database.create('a sufficiently long passphrase')
    await database.writeCollection('armies', [{ name: 'Private campaign army', id: 'army-1' }])

    const backup = await database.exportEncryptedBackup()
    assert.equal(backup.includes('Private campaign army'), false)

    await database.lock()
    await assert.rejects(database.unlock('an incorrect long passphrase'), /Incorrect passphrase/)
    await database.unlock('a sufficiently long passphrase')
    assert.deepEqual(await database.readCollection('armies'), [
      { name: 'Private campaign army', id: 'army-1' },
    ])
  } finally {
    await removeDatabase(database)
  }
})

test('initial collections are encrypted during database setup', async () => {
  const database = createTestDatabase()
  const campaigns = [{ id: 1, name: 'Private campaign' }]
  try {
    await database.create('a sufficiently long passphrase', {
      campaigns,
      places: [],
      routes: [],
      events: [],
      armies: [],
      terrainRegions: [],
      rooms: [],
    })
    assert.equal((await database.exportEncryptedBackup()).includes('Private campaign'), false)

    await database.lock()
    await database.unlock('a sufficiently long passphrase')
    assert.deepEqual(await database.readCollection('campaigns'), campaigns)
  } finally {
    await removeDatabase(database)
  }
})

test('encrypted backups restore into a fresh browser database', async () => {
  const original = createTestDatabase()
  const restored = createTestDatabase()
  try {
    await original.create('a sufficiently long passphrase')
    await original.initializeCollections({
      campaigns: [],
      places: [],
      routes: [],
      events: [],
      armies: [],
      terrainRegions: [],
      rooms: [],
    })
    await original.writeCollection('terrainRegions', [{ id: 'region-1', name: 'Hidden Pass' }])
    const backup = JSON.parse(await original.exportEncryptedBackup())

    const incompleteBackup = { ...backup, records: backup.records.slice(1) }
    await assert.rejects(restored.restoreEncryptedBackup(incompleteBackup), /not a supported encrypted backup/)
    await restored.restoreEncryptedBackup(backup)
    await restored.unlock('a sufficiently long passphrase')
    assert.deepEqual(await restored.readCollection('terrainRegions'), [
      { id: 'region-1', name: 'Hidden Pass' },
    ])
  } finally {
    await removeDatabase(original)
    await removeDatabase(restored)
  }
})

test('legacy exports require each campaign data collection', () => {
  assert.throws(() => validateLegacyExport({ format: 'bom-wargames-legacy-export', version: 1 }), /campaigns/)
  const normalized = validateLegacyExport({
    format: 'bom-wargames-legacy-export',
    version: 1,
    campaigns: [],
    places: [],
    routes: [],
    events: [],
    armies: [],
    terrainRegions: [],
  })
  assert.deepEqual(normalized.rooms, [])
})
