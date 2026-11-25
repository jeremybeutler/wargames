import { DataChannel, MeteredPeer } from '@metered-ca/realtime'

const INVITE_VERSION = 2
const MESSAGE_LIMIT = 32 * 1024
const MAX_ROOM_PEERS = 32
const MAX_PENDING_PROPOSALS_PER_PEER = 50
const CHANNEL_PREFIX = 'bom-wargames-'
const DATA_CHANNEL_LABEL = 'bom-wargames-game-v1'
const encoder = new TextEncoder()
const decoder = new TextDecoder()

function bytesToBase64Url(bytes) {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

function base64UrlToBytes(value) {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')),
    character => character.charCodeAt(0))
}

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(24))
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
}

function makeChannelName(roomId) {
  return `${CHANNEL_PREFIX}${roomId}`
}

function parseInvite(source) {
  if (typeof source !== 'string' || source.length > MESSAGE_LIMIT) {
    throw new Error('The invitation is missing or too large')
  }

  let invite
  try {
    invite = JSON.parse(source)
  } catch {
    throw new Error('The invitation must be valid JSON')
  }
  if (invite?.version !== INVITE_VERSION
    || typeof invite.roomId !== 'string'
    || !/^[a-f0-9]{48}$/.test(invite.roomId)
    || typeof invite.roomKey !== 'string'
    || !/^[A-Za-z0-9_-]{43}$/.test(invite.roomKey)
    || typeof invite.hostPeerId !== 'string'
    || invite.hostPeerId.length < 1
    || invite.hostPeerId.length > 128
    || typeof invite.campaignSlug !== 'string'
    || !['tiles', 'artwork', 'middle-earth'].includes(invite.mapMode)) {
    throw new Error('This invitation is invalid or uses an unsupported version')
  }
  return invite
}

export async function createRoomKey() {
  const rawKey = crypto.getRandomValues(new Uint8Array(32))
  const key = await crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt'])
  return { key, secret: bytesToBase64Url(rawKey) }
}

export async function encryptRoomMessage(key, roomId, message) {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: encoder.encode(`bom-wargames-room-v1:${roomId}`) },
    key,
    encoder.encode(JSON.stringify(message)),
  )
  return encoder.encode(JSON.stringify({
    version: 1,
    iv: bytesToBase64Url(iv),
    ciphertext: bytesToBase64Url(new Uint8Array(ciphertext)),
  }))
}

export async function decryptRoomMessage(key, roomId, data) {
  let envelope
  try {
    envelope = JSON.parse(decoder.decode(data))
  } catch {
    throw new Error('Invalid encrypted room message')
  }
  if (envelope?.version !== 1
    || typeof envelope.iv !== 'string'
    || typeof envelope.ciphertext !== 'string') {
    throw new Error('Invalid encrypted room message')
  }
  const plaintext = await crypto.subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: base64UrlToBytes(envelope.iv),
      additionalData: encoder.encode(`bom-wargames-room-v1:${roomId}`),
    },
    key,
    base64UrlToBytes(envelope.ciphertext),
  )
  return JSON.parse(decoder.decode(plaintext))
}

function toMessageBytes(data) {
  if (typeof data === 'string') return encoder.encode(data)
  if (data instanceof ArrayBuffer) return new Uint8Array(data)
  throw new Error('The game peer sent an unsupported message format')
}

export class GameNetwork {
  constructor(callbacks = {}, peerFactory = apiKey => new MeteredPeer({ apiKey })) {
    this.callbacks = callbacks
    this.peerFactory = peerFactory
    this.peer = null
    this.role = null
    this.roomId = null
    this.hostPeerId = null
    this.roomKey = null
    this.roomKeySecret = null
    this.remotePeers = new Map()
    this.dataChannels = new Map()
    this.channelWaiters = new Map()
    this.pendingPeers = new Map()
    this.pendingProposalIds = new Map()
    this.members = new Set()
    this.seenProposals = new Set()
    this.lastStateRequestAt = new Map()
    this.latestState = null
    this.lastSequence = 0
    this.joinResolved = false
    this.joinTimeout = null

    this.onPeerJoined = ({ peer }) => this.addRemotePeer(peer)
    this.onPeerLeft = ({ peer }) => this.removeRemotePeer(peer.id)
    this.onData = event => {
      void this.handleMessage(event.senderPeerId, event.data).catch(error =>
        this.callbacks.onError?.(error))
    }
    this.onPeerError = ({ err }) => this.callbacks.onError?.(err)
    this.onSignallingState = ({ to }) => {
      if (to === 'reconnecting') this.callbacks.onStatus?.('reconnecting')
      if (to === 'joined') {
        this.callbacks.onStatus?.(this.role === 'host' ? 'hosting' : 'connected')
      }
      if (to === 'closed') this.callbacks.onStatus?.('disconnected')
    }
  }

