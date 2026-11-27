<script setup>
/**
 * Table talk: a game's live text chat and/or voice channel, as the host chose (text, voice or
 * both). Shared by both campaigns; the game passes its API path and this tab's seat.
 *
 * Voice is peer to peer (WebRTC): every pair of people in the channel connects directly, and
 * the game server only relays each pair's connection setup (offers, answers, ICE candidates).
 * Microphones need a secure page (https, or localhost).
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  apiBase: { type: String, required: true },
  seat: { type: String, required: true },
  mode: { type: String, default: 'text' },
  /** side (a team or house id) -> CSS color, for each speaker's name. */
  sideColor: { type: Function, default: () => '' },
  /** Distance from the left edge on wide screens, to clear the game's own side panel. */
  left: { type: String, default: '14px' },
})

const allowsText = computed(() => props.mode === 'text' || props.mode === 'both')
const allowsVoice = computed(() => props.mode === 'voice' || props.mode === 'both')
const open = ref(false)
const error = ref('')

async function call(path, options = {}) {
  const response = await fetch(`${props.apiBase}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', 'X-Game-Seat': props.seat, ...(options.headers || {}) },
  })
  const type = response.headers.get('content-type') || ''
  const payload = type.includes('json') ? await response.json() : null
  if (!response.ok) throw new Error(payload?.message || payload?.error || `Request failed (${response.status})`)
  return payload
}

// ---------------------------------------------------------------- text

const messages = ref([])
const draft = ref('')
const unread = ref(0)
const sending = ref(false)
const list = ref(null)
let lastId = 0
let historyLoaded = false
let textTimer = 0
let textInFlight = false

async function pollText() {
  if (!allowsText.value || textInFlight) return
  textInFlight = true
  try {
    const fresh = await call(`/chat${lastId ? `?since=${lastId}` : ''}`)
    if (fresh.length) {
      messages.value = [...messages.value, ...fresh].slice(-150)
      lastId = fresh[fresh.length - 1].id
      // History loaded on opening the game isn't "unread"; anything after it from others is.
      if (historyLoaded && !open.value) unread.value += fresh.filter(message => message.seat !== props.seat).length
      if (open.value) scrollToEnd()
    }
    historyLoaded = true
  } catch {
    // A missed poll is retried on the next tick.
  } finally {
    textInFlight = false
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  sending.value = true
  error.value = ''
  try {
    await call('/chat', { method: 'POST', body: JSON.stringify({ text }) })
    draft.value = ''
    await pollText()
    scrollToEnd()
  } catch (problem) {
    error.value = problem.message
  } finally {
    sending.value = false
  }
}

function scrollToEnd() {
  nextTick(() => { if (list.value) list.value.scrollTop = list.value.scrollHeight })
}

function time(sentAt) {
  return new Date(sentAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
}

watch(open, isOpen => {
  if (isOpen) {
    unread.value = 0
    scrollToEnd()
  }
})

// ---------------------------------------------------------------- voice

const ICE_SERVERS = [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }]
const inVoice = ref(false)
const joining = ref(false)
const muted = ref(false)
const participants = ref([])
const connected = ref({})
const voiceSupported = typeof navigator !== 'undefined' && Boolean(navigator.mediaDevices?.getUserMedia) && typeof RTCPeerConnection !== 'undefined'
let localStream = null
let lastSignal = 0
let voiceTimer = 0
let voiceInFlight = false
const peers = new Map()

async function joinVoice() {
  if (!voiceSupported) {
    error.value = 'Voice needs a secure page (https) and a browser with microphone access.'
    return
  }
  joining.value = true
  error.value = ''
  try {
    localStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
    muted.value = false
    lastSignal = 0
    const view = await call('/voice/join', { method: 'POST', body: JSON.stringify({ muted: false }) })
    inVoice.value = true
    await handleVoice(view)
    voiceTimer = window.setInterval(pollVoice, 1000)
  } catch (problem) {
    stopLocal()
    error.value = problem?.name === 'NotAllowedError' ? 'Microphone access was blocked. Allow it in the browser to talk.' : problem.message
  } finally {
    joining.value = false
  }
}

async function leaveVoice() {
  window.clearInterval(voiceTimer)
  inVoice.value = false
  for (const other of [...peers.keys()]) closePeer(other)
  stopLocal()
  participants.value = []
  try { await call('/voice/leave', { method: 'POST' }) } catch { /* the server forgets us after a few seconds anyway */ }
}

function stopLocal() {
  localStream?.getTracks().forEach(track => track.stop())
  localStream = null
}

async function toggleMute() {
  muted.value = !muted.value
  localStream?.getAudioTracks().forEach(track => { track.enabled = !muted.value })
  try { await call('/voice/join', { method: 'POST', body: JSON.stringify({ muted: muted.value }) }) } catch { /* shown on the next poll */ }
}

async function pollVoice() {
  if (!inVoice.value || voiceInFlight) return
  voiceInFlight = true
  try {
    await handleVoice(await call(`/voice?since=${lastSignal}`))
  } catch {
    // Retried on the next tick.
  } finally {
    voiceInFlight = false
  }
}

async function handleVoice(view) {
  participants.value = view.participants
  if (!view.participants.some(person => person.seat === props.seat)) {
    // Dropped (e.g. the server restarted): rejoin quietly.
    await call('/voice/join', { method: 'POST', body: JSON.stringify({ muted: muted.value }) })
    return
  }
  const present = new Set(view.participants.map(person => person.seat))
  for (const other of [...peers.keys()]) if (!present.has(other)) closePeer(other)
  // Of each pair, the seat that sorts first makes the call, so two offers never cross.
  for (const person of view.participants) {
    if (person.seat !== props.seat && !peers.has(person.seat) && props.seat < person.seat) await createPeer(person.seat, true)
  }
  for (const signal of view.signals) {
    try {
      await onSignal(signal)
    } catch {
      // A bad or stale signal: the pair reconnects on its own after a failure.
    }
    lastSignal = Math.max(lastSignal, signal.id)
  }
}

async function createPeer(other, calling) {
  const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS })
  const audio = new Audio()
  audio.autoplay = true
  const peer = { pc, audio, pendingIce: [] }
  peers.set(other, peer)
  localStream?.getTracks().forEach(track => pc.addTrack(track, localStream))
  pc.onicecandidate = event => {
    if (event.candidate) void sendSignal(other, 'ice', JSON.stringify(event.candidate))
  }
  pc.ontrack = event => {
    audio.srcObject = event.streams[0]
    audio.play().catch(() => {})
  }
  pc.onconnectionstatechange = () => {
    connected.value = { ...connected.value, [other]: pc.connectionState }
    // A failed pair is dropped; the caller sets it up again on the next poll.
    if (pc.connectionState === 'failed') closePeer(other)
  }
  if (calling) {
    await pc.setLocalDescription(await pc.createOffer())
    await sendSignal(other, 'offer', JSON.stringify(pc.localDescription))
  }
  return peer
}

function closePeer(other) {
  const peer = peers.get(other)
  if (!peer) return
  peer.pc.close()
  peer.audio.srcObject = null
  peers.delete(other)
  const next = { ...connected.value }
  delete next[other]
  connected.value = next
}

async function onSignal(signal) {
  let peer = peers.get(signal.from)
  const data = JSON.parse(signal.data)
  if (signal.kind === 'offer') {
    if (peer) closePeer(signal.from)
    peer = await createPeer(signal.from, false)
    await peer.pc.setRemoteDescription(data)
    await flushIce(peer)
    await peer.pc.setLocalDescription(await peer.pc.createAnswer())
    await sendSignal(signal.from, 'answer', JSON.stringify(peer.pc.localDescription))
  } else if (signal.kind === 'answer' && peer) {
    await peer.pc.setRemoteDescription(data)
    await flushIce(peer)
  } else if (signal.kind === 'ice' && peer) {
    if (peer.pc.remoteDescription) await peer.pc.addIceCandidate(data)
    else peer.pendingIce.push(data)
  }
}

async function flushIce(peer) {
  for (const candidate of peer.pendingIce.splice(0)) await peer.pc.addIceCandidate(candidate)
}

function sendSignal(to, kind, data) {
  return call('/voice/signal', { method: 'POST', body: JSON.stringify({ to, kind, data }) })
}

function voiceState(person) {
  if (person.seat === props.seat) return muted.value ? 'muted' : 'you'
  if (person.muted) return 'muted'
  const state = connected.value[person.seat]
  return state === 'connected' ? 'live' : state === 'failed' ? 'no route' : 'connecting'
}

const others = computed(() => participants.value.filter(person => person.seat !== props.seat).length)

// ---------------------------------------------------------------- lifecycle

onMounted(() => {
  if (allowsText.value) {
    void pollText()
    textTimer = window.setInterval(pollText, 2500)
  }
})

onBeforeUnmount(() => {
  window.clearInterval(textTimer)
  if (inVoice.value) void leaveVoice()
})
</script>

<template>
  <div class="table-talk" :class="{ open }" :style="{ '--talk-left': left }">
    <button class="talk-toggle" type="button" :aria-expanded="open" :aria-label="open ? 'Close table talk' : 'Open table talk'"
      @click="open = !open">
      <span aria-hidden="true">{{ allowsText ? '✉' : '🎙' }}</span>
      <i v-if="unread" class="talk-unread">{{ unread > 9 ? '9+' : unread }}</i>
      <i v-if="inVoice" class="talk-live" :class="{ muted }" aria-hidden="true"></i>
    </button>

    <section v-if="open" class="talk-panel" aria-label="Table talk">
      <header>
        <strong>Table talk</strong>
        <button type="button" aria-label="Close" @click="open = false">×</button>
      </header>

      <div v-if="allowsVoice" class="talk-voice">
        <template v-if="!inVoice">
          <button class="talk-join" type="button" :disabled="joining" @click="joinVoice">
            {{ joining ? 'Connecting…' : '🎙 Join voice' }}
          </button>
          <small>{{ voiceSupported ? 'Talk with everyone at the table who has joined.' : 'Voice needs a secure (https) page.' }}</small>
        </template>
        <template v-else>
          <div class="talk-voice-controls">
            <button type="button" class="talk-mute" :class="{ muted }" @click="toggleMute">{{ muted ? '🔇 Unmute' : '🎙 Mute' }}</button>
            <button type="button" class="talk-leave" @click="leaveVoice">Leave voice</button>
          </div>
          <ul class="talk-people">
            <li v-for="person in participants" :key="person.seat">
              <span :style="{ color: sideColor(person.side) }">{{ person.name }}</span>
              <small :class="voiceState(person).replace(' ', '-')">{{ voiceState(person) }}</small>
            </li>
          </ul>
          <small v-if="!others">Waiting for others to join voice…</small>
        </template>
      </div>

      <template v-if="allowsText">
        <div ref="list" class="talk-messages" aria-live="polite">
          <p v-if="!messages.length" class="talk-empty">No messages yet. Say hello.</p>
          <p v-for="message in messages" :key="message.id" class="talk-message" :class="{ mine: message.seat === seat }">
            <span class="talk-author" :style="{ color: sideColor(message.side) }">{{ message.author }}</span>
            <time>{{ time(message.sentAt) }}</time>
            <span class="talk-text">{{ message.text }}</span>
          </p>
        </div>
        <form class="talk-form" @submit.prevent="send">
          <input v-model="draft" maxlength="300" placeholder="Message the table…" aria-label="Message" autocomplete="off" />
          <button type="submit" :disabled="sending || !draft.trim()">Send</button>
        </form>
      </template>
      <p v-if="error" class="talk-error" role="alert">{{ error }}</p>
    </section>
  </div>
</template>

<style scoped>
.table-talk { position: fixed; left: var(--talk-left, 14px); bottom: 54px; z-index: 40; font-family: var(--cine-body, 'DM Sans', sans-serif); }
.talk-toggle {
  position: relative; width: 46px; height: 46px; display: grid; place-items: center; padding: 0;
  border: 1px solid var(--cine-line-strong, #8a6833); border-radius: 50%;
  background: radial-gradient(circle at 35% 30%, #2a2216, #0d0b0e 75%); color: var(--cine-gold-hi, #f4dc9c);
  font-size: 19px; cursor: pointer; box-shadow: 0 8px 24px rgba(0,0,0,.6), 0 0 14px rgba(217,178,106,.2);
}
.talk-toggle:hover, .open .talk-toggle { border-color: var(--cine-gold, #d9b26a); box-shadow: 0 8px 24px rgba(0,0,0,.6), 0 0 20px rgba(217,178,106,.35); }
.talk-unread { position: absolute; top: -3px; right: -3px; min-width: 19px; height: 19px; padding: 0 5px; border-radius: 10px; background: var(--cine-ember, #e0703c); color: #fff; font: 700 11px/19px var(--cine-body, sans-serif); font-style: normal; box-shadow: 0 0 10px rgba(224,112,60,.6); }
.talk-live { position: absolute; bottom: 1px; right: 1px; width: 11px; height: 11px; border: 2px solid #0d0b0e; border-radius: 50%; background: #7ee0a3; box-shadow: 0 0 8px rgba(126,224,163,.8); }
.talk-live.muted { background: #8a8170; box-shadow: none; }
.talk-panel {
  position: absolute; left: 0; bottom: 56px; width: min(340px, calc(100vw - 28px)); max-height: min(70vh, 560px);
  display: flex; flex-direction: column; gap: 8px; padding: 12px;
  border: 1px solid var(--cine-line-strong, #8a6833); border-radius: 8px;
  background: var(--cine-glass, rgba(16,14,19,.94)); color: var(--cine-ink, #efe6d2);
  box-shadow: 0 24px 70px rgba(0,0,0,.75); backdrop-filter: blur(8px); animation: cine-rise .25s ease-out both;
}
.talk-panel header { display: flex; align-items: center; justify-content: space-between; }
.talk-panel header strong { color: var(--cine-gold-hi, #f4dc9c); font: 700 13px var(--cine-title, serif); letter-spacing: 2.4px; text-transform: uppercase; }
.talk-panel header button { width: 28px; height: 28px; border: 0; background: none; color: var(--cine-muted, #a79e8c); font-size: 20px; cursor: pointer; }
.talk-voice { display: grid; gap: 6px; padding: 9px; border: 1px solid var(--cine-line, rgba(217,178,106,.16)); border-radius: 6px; background: rgba(6,5,8,.55); }
.talk-voice small { color: var(--cine-muted, #a79e8c); font-size: 11px; }
.talk-join, .talk-mute, .talk-leave, .talk-form button {
  min-height: 34px; padding: 0 12px; border: 1px solid var(--cine-line-strong, #8a6833); border-radius: 6px;
  background: linear-gradient(#1d191f, #141117); color: #eadfc6; font: 600 12px var(--cine-body, sans-serif); cursor: pointer;
}
.talk-join { border-color: #f0d390; background: linear-gradient(180deg, #f3d792, #d3a75a 48%, #a7772f); color: #1c1307; font-family: var(--cine-title, serif); letter-spacing: 1px; }
.talk-join:disabled, .talk-form button:disabled { opacity: .5; cursor: default; }
.talk-voice-controls { display: flex; gap: 6px; }
.talk-voice-controls button { flex: 1; }
.talk-mute.muted { border-color: var(--cine-ember, #e0703c); color: #ffd0bc; }
.talk-people { display: grid; gap: 3px; margin: 0; padding: 0; list-style: none; }
.talk-people li { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
.talk-people small { font-size: 10px; letter-spacing: .5px; text-transform: uppercase; }
.talk-people small.live, .talk-people small.you { color: #7ee0a3; }
.talk-people small.muted { color: var(--cine-dim, #6d6557); }
.talk-people small.no-route { color: #f0a796; }
.talk-messages { flex: 1; min-height: 120px; overflow-y: auto; display: grid; align-content: start; gap: 6px; padding-right: 2px; }
.talk-empty { margin: 8px 0; color: var(--cine-dim, #6d6557); font: italic 500 14px var(--cine-serif, Georgia, serif); }
.talk-message { margin: 0; display: grid; grid-template-columns: 1fr auto; gap: 1px 8px; font-size: 13px; line-height: 1.35; }
.talk-author { color: var(--cine-gold, #d9b26a); font: 700 11px var(--cine-title, serif); letter-spacing: .6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.talk-message time { color: var(--cine-dim, #6d6557); font-size: 10px; }
.talk-text { grid-column: 1 / -1; overflow-wrap: anywhere; color: #e8dfca; }
.talk-message.mine .talk-text { color: #f6e7c2; }
.talk-form { display: flex; gap: 6px; }
.talk-form input {
  flex: 1; min-width: 0; height: 36px; padding: 0 10px; border: 1px solid var(--cine-line-strong, #8a6833); border-radius: 6px;
  outline: none; background: rgba(6,5,8,.8); color: var(--cine-ink, #efe6d2); font: 14px var(--cine-body, sans-serif);
}
.talk-form input:focus { border-color: var(--cine-gold, #d9b26a); box-shadow: 0 0 0 3px rgba(217,178,106,.14); }
.talk-error { margin: 0; color: #f6c4b4; font-size: 12px; }
/* Phones: the button sits top-left over the map (the bottom belongs to the action dock), and the
   panel drops down full width. */
@media (max-width: 860px) {
  .table-talk { top: 62px; bottom: auto; left: 10px; }
  .talk-toggle { width: 40px; height: 40px; font-size: 17px; }
  .talk-panel { top: 48px; bottom: auto; width: calc(100vw - 20px); max-height: 62vh; }
}
</style>
