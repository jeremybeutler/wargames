import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import {
  createRoomKey,
  decryptRoomMessage,
  encryptRoomMessage,
  GameNetwork,
} from './gameNetwork.js'

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto })
}

if (!globalThis.btoa) {
  globalThis.btoa = value => Buffer.from(value, 'binary').toString('base64')
  globalThis.atob = value => Buffer.from(value, 'base64').toString('binary')
}

class FakeEmitter {
  constructor() {
    this.listeners = new Map()
  }

  on(eventName, listener) {
    const listeners = this.listeners.get(eventName) || new Set()
    listeners.add(listener)
    this.listeners.set(eventName, listeners)
    return this
  }

  emit(eventName, payload) {
    for (const listener of this.listeners.get(eventName) || []) listener(payload)
  }
}

class FakeDataChannel {
  constructor() {
    this.readyState = 'connecting'
    this.bufferedAmount = 0
    this.bufferedAmountLowThreshold = 0
    this.listeners = new Map()
    this.other = null
  }

  addEventListener(eventName, listener) {
    const listeners = this.listeners.get(eventName) || new Set()
    listeners.add(listener)
    this.listeners.set(eventName, listeners)
  }

  removeEventListener(eventName, listener) {
    this.listeners.get(eventName)?.delete(listener)
  }

  dispatch(eventName, event = {}) {
    for (const listener of this.listeners.get(eventName) || []) listener(event)
  }

  open() {
    this.readyState = 'open'
    this.dispatch('open')
  }

  send(data) {
    if (this.readyState !== 'open') throw new Error('Data channel is not open')
    queueMicrotask(() => this.other.dispatch('message', { data }))
  }

  close() {
    this.readyState = 'closed'
    this.dispatch('close')
  }
}

class FakeRemote extends FakeEmitter {
  constructor(id, polite) {
    super()
    this.id = id
    this.polite = polite
    this.other = null
    this.pc = {
      createDataChannel: () => {
        const local = new FakeDataChannel()
        const remote = new FakeDataChannel()
        local.other = remote
        remote.other = local
        queueMicrotask(() => this.other.emit('data-channel', { channel: remote }))
        setTimeout(() => {
          local.open()
          remote.open()
        }, 0)
        return local
      },
    }
  }
}

class FakeMeteredPeer extends FakeEmitter {
  constructor(peerId, rooms) {
    super()
    this.peerId = peerId
    this.rooms = rooms
  }

  async join(channel) {
    const peers = this.rooms.get(channel) || []
    for (const peer of peers) {
      const currentRemote = new FakeRemote(this.peerId, false)
      const joiningRemote = new FakeRemote(peer.peerId, true)
      currentRemote.other = joiningRemote
      joiningRemote.other = currentRemote
      peer.emit('peer-joined', { peer: currentRemote })
      this.emit('peer-joined', { peer: joiningRemote })
    }
    this.rooms.set(channel, [...peers, this])
    this.emit('state-change', { from: 'joining', to: 'joined' })
  }

  async close() {}
}

function waitFor(predicate, message) {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const interval = setInterval(() => {
      if (predicate()) {
        clearInterval(interval)
        resolve()
      } else if (Date.now() - started > 3000) {
        clearInterval(interval)
        reject(new Error(message))
      }
    }, 5)
  })
}

test('room messages use authenticated Web Crypto encryption', async () => {
  const { key } = await createRoomKey()
  const message = { type: 'state', armyName: 'Private force' }
  const encrypted = await encryptRoomMessage(key, 'room-1', message)

  assert.equal(new TextDecoder().decode(encrypted).includes('Private force'), false)
  assert.deepEqual(await decryptRoomMessage(key, 'room-1', encrypted), message)
  await assert.rejects(decryptRoomMessage(key, 'wrong-room', encrypted))
})

test('host approval and game state travel over encrypted peer data channels', async () => {
  const rooms = new Map()
  let nextPeerId = 0
  let joinRequest
  let proposal
  let proposalResult
  const receivedStates = []
  const host = new GameNetwork({
    onJoinRequest: request => { joinRequest = request },
    onProposal: incoming => { proposal = incoming },
  }, () => new FakeMeteredPeer(`peer-${++nextPeerId}`, rooms))
  const guest = new GameNetwork({
    onState: state => receivedStates.push(state),
    onProposalResult: result => { proposalResult = result },
  }, () => new FakeMeteredPeer(`peer-${++nextPeerId}`, rooms))

  try {
    const invitation = await host.host(
      'pk_live_test-key',
      'Host',
      'book-of-mormon',
      'tiles',
    )
    await guest.join(invitation, 'pk_live_test-key', 'Guest')
    await waitFor(() => joinRequest, 'Host did not receive the join request')
    assert.equal(joinRequest.playerName, 'Guest')

    const initialState = {
      campaignSlug: 'book-of-mormon',
      mapMode: 'tiles',
      armies: [],
      ruleset: { name: 'Test game' },
    }
    await host.approvePeer(joinRequest.peerId, initialState)
    await waitFor(() => receivedStates.length > 0, 'Guest did not receive the host state')
    assert.deepEqual(receivedStates[0], initialState)

    await guest.propose('army.create', { faction: 'Nephites' })
    await waitFor(() => proposal, 'Host did not receive the guest proposal')
    const updatedState = { ...initialState, armies: [{ id: 'army-1', x: 10, y: 20 }] }
    await host.resolveProposal(proposal.peerId, proposal.proposalId, true, updatedState)
    await waitFor(() => proposalResult, 'Guest did not receive the proposal result')
    await waitFor(() => receivedStates.length > 1, 'Guest did not receive the updated state')
    assert.equal(proposalResult.accepted, true)
    assert.deepEqual(receivedStates[1], updatedState)
  } finally {
    await guest.stop()
    await host.stop()
  }
})