  get peerId() {
    return this.peer?.peerId ?? null
  }

  createPeer(apiKey) {
    if (typeof apiKey !== 'string' || !/^pk_(live|test)_[A-Za-z0-9_-]+$/.test(apiKey.trim())) {
      throw new Error('Enter a valid Metered publishable key (pk_live_...)')
    }
    if (this.peer) throw new Error('A multiplayer session is already running')

    this.peer = this.peerFactory(apiKey.trim())
    this.peer.on('peer-joined', this.onPeerJoined)
    this.peer.on('peer-left', this.onPeerLeft)
    this.peer.on('data', this.onData)
    this.peer.on('error', this.onPeerError)
    this.peer.on('state-change', this.onSignallingState)
  }

  async host(apiKey, playerName, campaignSlug, mapMode) {
    this.roomId = randomId()
    this.role = 'host'
    try {
      const roomKey = await createRoomKey()
      this.roomKey = roomKey.key
      this.roomKeySecret = roomKey.secret
      this.createPeer(apiKey)
      await this.peer.join(makeChannelName(this.roomId))

      this.hostPeerId = this.peerId
      if (!this.hostPeerId) throw new Error('Metered did not assign a peer ID')
      this.members.add(this.hostPeerId)
      this.callbacks.onStatus?.('hosting')
      return JSON.stringify({
        version: INVITE_VERSION,
        roomId: this.roomId,
        roomKey: this.roomKeySecret,
        hostPeerId: this.hostPeerId,
        hostName: playerName.trim() || 'Host',
        campaignSlug,
        mapMode,
      }, null, 2)
    } catch (error) {
      await this.stop()
      throw error
    }
  }

  async join(inviteSource, apiKey, playerName) {
    if (this.peer) throw new Error('A multiplayer session is already running')
    const invite = parseInvite(inviteSource)
    this.role = 'guest'
    this.roomId = invite.roomId
    this.hostPeerId = invite.hostPeerId

    try {
      this.roomKey = await crypto.subtle.importKey(
        'raw',
        base64UrlToBytes(invite.roomKey),
        { name: 'AES-GCM' },
        false,
        ['encrypt', 'decrypt'],
      )
      this.createPeer(apiKey)
      await this.peer.join(makeChannelName(this.roomId))
      await this.waitForDataChannel(this.hostPeerId)

      this.callbacks.onStatus?.('requesting approval')
      await this.publish({
        type: 'join',
        playerName: playerName.trim() || 'Player',
      }, this.hostPeerId)
      this.joinTimeout = setTimeout(() => {
        if (this.role === 'guest' && !this.joinResolved) {
          this.callbacks.onStatus?.('waiting for host')
          this.callbacks.onNotice?.('Join request sent. The host still needs to approve it.')
        }
      }, 12000)
      return invite
    } catch (error) {
      await this.stop()
      throw error
    }
  }

  addRemotePeer(remote) {
    if (!remote?.id || remote.id === this.peerId) return
    this.remotePeers.set(remote.id, remote)
    remote.on('data-channel', ({ channel }) => this.attachDataChannel(remote.id, channel))
    remote.on('connection-reset', () => {
      this.closeDataChannel(remote.id)
      if (!remote.polite) this.createDataChannel(remote)
    })
    remote.on('negotiation-error', ({ err }) => this.callbacks.onError?.(err))

    if (!remote.polite) this.createDataChannel(remote)
  }

  createDataChannel(remote) {
    try {
      const channel = remote.pc.createDataChannel(DATA_CHANNEL_LABEL, { ordered: true })
      this.attachDataChannel(remote.id, channel)
    } catch (error) {
      this.callbacks.onError?.(error)
    }
  }

  attachDataChannel(peerId, channel) {
    this.closeDataChannel(peerId)
    const dataChannel = new DataChannel(channel, {
      maxBufferedAmount: 256 * 1024,
      maxQueuedSends: 100,
    })
    this.dataChannels.set(peerId, dataChannel)
    dataChannel.on('message', ({ data }) => {
      void this.handleMessage(peerId, toMessageBytes(data)).catch(error =>
        this.callbacks.onError?.(error))
    })
    dataChannel.on('open', () => this.notifyChannelWaiters(peerId, dataChannel))
    dataChannel.on('error', ({ err }) => this.callbacks.onError?.(err))
    dataChannel.on('close', () => {
      if (this.dataChannels.get(peerId) === dataChannel) this.dataChannels.delete(peerId)
      this.notifyChannelWaiters(peerId, null, new Error('The WebRTC connection to a player closed'))
    })
    if (dataChannel.readyState === 'open') this.notifyChannelWaiters(peerId, dataChannel)
  }

  closeDataChannel(peerId) {
    const channel = this.dataChannels.get(peerId)
    if (!channel) return
    this.dataChannels.delete(peerId)
    channel.close()
  }

  notifyChannelWaiters(peerId, channel, error = null) {
    const waiters = this.channelWaiters.get(peerId)
    if (!waiters) return
    for (const notify of [...waiters]) notify(channel, error)
  }

  waitForDataChannel(peerId, timeout = 20000) {
    const existing = this.dataChannels.get(peerId)
    if (existing?.readyState === 'open') return Promise.resolve(existing)
    return new Promise((resolve, reject) => {
      let settled = false
      const waiters = this.channelWaiters.get(peerId) || new Set()
      const finish = (channel, error) => {
        if (settled) return
        if (!channel && !error) return
        settled = true
        clearTimeout(timer)
        waiters.delete(finish)
        if (waiters.size === 0) this.channelWaiters.delete(peerId)
        if (channel?.readyState === 'open') resolve(channel)
        else reject(error || new Error('The WebRTC data channel did not open'))
      }
      const timer = setTimeout(() => {
        finish(null, new Error('Could not establish a WebRTC data channel. Check the Metered key, TURN setup, and network access.'))
      }, timeout)
      waiters.add(finish)
      this.channelWaiters.set(peerId, waiters)
      const current = this.dataChannels.get(peerId)
      if (current?.readyState === 'open') finish(current)
    })
  }

  removeRemotePeer(peerId) {
    this.closeDataChannel(peerId)
    this.remotePeers.delete(peerId)
    this.pendingPeers.delete(peerId)
    this.members.delete(peerId)
    this.pendingProposalIds.delete(peerId)
    this.lastStateRequestAt.delete(peerId)
    this.callbacks.onPeerLeft?.(peerId)
    if (this.role === 'guest' && peerId === this.hostPeerId) {
      this.callbacks.onStatus?.('host disconnected')
      this.callbacks.onNotice?.('The host disconnected. The saved room snapshot remains on this device.')
    }
  }

  async approvePeer(peerId, snapshot) {
    if (this.role !== 'host' || !this.pendingPeers.has(peerId)) {
      throw new Error('That player has no pending join request')
    }
    this.pendingPeers.delete(peerId)
    this.members.add(peerId)
    this.latestState = snapshot
    await this.publish({ type: 'join-approved' }, peerId)
    await this.broadcastState(snapshot)
  }

  async rejectPeer(peerId) {
    if (this.role !== 'host' || !this.pendingPeers.has(peerId)) {
      throw new Error('That player has no pending join request')
    }
    this.pendingPeers.delete(peerId)
    await this.publish({ type: 'join-rejected' }, peerId)
  }

  async propose(action, payload) {
    if (this.role !== 'guest') throw new Error('Only joined players can propose actions')
    await this.publish({
      type: 'proposal',
      proposalId: randomId(),
      action,
      payload,
    }, this.hostPeerId)
  }

  async resolveProposal(peerId, proposalId, accepted, snapshot, reason = '') {
    if (this.role !== 'host' || !this.members.has(peerId)) {
      throw new Error('The proposal sender is not an approved player')
    }
    const pending = this.pendingProposalIds.get(peerId)
    if (!pending?.has(proposalId)) throw new Error('That proposal is no longer pending')
    pending.delete(proposalId)
    await this.publish({
      type: 'proposal-result',
      proposalId,
      accepted,
      reason,
    }, peerId)
    if (accepted) await this.broadcastState(snapshot)
  }

  async broadcastState(state) {
    if (this.role !== 'host') throw new Error('Only the host can publish game state')
    this.latestState = state
    this.lastSequence += 1
    await this.publish({ type: 'state', sequence: this.lastSequence, state })
  }

  async publish(message, recipientPeerId = null) {
    if (!this.peer || !this.roomId) throw new Error('The multiplayer connection is not active')
    const data = await encryptRoomMessage(
      this.roomKey,
      this.roomId,
      { ...message, roomId: this.roomId },
    )
    if (data.byteLength > MESSAGE_LIMIT) {
      throw new Error('The game message exceeds the WebRTC data channel size limit')
    }
    const recipients = recipientPeerId
      ? [recipientPeerId]
      : [...this.members].filter(peerId => peerId !== this.peerId)
    if (recipients.length === 0) return

    const encoded = decoder.decode(data)
    const results = await Promise.allSettled(recipients.map(async peerId => {
      const channel = await this.waitForDataChannel(peerId)
      await channel.send(encoded)
    }))
    const failures = results.filter(result => result.status === 'rejected')
    if (failures.length) {
      throw new Error(`Could not deliver the game message to ${failures.length} player(s): ${failures[0].reason.message}`)
    }
  }

  async handleMessage(sender, data) {
    if (typeof sender !== 'string'
      || sender.length > 128
      || sender === this.peerId
      || !this.roomKey
      || !this.roomId) return

    let payload
    try {
      payload = await decryptRoomMessage(this.roomKey, this.roomId, data)
    } catch {
      return
    }
    if (payload.roomId !== this.roomId || typeof payload.type !== 'string') return

    if (this.role === 'host') {
      if (payload.type === 'join') {
        const playerName = typeof payload.playerName === 'string'
          ? payload.playerName.slice(0, 80)
          : 'Player'
        if (!this.members.has(sender) && !this.pendingPeers.has(sender)
          && this.members.size + this.pendingPeers.size < MAX_ROOM_PEERS) {
          this.pendingPeers.set(sender, playerName)
          this.callbacks.onJoinRequest?.({ peerId: sender, playerName })
        }
        return
      }
      if (payload.type === 'proposal' && this.members.has(sender)
        && typeof payload.proposalId === 'string'
        && /^[a-f0-9]{48}$/.test(payload.proposalId)
        && !this.seenProposals.has(payload.proposalId)
        && typeof payload.action === 'string') {
        const peerProposals = this.pendingProposalIds.get(sender) || new Set()
        if (peerProposals.size >= MAX_PENDING_PROPOSALS_PER_PEER) return
        if (this.seenProposals.size >= 10000) {
          this.seenProposals.delete(this.seenProposals.values().next().value)
        }
        this.seenProposals.add(payload.proposalId)
        peerProposals.add(payload.proposalId)
        this.pendingProposalIds.set(sender, peerProposals)
        this.callbacks.onProposal?.({
          peerId: sender,
          proposalId: payload.proposalId,
          action: payload.action,
          payload: payload.payload,
        })
      } else if (payload.type === 'state-request'
        && this.members.has(sender)
        && this.latestState) {
        const now = Date.now()
        const previousRequest = this.lastStateRequestAt.get(sender) || 0
        if (now - previousRequest >= 1000) {
          this.lastStateRequestAt.set(sender, now)
          await this.publish({
            type: 'state',
            sequence: this.lastSequence,
            state: this.latestState,
          }, sender)
        }
      }
      return
    }

    if (this.role !== 'guest' || sender !== this.hostPeerId) return
    if (payload.type === 'join-approved') {
      this.joinResolved = true
      this.members.add(sender)
      clearTimeout(this.joinTimeout)
      this.callbacks.onStatus?.('connected')
      this.callbacks.onApproved?.()
      this.startStateSync()
    } else if (payload.type === 'join-rejected') {
      this.joinResolved = true
      clearTimeout(this.joinTimeout)
      this.callbacks.onStatus?.('rejected')
      this.callbacks.onRejected?.()
    } else if (payload.type === 'state'
      && Number.isInteger(payload.sequence)
      && payload.sequence > this.lastSequence
      && payload.state
      && typeof payload.state === 'object') {
      this.lastSequence = payload.sequence
      this.callbacks.onState?.(payload.state)
    } else if (payload.type === 'proposal-result') {
      this.callbacks.onProposalResult?.({
        proposalId: payload.proposalId,
        accepted: payload.accepted === true,
        reason: typeof payload.reason === 'string' ? payload.reason : '',
      })
    }
  }

  startStateSync() {
    if (this.stateSyncTimer) clearInterval(this.stateSyncTimer)
    void this.requestState()
    this.stateSyncTimer = setInterval(() => {
      if (this.role === 'guest') void this.requestState()
    }, 15000)
  }

  async requestState() {
    try {
      await this.publish({ type: 'state-request' }, this.hostPeerId)
    } catch (error) {
      this.callbacks.onError?.(error)
    }
  }

  async stop() {
    this.role = null
    clearTimeout(this.joinTimeout)
    this.joinTimeout = null
    if (this.stateSyncTimer) clearInterval(this.stateSyncTimer)
    this.stateSyncTimer = null
    for (const peerId of this.dataChannels.keys()) this.closeDataChannel(peerId)
    this.channelWaiters.clear()
    const peer = this.peer
    this.peer = null
    if (peer) await peer.close('left game')
    this.remotePeers.clear()
    this.pendingPeers.clear()
    this.pendingProposalIds.clear()
    this.members.clear()
    this.seenProposals.clear()
    this.lastStateRequestAt.clear()
    this.latestState = null
    this.lastSequence = 0
    this.joinResolved = false
    this.roomKey = null
    this.roomKeySecret = null
    this.roomId = null
    this.hostPeerId = null
    this.callbacks.onStatus?.('disconnected')
  }
}
