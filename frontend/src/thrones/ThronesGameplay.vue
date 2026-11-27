<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { HOUSES, SIGILS, houseColor, PLACE_KINDS_WITH_LABELS } from './houses.js'

const props = defineProps({ initialCode: { type: String, default: '' } })
const emit = defineEmits(['exit'])

const API = '/api/thrones'
const SAVED_KEY = 'thrones-saved-seats'
const SCALE_RATIO = 0.5 // the map is twice as wide as tall: y % counts half as far as x %
const MIN_ZOOM = 1
const MAX_ZOOM = 5

// ------------------------------------------------------------------ state
const view = ref('home') // home | host | seat | lobby | game
const game = ref(null)
const code = ref('')
const seat = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)
const joinCode = ref(props.initialCode || '')
const playerName = ref('')
const hostName = ref('')
const mapWidthMiles = ref(2500)
const roundLimit = ref(20)
const computerDead = ref(true)
const chosenHouse = ref('')
const chosenHeir = ref('')
const savedSeats = ref([])
const rulesOpen = ref(false)
const panel = ref('house') // mobile tabs: map | house | realm
const realmOpen = ref(false) // the realm drawer on mid-size screens
const movementMode = ref(false)
const destination = ref(null)
const preview = ref(null)
const previewLoading = ref(false)
const powerTarget = ref('')
const selectedMarker = ref('')
const fateShown = ref(null)
const battleShown = ref(null)
const battlePhase = ref('done')
const battleFaces = ref({ attacker: [], defender: [] })
let pollTimer = null
let previewRequest = 0
let seenFate = 0
let seenBattle = 0

// ------------------------------------------------------------------ helpers
const houses = computed(() => game.value?.houses || [])
const own = computed(() => houses.value.find(house => house.id === game.value?.ownHouseId) || null)
const active = computed(() => houses.value.find(house => house.id === game.value?.activeHouse) || null)
const isHost = computed(() => Boolean(game.value?.host))
const isMyTurn = computed(() => game.value?.status === 'campaign' && Boolean(own.value) && game.value.activeHouse === own.value.id)
const over = computed(() => game.value?.status === 'over')

function house(id) {
  return houses.value.find(item => item.id === id)
}
function houseName(id) {
  if (!id || id === 'neutral') return 'Neutral'
  return house(id)?.name || id
}
function allied(a, b) {
  return Boolean(a && b) && (game.value?.pacts || []).some(pact => (pact.a === a && pact.b === b) || (pact.a === b && pact.b === a))
}
function friendly(a, b) {
  return a === b || allied(a, b)
}
function same(x1, y1, x2, y2) {
  return x1 !== null && x2 !== null && Math.hypot(x1 - x2, y1 - y2) < 0.5
}
function pct(value) {
  return `${value}%`
}
function plural(count, word) {
  return `${count} ${word}${count === 1 ? '' : 's'}`
}
const missingPortraits = ref(new Set())
function portraitSlug(name) {
  return (name || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')
}
function hasPortrait(name) {
  return Boolean(name) && !missingPortraits.value.has(portraitSlug(name))
}
function portraitMissing(name) {
  missingPortraits.value = new Set([...missingPortraits.value, portraitSlug(name)])
}

const castles = computed(() => game.value?.castles || [])
const castleHere = computed(() => own.value && own.value.x !== null
  ? castles.value.find(castle => same(castle.x, castle.y, own.value.x, own.value.y)) || null : null)
const enemiesHere = computed(() => !own.value || own.value.x === null ? [] : houses.value.filter(other =>
  other.alive && other.id !== own.value.id && other.x !== null && !allied(own.value.id, other.id)
  && same(other.x, other.y, own.value.x, own.value.y)))
const alliesHere = computed(() => !own.value || own.value.x === null ? [] : houses.value.filter(other =>
  other.alive && other.id !== own.value.id && other.x !== null && allied(own.value.id, other.id)
  && same(other.x, other.y, own.value.x, own.value.y)))

function dice(item) {
  if (!item || item.units === null || item.units === undefined) return null
  let count = Math.max(1, item.units) + 2 * (item.dragons?.length || 0)
  if (item.id === 'dead' && game.value?.iceDragon) count += 2
  return count
}

const attackTargets = computed(() => {
  if (!isMyTurn.value || !own.value || own.value.attacksLeft <= 0) return []
  if (enemiesHere.value.length) {
    return enemiesHere.value.map(enemy => ({ id: enemy.id, label: `${enemy.leader} (${enemy.name})`, dice: dice(enemy), kind: 'army' }))
  }
  const castle = castleHere.value
  if (castle && castle.owner !== own.value.id && !allied(own.value.id, castle.owner)) {
    if (own.value.id === 'dead' && game.value.season !== 'Winter') return []
    return [{ id: castle.id, label: castle.name, dice: Math.max(1, castle.units), kind: 'castle' }]
  }
  return []
})

const canMove = computed(() => isMyTurn.value && !own.value.moved && !own.value.attacked && own.value.movementRemaining > 0.01)
const canRecruit = computed(() => isMyTurn.value && own.value.id !== 'dead' && castleHere.value?.owner === own.value.id
  && own.value.recruitedThisTurn < 2)
const canFortify = computed(() => isMyTurn.value && castleHere.value?.owner === own.value.id && !own.value.fortifiedThisTurn)
const atFreeCity = computed(() => castleHere.value && ['Braavos', 'Pentos'].includes(castleHere.value.name)
  && (castleHere.value.owner === 'neutral' || friendly(own.value?.id, castleHere.value.owner)))
const canHire = computed(() => isMyTurn.value && own.value.id !== 'dead' && atFreeCity.value && !own.value.hiredThisTurn)
const canLoan = computed(() => isMyTurn.value && atFreeCity.value && castleHere.value?.name === 'Braavos' && !own.value.loanDue)
const canRepay = computed(() => isMyTurn.value && own.value.loanDue > 0)
const canBribe = computed(() => isMyTurn.value && own.value.id === 'lannister' && castleHere.value?.owner === 'neutral')

const powerTargets = computed(() => {
  if (!own.value) return []
  if (own.value.id === 'baratheon') {
    return houses.value.filter(item => item.alive && item.id !== 'baratheon' && item.id !== 'dead'
      && item.heirIndex < item.heirs.length - 1)
  }
  if (own.value.id === 'martell') return enemiesHere.value.filter(item => item.id !== 'dead')
  return []
})
const powerReady = computed(() => {
  const me = own.value
  if (!isMyTurn.value || !me || me.powerUsed) return false
  switch (me.id) {
    case 'stark': return castleHere.value?.owner === 'stark' && castleHere.value.region === 'The North'
    case 'lannister': return castleHere.value?.name === "King's Landing"
    case 'targaryen': return !me.attacked
    case 'baratheon': return powerTargets.value.length > 0
    case 'greyjoy': return Boolean(castleHere.value) && !friendly('greyjoy', castleHere.value.owner) && castleHere.value.units <= 1
    case 'martell': return powerTargets.value.length > 0
    case 'dead': return game.value.season === 'Winter' && !me.moved
    default: return false
  }
})
// On phones the map is its own tab; open it on this house's army.
let centeredOnce = false
watch(panel, async value => {
  if (value !== 'map' || centeredOnce) return
  centeredOnce = true
  await nextTick()
  centerOnOwn()
})
watch(powerTargets, targets => {
  if (!targets.some(target => target.id === powerTarget.value)) powerTarget.value = targets[0]?.id || ''
})

const incomingOffers = computed(() => (game.value?.proposals || []).filter(p => p.to === own.value?.id))
const outgoingOffers = computed(() => (game.value?.proposals || []).filter(p => p.from === own.value?.id))
const pactRows = computed(() => houses.value.filter(item => item.alive && item.id !== own.value?.id && item.id !== 'dead' && !item.ai)
  .map(item => {
    const pact = (game.value?.pacts || []).find(p => (p.a === item.id && p.b === own.value?.id) || (p.b === item.id && p.a === own.value?.id))
    return { house: item, pact, offered: outgoingOffers.value.some(p => p.to === item.id) }
  }))
const isOathbreaker = computed(() => own.value && own.value.oathbreakerUntil >= (game.value?.round || 0))

const winterIn = computed(() => game.value ? Math.max(0, game.value.winterRound - game.value.round) : 0)
const crownText = computed(() => {
  if (!game.value?.crownHolder) return 'No one holds the Iron Throne'
  return `${houseName(game.value.crownHolder)} · ${plural(game.value.crownStreak, 'round')} of 3`
})
const kingsLanding = computed(() => castles.value.find(castle => castle.name === "King's Landing"))
const recentEvents = computed(() => (game.value?.events || []).slice(-40).reverse())

// ------------------------------------------------------------------ map markers
const mapZoom = ref(1)
const mapPan = ref({ x: 0, y: 0 })
const mapStage = ref(null)
const mapWorld = ref(null)
const gesturing = ref(false)
const pointers = new Map()
let drag = null
let pinch = null
let suppressClick = false

const worldStyle = computed(() => ({
  transform: `translate(${mapPan.value.x}px, ${mapPan.value.y}px) scale(${mapZoom.value})`,
  '--marker-scale': 1 / mapZoom.value,
}))

const markers = computed(() => {
  if (!game.value || game.value.status === 'lobby') return []
  const list = []
  for (const castle of castles.value) list.push({ key: `c:${castle.id}`, kind: 'castle', x: castle.x, y: castle.y, castle })
  for (const item of houses.value) {
    if (item.alive && item.x !== null) list.push({ key: `h:${item.id}`, kind: 'house', x: item.x, y: item.y, house: item })
  }
  return tile(list)
})

/** Castles stay where they are painted; armies sharing a spot fan out around it. */
function tile(list) {
  const groups = []
  for (const marker of list) {
    const group = groups.find(items => same(items[0].x, items[0].y, marker.x, marker.y))
    if (group) group.push(marker)
    else groups.push([marker])
  }
  return groups.flatMap(group => {
    const anchor = group.find(marker => marker.kind === 'castle') || null
    const armies = group.filter(marker => marker !== anchor)
    const placed = anchor ? [{ ...anchor, style: { left: pct(anchor.x), top: pct(anchor.y) } }] : []
    armies.forEach((marker, index) => {
      const fan = anchor || armies.length > 1
      const angle = -Math.PI / 2 + index * (Math.PI * 2 / Math.max(armies.length, 3)) + (anchor ? 0.6 : 0)
      const distance = fan ? 26 : 0
      placed.push({ ...marker, style: {
        left: `calc(${marker.x}% + ${Math.cos(angle) * distance}px * var(--marker-scale))`,
        top: `calc(${marker.y}% + ${Math.sin(angle) * distance}px * var(--marker-scale))`,
      } })
    })
    return placed
  })
}
function minorCastle(castle) {
  return !castle.great && !['capital', 'seat', 'city'].includes(castle.kind) && mapZoom.value < 1.75
}

const labels = computed(() => {
  if (!game.value?.places) return []
  return game.value.places.filter(place => PLACE_KINDS_WITH_LABELS.includes(place.kind) || mapZoom.value >= 1.6
    || (place.kind === 'landmark' && mapZoom.value >= 1.25) || place.kind === 'dead')
})

function clampZoom(zoom) {
  return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Number(zoom.toFixed(2))))
}
function clampPan(pan, zoom = mapZoom.value) {
  const stage = mapStage.value
  const world = mapWorld.value
  if (!stage || !world) return pan
  const limitX = Math.max(0, (world.offsetWidth * zoom - stage.clientWidth) / 2)
  const limitY = Math.max(0, (world.offsetHeight * zoom - stage.clientHeight) / 2)
  return { x: Math.max(-limitX, Math.min(limitX, pan.x)), y: Math.max(-limitY, Math.min(limitY, pan.y)) }
}
function stageOffset(clientX, clientY) {
  const bounds = mapStage.value.getBoundingClientRect()
  return { x: clientX - (bounds.left + bounds.width / 2), y: clientY - (bounds.top + bounds.height / 2) }
}
function zoomAround(next, anchor, fromZoom = mapZoom.value, fromPan = mapPan.value, toAnchor = anchor) {
  const zoom = clampZoom(next)
  const ratio = zoom / fromZoom
  mapPan.value = clampPan({ x: toAnchor.x - ratio * (anchor.x - fromPan.x), y: toAnchor.y - ratio * (anchor.y - fromPan.y) }, zoom)
  mapZoom.value = zoom
}
function zoomBy(delta) {
  zoomAround(mapZoom.value + delta, { x: 0, y: 0 })
}
function resetView() {
  mapZoom.value = 1
  mapPan.value = { x: 0, y: 0 }
}
function centerOnOwn() {
  if (!own.value || own.value.x === null || !mapWorld.value || !mapStage.value) return
  // Zoom in first only when the whole map already fits, so centering has room to move.
  if (mapZoom.value < 2 && mapWorld.value.offsetWidth * mapZoom.value <= mapStage.value.clientWidth * 1.05) mapZoom.value = 2
  mapPan.value = clampPan({
    x: (0.5 - own.value.x / 100) * mapWorld.value.offsetWidth * mapZoom.value,
    y: (0.5 - own.value.y / 100) * mapWorld.value.offsetHeight * mapZoom.value,
  })
}
function onPointerDown(event) {
  if ((event.pointerType === 'mouse' && event.button !== 0) || event.target.closest?.('.zoom-controls, .marker, .marker-info')) return
  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  gesturing.value = true
  try { event.currentTarget.setPointerCapture(event.pointerId) } catch { /* pointer already gone */ }
  if (pointers.size === 1) {
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, pan: { ...mapPan.value }, moved: false }
  } else if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), center: stageOffset((a.x + b.x) / 2, (a.y + b.y) / 2), zoom: mapZoom.value, pan: { ...mapPan.value } }
    drag = null
  }
}
function onPointerMove(event) {
  if (!pointers.has(event.pointerId)) return
  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  if (pinch && pointers.size >= 2) {
    const [a, b] = [...pointers.values()]
    const distance = Math.hypot(a.x - b.x, a.y - b.y)
    if (pinch.distance > 0) zoomAround(pinch.zoom * distance / pinch.distance, pinch.center, pinch.zoom, pinch.pan, stageOffset((a.x + b.x) / 2, (a.y + b.y) / 2))
    return
  }
  if (!drag || drag.id !== event.pointerId) return
  const dx = event.clientX - drag.x
  const dy = event.clientY - drag.y
  if (!drag.moved && Math.hypot(dx, dy) > 6) drag.moved = true
  if (drag.moved) mapPan.value = clampPan({ x: drag.pan.x + dx, y: drag.pan.y + dy })
}
function onPointerUp(event) {
  if (!pointers.has(event.pointerId)) return
  pointers.delete(event.pointerId)
  if (!pointers.size) window.setTimeout(() => { gesturing.value = false }, 120)
  if (pinch) {
    suppressClick = true
    if (pointers.size < 2) pinch = null
    drag = null
    return
  }
  if (drag?.moved) suppressClick = true
  drag = null
}
function onWheel(event) {
  zoomAround(mapZoom.value + (event.deltaY < 0 ? 0.25 : -0.25), stageOffset(event.clientX, event.clientY))
}
function onMapClick(event) {
  if (suppressClick) {
    suppressClick = false
    return
  }
  selectedMarker.value = ''
  if (!movementMode.value || !mapWorld.value) return
  const bounds = mapWorld.value.getBoundingClientRect()
  const x = Math.max(0, Math.min(100, ((event.clientX - bounds.left) / bounds.width) * 100))
  const y = Math.max(0, Math.min(100, ((event.clientY - bounds.top) / bounds.height) * 100))
  chooseDestination({ x, y })
}
function onMarkerClick(marker) {
  if (movementMode.value) {
    chooseDestination({ x: marker.x, y: marker.y })
    return
  }
  selectedMarker.value = selectedMarker.value === marker.key ? '' : marker.key
}
function onResize() {
  mapPan.value = clampPan(mapPan.value)
}

// ------------------------------------------------------------------ API
async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(seat.value ? { 'X-Game-Seat': seat.value } : {}), ...(options.headers || {}) }
  const response = await fetch(path, { ...options, headers })
  const type = response.headers.get('content-type') || ''
  const payload = type.includes('json') ? await response.json() : await response.text()
  if (!response.ok) {
    const message = payload && typeof payload === 'object' ? payload.message || payload.error : payload
    throw new Error(message || `Request failed (${response.status})`)
  }
  return payload
}

async function run(task) {
  error.value = ''
  busy.value = true
  try {
    await task()
  } catch (problem) {
    error.value = problem.message
  } finally {
    busy.value = false
  }
}

function readSaved() {
  try {
    const list = JSON.parse(localStorage.getItem(SAVED_KEY) || '[]')
    return Array.isArray(list) ? list.filter(item => item?.code && item?.seatId) : []
  } catch {
    return []
  }
}
function writeSaved(list) {
  try { localStorage.setItem(SAVED_KEY, JSON.stringify(list)) } catch { /* private mode */ }
  savedSeats.value = readSaved()
}
function remember(gameView) {
  const id = gameView.host ? 'host' : gameView.ownHouseId
  if (!id) return
  seat.value = id
  writeSaved([...readSaved().filter(item => !(item.code === gameView.code && item.seatId === id)), { code: gameView.code, seatId: id }])
  const url = new URL(window.location.href)
  for (const key of ['game', 'seat', 'view']) url.searchParams.delete(key)
  url.searchParams.set('thrones', gameView.code)
  url.searchParams.set('seat', id)
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}`)
}
function savedLabel(item) {
  return item.seatId === 'host' ? 'Host' : item.seatId.charAt(0).toUpperCase() + item.seatId.slice(1)
}

function show(gameView) {
  const firstLoad = !game.value || game.value.code !== gameView.code
  game.value = gameView
  code.value = gameView.code
  if (firstLoad) {
    seenFate = Number(sessionStorage.getItem(`thrones-fate-${gameView.code}`) || 0)
    seenBattle = Number(sessionStorage.getItem(`thrones-battle-${gameView.code}`) || gameView.lastBattle?.id || 0)
  }
  view.value = gameView.status === 'lobby' ? (seat.value ? 'lobby' : 'seat') : 'game'
  if (gameView.fate && gameView.fate.count > seenFate) {
    seenFate = gameView.fate.count
    sessionStorage.setItem(`thrones-fate-${gameView.code}`, String(seenFate))
    fateShown.value = gameView.fate
  }
  if (gameView.lastBattle && gameView.lastBattle.id > seenBattle) {
    seenBattle = gameView.lastBattle.id
    sessionStorage.setItem(`thrones-battle-${gameView.code}`, String(seenBattle))
    playBattle(gameView.lastBattle)
  }
}

async function refresh() {
  // Before a house is claimed the lobby is public, so the house picker keeps refreshing too.
  if (!code.value || (!seat.value && view.value !== 'seat')) return
  try {
    show(await api(`${API}/${encodeURIComponent(code.value)}`))
  } catch (problem) {
    error.value = problem.message
    stopPolling()
  }
}
function startPolling() {
  stopPolling()
  let inFlight = false
  pollTimer = window.setInterval(async () => {
    if (inFlight || busy.value) return
    inFlight = true
    await refresh()
    inFlight = false
  }, 2000)
}
function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = null
}

function hostGame() {
  return run(async () => {
    seat.value = ''
    const created = await api(API, {
      method: 'POST',
      body: JSON.stringify({ playerName: hostName.value, mapWidthMiles: mapWidthMiles.value, roundLimit: roundLimit.value, computerDead: computerDead.value }),
    })
    remember(created)
    show(created)
    startPolling()
  })
}

function findGame() {
  return run(async () => {
    const wanted = joinCode.value.trim().toUpperCase()
    seat.value = ''
    const found = await api(`${API}/${encodeURIComponent(wanted)}`)
    if (found.status !== 'lobby') throw new Error('This game has already started. Resume it from the device you joined with.')
    game.value = found
    code.value = found.code
    view.value = 'seat'
    startPolling()
  })
}

function joinHouse() {
  if (!chosenHouse.value) {
    error.value = 'Choose a house.'
    return
  }
  return run(async () => {
    seat.value = ''
    const joined = await api(`${API}/${encodeURIComponent(code.value)}/join`, {
      method: 'POST',
      body: JSON.stringify({ playerName: playerName.value, houseId: chosenHouse.value, heir: chosenHeir.value || null }),
    })
    remember(joined)
    show(joined)
    startPolling()
  })
}

function resume(item) {
  return run(async () => {
    seat.value = item.seatId
    code.value = item.code
    show(await api(`${API}/${encodeURIComponent(item.code)}`))
    remember(game.value)
    startPolling()
  })
}

function forget(item) {
  fetch(`${API}/${encodeURIComponent(item.code)}/session`, { method: 'DELETE', headers: { 'X-Game-Seat': item.seatId } }).catch(() => {})
  writeSaved(readSaved().filter(saved => !(saved.code === item.code && saved.seatId === item.seatId)))
}

function startGame() {
  return run(async () => show(await api(`${API}/${encodeURIComponent(code.value)}/start`, { method: 'POST' })))
}

function act(path, body = {}) {
  return run(async () => {
    previewRequest += 1
    const result = await api(`${API}/${encodeURIComponent(code.value)}/${path}`, { method: 'POST', body: JSON.stringify(body) })
    cancelMove()
    selectedMarker.value = ''
    show(result)
  })
}

// ------------------------------------------------------------------ movement
function startMove() {
  movementMode.value = true
  destination.value = null
  preview.value = null
  selectedMarker.value = ''
  panel.value = 'map'
}
function cancelMove() {
  movementMode.value = false
  destination.value = null
  preview.value = null
  previewLoading.value = false
}
async function chooseDestination(point) {
  destination.value = point
  const request = ++previewRequest
  previewLoading.value = true
  preview.value = null
  try {
    const result = await api(`${API}/${encodeURIComponent(code.value)}/movement-preview`, { method: 'POST', body: JSON.stringify(point) })
    if (request !== previewRequest) return
    preview.value = result
    if (!result.error) destination.value = { x: result.x, y: result.y }
  } catch (problem) {
    if (request === previewRequest) error.value = problem.message
  } finally {
    if (request === previewRequest) previewLoading.value = false
  }
}
const previewOk = computed(() => preview.value && !preview.value.error && preview.value.remaining >= -0.0001)
function confirmMove() {
  if (!destination.value) return
  return act('move', { x: destination.value.x, y: destination.value.y })
}
const modeLabel = { land: 'March', sail: 'Sail', fly: 'Fly' }

// ------------------------------------------------------------------ battle and fate presentation
function playBattle(log) {
  battleShown.value = log
  battlePhase.value = 'rolling'
  const roll = () => ({
    attacker: log.attackerDice.map(() => 1 + Math.floor(Math.random() * 6)),
    defender: log.defenderDice.map(() => 1 + Math.floor(Math.random() * 6)),
  })
  battleFaces.value = roll()
  let ticks = 0
  const timer = window.setInterval(() => {
    ticks += 1
    battleFaces.value = roll()
    if (ticks >= 9) {
      window.clearInterval(timer)
      battleFaces.value = { attacker: log.attackerDice, defender: log.defenderDice }
      battlePhase.value = 'done'
    }
  }, 90)
}
function battleSummary(log) {
  if (log.captured) return `${log.defender} was captured.`
  if (log.captured === false && log.attackerWon) {
    return log.defenderUnits ? `${log.defender}'s garrison is down to ${log.defenderUnits}.` : `${log.defender}'s walls stand empty. Beat them once more to take it.`
  }
  if (log.attackerWon) return log.defenderFell ? `${log.defender} fell.` : `${log.attacker} won the battle.`
  return log.attackerFell ? `${log.attacker} fell.` : `${log.defender} held.`
}

// ------------------------------------------------------------------ lobby helpers
const selectedHouseData = computed(() => houses.value.find(item => item.id === chosenHouse.value))
watch(chosenHouse, () => { chosenHeir.value = selectedHouseData.value?.heirs?.[0] || '' })
const claimedCount = computed(() => houses.value.filter(item => item.claimed).length)
const joinLink = computed(() => code.value ? `${window.location.origin}${window.location.pathname}?thrones=${code.value}` : '')
async function copyLink() {
  try {
    await navigator.clipboard.writeText(joinLink.value)
    notice.value = 'Join link copied.'
  } catch {
    notice.value = joinLink.value
  }
}

function backToMenu() {
  stopPolling()
  game.value = null
  code.value = ''
  seat.value = ''
  view.value = 'home'
  const url = new URL(window.location.href)
  url.searchParams.delete('thrones')
  url.searchParams.delete('seat')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}`)
}

onMounted(async () => {
  savedSeats.value = readSaved()
  window.addEventListener('resize', onResize)
  const params = new URLSearchParams(window.location.search)
  const urlCode = params.get('thrones')
  const urlSeat = params.get('seat')
  if (urlCode && urlSeat) {
    await resume({ code: urlCode, seatId: urlSeat })
  } else if (urlCode || props.initialCode) {
    joinCode.value = urlCode || props.initialCode
    await findGame()
  }
})
onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <main class="thrones-app">
    <header class="topbar">
      <a class="brand" href="#" @click.prevent="view === 'home' || view === 'host' ? emit('exit') : backToMenu()">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 21V9l3 3 2-7 3 6 3-6 2 7 3-3v12z" /></svg>
        </span>
        <span><strong>THE IRON THRONE</strong><small>A WARGAMES CAMPAIGN</small></span>
      </a>
      <div class="topbar-title">
        {{ view === 'host' ? 'HOST A GAME' : view === 'seat' ? 'CHOOSE YOUR HOUSE' : view === 'lobby' ? 'GAME LOBBY'
          : view === 'game' ? (over ? 'THE GAME IS OVER' : isMyTurn ? 'YOUR TURN' : `${(active?.name || '').toUpperCase()}'S TURN`) : 'WESTEROS & ESSOS' }}
      </div>
      <div class="topbar-actions">
        <span v-if="game && view === 'game'" class="status-pill"><i></i>ROUND {{ game.round }} · {{ game.season.toUpperCase() }}</span>
        <button v-if="view === 'game'" class="ghost-button realm-toggle" type="button" :aria-expanded="realmOpen" @click="realmOpen = !realmOpen">Realm</button>
        <button v-if="game" class="ghost-button" type="button" @click="rulesOpen = true">Rules</button>
        <button class="ghost-button exit-button" type="button" @click="emit('exit')">War of the Ring</button>
      </div>
    </header>

    <div v-if="error" class="message error" role="alert">{{ error }}<button type="button" aria-label="Dismiss" @click="error = ''">×</button></div>
    <div v-if="notice" class="message notice" role="status">{{ notice }}<button type="button" aria-label="Dismiss" @click="notice = ''">×</button></div>
    <div v-if="over" class="victory" role="status">{{ game.winnerText }}</div>

    <!-- rules -->
    <div v-if="rulesOpen" class="backdrop" @click.self="rulesOpen = false">
      <section class="sheet rules" role="dialog" aria-modal="true" aria-labelledby="rules-title">
        <header><h2 id="rules-title">How to play The Iron Throne</h2><button type="button" aria-label="Close" @click="rulesOpen = false">×</button></header>
        <h3>Winning</h3>
        <ul>
          <li><b>The Iron Throne:</b> hold King's Landing at the end of 3 rounds in a row. Houses that kept a pact with you the whole time win with you as Hand of the King.</li>
          <li><b>The Seven Kingdoms:</b> hold 7 of the 9 great seats (Winterfell, the Eyrie, Riverrun, Pyke, Casterly Rock, Highgarden, Storm's End, Sunspear, King's Landing) at the end of a round.</li>
          <li><b>Slay the Night King</b> while holding 3 great seats and you win at once. Otherwise you gain 6 gold and every house gains 2 units.</li>
          <li><b>The Long Night:</b> the Dead win if they take King's Landing or hold 5 castles south of the Wall. If the server plays the Dead, everyone loses.</li>
          <li>The last house standing wins. After the final round, the house with the most great seats wins.</li>
        </ul>
        <h3>Your turn</h3>
        <ul>
          <li>Collect gold: 1 for each castle, 2 for your own seat, 3 for King's Landing (Northern castles give 1 less in Winter).</li>
          <li>Move once, then attack once. You cannot move after attacking. Spend gold at any time during your turn.</li>
          <li><b>Raise levies</b> at a castle you hold (2 gold a unit, 2 a turn). <b>Fortify</b> it (2 gold, +1 garrison). In Braavos or Pentos, <b>hire sellswords</b> (4 gold for 2 units). In Braavos, <b>borrow 6 gold</b> from the Iron Bank and repay 8 within 4 rounds, or your seat is sacked.</li>
        </ul>
        <h3>Movement</h3>
        <ul>
          <li>12 movement points a turn. Miles per point: plains 15, forest 10, desert 10, river 10 (+1 per crossing), snow 8, mountains 7.5, volcanic ground 6.</li>
          <li>Water: armies sail only from a port to a port, using the whole turn. The Greyjoys sail coast to coast. Dragons fly anywhere at 20 miles a point.</li>
          <li>The Wall can only be crossed by stepping onto a castle on the Wall that you or an ally hold, until the Dead breach it.</li>
          <li>Ending in mountains or volcanic ground hides your army; forest hides it half the time. Allies always see each other.</li>
        </ul>
        <h3>Battle</h3>
        <ul>
          <li>Move onto an army or castle, then attack. An army defending a castle must be beaten first.</li>
          <li>Each side rolls a die per unit (a lone leader or empty walls roll one); dragons add 2 dice each. Higher total wins; ties go to the defender. The loser loses a unit.</li>
          <li>A castle with no garrison left is captured when beaten. Allies standing together defend together.</li>
          <li><b>Valar morghulis:</b> an army with no units that loses again falls, and the house's next heir rises at its seat with 1 unit. With no heirs or no castle left, the house is out.</li>
          <li>The Eyrie's Moon Door: attackers who lose there lose 2 units.</li>
        </ul>
        <h3>Pacts and betrayal</h3>
        <ul>
          <li>Offer pacts at any time. Allies cannot attack each other, can share a spot and defend together.</li>
          <li>Break a pact on your turn and attack that house the same turn: a <b>Red Wedding</b>, +2 on every die. You become an oathbreaker for 3 rounds: no pacts and no income.</li>
        </ul>
        <h3>Seasons and the Dead</h3>
        <ul>
          <li>Summer, then Autumn, then Winter (round 8, sooner if Fate wills it). From Autumn on, snow creeps south each round.</li>
          <li>In Winter the Dead grow by a unit each round and may attack the Wall. Every battle they win against an army raises another unit.</li>
          <li>The Night King only dies to dragons, Valyrian steel (Jon, Jaime or Arya leading) or the holder of Dragonstone's dragonglass and their allies. Otherwise he retreats and returns.</li>
          <li>Every round after the first begins with a Fate card that changes the realm.</li>
        </ul>
        <h3>The houses</h3>
        <ul>
          <li v-for="(data, id) in HOUSES" :key="id"><b>{{ id === 'dead' ? 'The Dead' : `House ${id.charAt(0).toUpperCase()}${id.slice(1)}` }}.</b> {{ data.passive }} Once per game: {{ data.power }}</li>
        </ul>
      </section>
    </div>

    <!-- fate card -->
    <div v-if="fateShown" class="backdrop" @click.self="fateShown = null">
      <section class="fate-card" role="dialog" aria-modal="true" aria-labelledby="fate-title">
        <p class="eyebrow">FATE · ROUND {{ fateShown.round }}</p>
        <h2 id="fate-title">{{ fateShown.title }}</h2>
        <p>{{ fateShown.text }}</p>
        <button class="gold-button" type="button" @click="fateShown = null">So it is written</button>
      </section>
    </div>

    <!-- battle -->
    <div v-if="battleShown" class="backdrop light" @click.self="battlePhase === 'done' && (battleShown = null)">
      <section class="battle-card" role="dialog" aria-label="Battle">
        <p class="eyebrow">BATTLE · ROUND {{ battleShown.round }}</p>
        <div class="battle-sides">
          <div>
            <strong>{{ battleShown.attacker }}</strong><small>attacking{{ battleShown.attackerModifier ? ` · ${battleShown.attackerModifier > 0 ? '+' : ''}${battleShown.attackerModifier} per die` : '' }}</small>
            <div class="dice"><i v-for="(face, index) in battleFaces.attacker" :key="`a${index}`" :class="{ win: battlePhase === 'done' && battleShown.attackerWon }">{{ face }}</i></div>
            <span v-if="battlePhase === 'done'" class="total">Total {{ battleShown.attackerTotal }}</span>
          </div>
          <span class="vs">vs</span>
          <div>
            <strong>{{ battleShown.defender }}</strong><small>defending{{ battleShown.defenderModifier ? ` · ${battleShown.defenderModifier > 0 ? '+' : ''}${battleShown.defenderModifier} per die` : '' }}</small>
            <div class="dice"><i v-for="(face, index) in battleFaces.defender" :key="`d${index}`" class="defender" :class="{ win: battlePhase === 'done' && !battleShown.attackerWon }">{{ face }}</i></div>
            <span v-if="battlePhase === 'done'" class="total">Total {{ battleShown.defenderTotal }}</span>
          </div>
        </div>
        <p class="battle-result" aria-live="polite">{{ battlePhase === 'done' ? battleSummary(battleShown) : 'Rolling…' }}</p>
        <button v-if="battlePhase === 'done'" class="action-button" type="button" @click="battleShown = null">Close</button>
      </section>
    </div>

    <!-- home -->
    <section v-if="view === 'home'" class="welcome">
      <div class="welcome-card">
        <svg class="crown" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 21V9l3 3 2-7 3 6 3-6 2 7 3-3v12z" /></svg>
        <p class="eyebrow">A GAME OF HOUSES, BETRAYAL AND WINTER</p>
        <h1>The Iron Throne</h1>
        <p class="subtitle">Every house for itself. Winter is coming.</p>
        <form class="join-form" @submit.prevent="findGame">
          <label for="thrones-code">GAME CODE</label>
          <input id="thrones-code" v-model="joinCode" maxlength="6" autocomplete="one-time-code" placeholder="Enter code" />
          <button class="gold-button" type="submit" :disabled="busy || !joinCode.trim()">Join game</button>
        </form>
        <div v-if="savedSeats.length" class="saved">
          <p class="eyebrow">RESUME A SAVED GAME</p>
          <div v-for="item in savedSeats" :key="`${item.code}-${item.seatId}`" class="saved-row">
            <button type="button" class="saved-button" :disabled="busy" @click="resume(item)"><strong>{{ savedLabel(item) }}</strong><small>Game {{ item.code }}</small></button>
            <button type="button" class="forget" :aria-label="`Forget ${savedLabel(item)} in game ${item.code}`" @click="forget(item)">×</button>
          </div>
        </div>
        <button class="link-button" type="button" @click="view = 'host'">Host a new game on this device →</button>
        <button class="link-button muted" type="button" @click="emit('exit')">← Back to War of the Ring</button>
      </div>
    </section>

    <!-- host setup -->
    <section v-else-if="view === 'host'" class="welcome">
      <form class="welcome-card host-card" @submit.prevent="hostGame">
        <p class="eyebrow">HOST A GAME</p>
        <h1>Raise your banners</h1>
        <label for="host-name">YOUR NAME</label>
        <input id="host-name" v-model="hostName" maxlength="30" placeholder="Host name" />
        <label for="map-miles">MAP WIDTH · {{ mapWidthMiles.toLocaleString() }} MILES</label>
        <input id="map-miles" v-model.number="mapWidthMiles" type="range" min="1000" max="6000" step="250" />
        <p class="hint">Wider maps make journeys longer. 2,500 miles puts Winterfell about three turns from King's Landing.</p>
        <label for="round-limit">LAST ROUND · {{ roundLimit }}</label>
        <input id="round-limit" v-model.number="roundLimit" type="range" min="8" max="40" step="1" />
        <label class="check"><input v-model="computerDead" type="checkbox" /> If no one plays the Dead, the server commands them</label>
        <p class="hint">3 to 8 players. Each player claims a house on their own phone; houses nobody claims become neutral castles.</p>
        <button class="gold-button" type="submit" :disabled="busy || !hostName.trim()">Create game</button>
        <button class="link-button" type="button" @click="view = 'home'">← Back</button>
      </form>
    </section>

    <!-- choose a house -->
    <section v-else-if="view === 'seat' && game" class="lobby">
      <div class="lobby-head">
        <p class="eyebrow">GAME {{ game.code }} · HOSTED BY {{ game.hostName.toUpperCase() }}</p>
        <h1>Choose your house</h1>
      </div>
      <div class="house-grid">
        <button v-for="item in houses" :key="item.id" type="button" class="house-tile"
          :class="{ chosen: chosenHouse === item.id, taken: item.claimed }" :disabled="item.claimed"
          :style="{ '--house': houseColor(item.id), '--house-ink': HOUSES[item.id].ink }" @click="chosenHouse = item.id">
          <svg class="sigil" viewBox="0 0 24 24" aria-hidden="true"><path :d="SIGILS[item.id]" /></svg>
          <strong>{{ item.name }}</strong>
          <small>{{ item.claimed ? `Taken by ${item.playerName}` : `${item.seat} · ${HOUSES[item.id].words}` }}</small>
        </button>
      </div>
      <div v-if="selectedHouseData" class="claim-card">
        <p class="house-rule"><b>{{ HOUSES[selectedHouseData.id].passive }}</b></p>
        <p class="house-rule">Once per game · {{ HOUSES[selectedHouseData.id].power }}</p>
        <div class="claim-fields">
          <label>YOUR NAME<input v-model="playerName" maxlength="30" placeholder="Player name" /></label>
          <label v-if="selectedHouseData.heirs.length > 1">FIRST TO LEAD
            <select v-model="chosenHeir"><option v-for="heir in selectedHouseData.heirs" :key="heir" :value="heir">{{ heir }}</option></select>
          </label>
        </div>
        <button class="gold-button" type="button" :disabled="busy || !playerName.trim()" @click="joinHouse">Claim {{ selectedHouseData.name }}</button>
      </div>
    </section>

    <!-- lobby -->
    <section v-else-if="view === 'lobby' && game" class="lobby">
      <div class="lobby-head">
        <p class="eyebrow">GAME CODE</p>
        <h1 class="code">{{ game.code }}</h1>
        <p class="hint">Players open this app, choose <b>The Iron Throne</b>, and enter the code. {{ claimedCount }} of 8 houses claimed.</p>
        <button class="action-button inline" type="button" @click="copyLink">Copy join link</button>
      </div>
      <div class="house-grid">
        <div v-for="item in houses" :key="item.id" class="house-tile static" :class="{ taken: item.claimed, mine: item.id === game.ownHouseId }"
          :style="{ '--house': houseColor(item.id), '--house-ink': HOUSES[item.id].ink }">
          <svg class="sigil" viewBox="0 0 24 24" aria-hidden="true"><path :d="SIGILS[item.id]" /></svg>
          <strong>{{ item.name }}</strong>
          <small>{{ item.claimed ? `${item.playerName} · ${item.leader}` : item.id === 'dead' && game.computerDead ? 'Open · the server will play them' : 'Open · becomes neutral' }}</small>
        </div>
      </div>
      <div class="lobby-foot">
        <button v-if="isHost" class="gold-button" type="button" :disabled="busy || claimedCount < 3" @click="startGame">
          {{ claimedCount < 3 ? `Waiting for ${3 - claimedCount} more player${3 - claimedCount === 1 ? '' : 's'}` : 'Start the game' }}
        </button>
        <p v-else class="hint">Waiting for the host to start the game.</p>
      </div>
    </section>

    <!-- the game -->
    <section v-else-if="view === 'game' && game" class="game-layout" :class="[`show-${panel}`, { 'realm-open': realmOpen }]">
      <aside class="realm-panel">
        <div class="season-card" :class="game.season.toLowerCase()">
          <p class="eyebrow">ROUND {{ game.round }} OF {{ game.roundLimit }}</p>
          <h2>{{ game.season }}</h2>
          <p>{{ game.season === 'Winter' ? 'Winter is here. The Dead are on the march.' : `Winter comes in ${plural(winterIn, 'round')}.` }}</p>
          <div class="season-track"><i v-for="round in Math.min(game.roundLimit, 12)" :key="round"
            :class="{ past: round < game.round, now: round === game.round, winter: round >= game.winterRound, autumn: round >= game.winterRound - 3 && round < game.winterRound }"></i></div>
        </div>
        <div class="crown-card">
          <p class="eyebrow">THE IRON THRONE</p>
          <strong>{{ crownText }}</strong>
          <small>King's Landing: {{ houseName(kingsLanding?.owner) }} · garrison {{ kingsLanding?.units ?? 0 }}</small>
        </div>
        <div v-if="game.fate" class="fate-mini" role="button" tabindex="0" @click="fateShown = game.fate" @keydown.enter="fateShown = game.fate">
          <p class="eyebrow">FATE · ROUND {{ game.fate.round }}</p>
          <strong>{{ game.fate.title }}</strong>
          <small>{{ game.fate.text }}</small>
        </div>
        <div class="houses-card">
          <p class="eyebrow">THE GREAT HOUSES</p>
          <div v-for="id in game.turnOrder" :key="id" class="house-row" :class="{ active: id === game.activeHouse, fallen: !house(id)?.alive }"
            :style="{ '--house': houseColor(id) }">
            <svg class="sigil small" viewBox="0 0 24 24" aria-hidden="true"><path :d="SIGILS[id]" /></svg>
            <span><strong>{{ house(id)?.leader }}</strong><small>{{ house(id)?.name }}{{ house(id)?.ai ? ' · server' : house(id)?.playerName ? ` · ${house(id).playerName}` : '' }}</small></span>
            <span class="row-stats">
              <template v-if="house(id)?.alive">{{ house(id).units === null ? 'hidden' : `${house(id).units}⚔` }} · {{ house(id).gold }}g · {{ house(id).greatSeats }}♛</template>
              <template v-else>fallen</template>
            </span>
          </div>
          <p v-if="game.pacts.length" class="pacts-line">Pacts: {{ game.pacts.map(p => `${houseName(p.a)} & ${houseName(p.b)}${p.lockedUntil >= game.round ? ' (wed)' : ''}`).join(' · ') }}</p>
          <p v-if="game.wallBreached" class="pacts-line warn">The Wall has been breached.</p>
        </div>
        <div class="events-card">
          <p class="eyebrow">THE MAESTER'S RECORD</p>
          <p v-for="(event, index) in recentEvents" :key="`${index}-${event}`">{{ event }}</p>
        </div>
      </aside>

      <section class="map-panel">
        <div ref="mapStage" class="map-stage" :class="{ moving: movementMode }" @pointerdown="onPointerDown" @pointermove="onPointerMove"
          @pointerup="onPointerUp" @pointercancel="onPointerUp" @wheel.prevent="onWheel" @click="onMapClick">
          <div ref="mapWorld" class="map-world" :class="{ gesturing }" :style="worldStyle">
            <img src="/map-assets/westeros/map.webp" alt="Map of Westeros and Essos" draggable="false" />
            <div v-if="game.snowLine > 17" class="snow-veil" :style="{ height: pct(game.snowLine) }" aria-hidden="true"></div>
            <span v-for="place in labels" :key="place.name" class="place-label" :class="place.kind"
              :style="{ left: pct(place.x), top: pct(place.y) }">{{ place.name }}</span>
            <span v-for="marker in markers" :key="marker.key" class="marker" :class="[marker.kind, { selected: selectedMarker === marker.key,
              active: marker.kind === 'house' && marker.house.id === game.activeHouse, mine: marker.kind === 'house' && marker.house.id === game.ownHouseId }]"
              :style="marker.style">
              <button v-if="marker.kind === 'house'" type="button" class="army-token"
                :style="{ '--house': houseColor(marker.house.id), '--house-ink': HOUSES[marker.house.id].ink }"
                :aria-label="`${marker.house.leader} of ${marker.house.name}`" @click.stop="onMarkerClick(marker)">
                <img v-if="hasPortrait(marker.house.leader)" :src="`/portraits/thrones/${portraitSlug(marker.house.leader)}.png`" alt="" @error="portraitMissing(marker.house.leader)" />
                <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path :d="SIGILS[marker.house.id]" /></svg>
                <b class="unit-badge">{{ marker.house.units }}</b>
                <i v-if="marker.house.dragons?.length" class="dragon-badge" :title="marker.house.dragons.join(', ')">🐉{{ marker.house.dragons.length }}</i>
              </button>
              <button v-else type="button" class="castle-token" :class="{ great: marker.castle.great, capital: marker.castle.kind === 'capital', minor: minorCastle(marker.castle) }"
                :style="{ '--house': houseColor(marker.castle.owner) }"
                :aria-label="`${marker.castle.name}, held by ${houseName(marker.castle.owner)}, garrison ${marker.castle.units}`"
                @click.stop="onMarkerClick(marker)">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M2 19V8h3V5h2.5v3h2V3h1v5h2V5H15v3h3v11h-6v-4a2 2 0 0 0-4 0v4z" /></svg>
                <b class="garrison">{{ marker.castle.units }}</b>
              </button>
              <div v-if="selectedMarker === marker.key" class="marker-info" @click.stop>
                <template v-if="marker.kind === 'house'">
                  <strong>{{ marker.house.leader }}</strong>
                  <span>{{ marker.house.name }} · {{ marker.house.units }} units{{ marker.house.dragons?.length ? ` · ${marker.house.dragons.join(', ')}` : '' }}</span>
                  <span>{{ marker.house.gold }} gold · {{ marker.house.greatSeats }} great seats</span>
                  <span v-if="allied(own?.id, marker.house.id)">Your ally</span>
                </template>
                <template v-else>
                  <strong>{{ marker.castle.name }}</strong>
                  <span>{{ marker.castle.region }}{{ marker.castle.great ? ' · great seat' : '' }}{{ marker.castle.port ? ' · port' : '' }}</span>
                  <span>Held by {{ houseName(marker.castle.owner) }} · garrison {{ marker.castle.units }}</span>
                </template>
                <button v-for="target in attackTargets.filter(t => t.id === (marker.kind === 'house' ? marker.house.id : marker.castle.id))"
                  :key="target.id" type="button" class="gold-button small" @click="act('attack', { targetId: target.id })">⚔ Attack</button>
              </div>
            </span>
            <span v-if="destination" class="destination" :class="{ bad: preview && (preview.error || preview.remaining < 0) }"
              :style="{ left: pct(destination.x), top: pct(destination.y) }"></span>
          </div>
          <div class="zoom-controls" @click.stop>
            <button type="button" aria-label="Zoom in" :disabled="mapZoom >= MAX_ZOOM" @click="zoomBy(0.5)">+</button>
            <span>{{ Math.round(mapZoom * 100) }}%</span>
            <button type="button" aria-label="Zoom out" :disabled="mapZoom <= MIN_ZOOM" @click="zoomBy(-0.5)">−</button>
            <button type="button" @click="resetView">Reset</button>
            <button v-if="own && own.x !== null" type="button" aria-label="Center on my army" title="Center on my army" @click="centerOnOwn">◎</button>
          </div>
          <div v-if="movementMode" class="map-hint">Tap where your army should go</div>
        </div>
        <div v-if="movementMode" class="move-bar">
          <template v-if="preview && !preview.error">
            <span><b>{{ modeLabel[preview.mode] || 'March' }}</b> · {{ preview.miles.toFixed(0) }} miles · {{ preview.movementPoints.toFixed(1) }} of {{ own.movementRemaining.toFixed(1) }} points
              <small>{{ preview.segments.map(s => `${s.terrain} ${s.movementPoints.toFixed(1)}`).join(' · ') }}{{ preview.riverCrossings ? ` · ${plural(preview.riverCrossings, 'river crossing')}` : '' }}</small></span>
          </template>
          <span v-else-if="preview?.error" class="bad-text">{{ preview.error }}</span>
          <span v-else>{{ previewLoading ? 'Measuring the road…' : 'Choose a destination on the map.' }}</span>
          <button class="gold-button" type="button" :disabled="busy || !previewOk" @click="confirmMove">Confirm</button>
          <button class="action-button" type="button" @click="cancelMove">Cancel</button>
        </div>
        <div class="map-foot">
          <span>MAP <b>{{ game.mapWidthMiles.toLocaleString() }} mi</b> wide</span>
          <span>Snow line {{ game.snowLine > 17 ? 'creeping south' : 'beyond the Wall' }}</span>
          <span class="desktop-only">Drag to pan · scroll or pinch to zoom</span>
        </div>
      </section>

      <aside class="house-panel">
        <template v-if="own">
          <div class="house-head" :style="{ '--house': houseColor(own.id), '--house-ink': HOUSES[own.id].ink }">
            <img v-if="hasPortrait(own.leader)" class="portrait" :src="`/portraits/thrones/${portraitSlug(own.leader)}.png`" :alt="own.leader" @error="portraitMissing(own.leader)" />
            <svg v-else class="sigil big" viewBox="0 0 24 24" aria-hidden="true"><path :d="SIGILS[own.id]" /></svg>
            <div>
              <p class="eyebrow">{{ own.name.toUpperCase() }} · {{ HOUSES[own.id].words.toUpperCase() }}</p>
              <h1>{{ own.leader }}</h1>
              <small v-if="own.alive">Heirs: {{ own.heirs.slice(own.heirIndex + 1).join(', ') || 'none — the last of the line' }}</small>
              <small v-else>Your house has fallen.</small>
            </div>
          </div>
          <dl v-if="own.alive" class="stats">
            <div><dt>ARMY</dt><dd>{{ own.units }} units · {{ dice(own) }} dice</dd></div>
            <div><dt>GOLD</dt><dd>{{ own.gold }} <small>+{{ own.income }}/turn</small></dd></div>
            <div><dt>MOVEMENT</dt><dd>{{ own.movementRemaining.toFixed(1) }} / {{ own.movementMax }}</dd></div>
            <div><dt>GREAT SEATS</dt><dd>{{ own.greatSeats }} of 9</dd></div>
            <div v-if="own.dragons.length"><dt>DRAGONS</dt><dd>{{ own.dragons.join(', ') }}</dd></div>
            <div v-if="castleHere"><dt>STANDING AT</dt><dd>{{ castleHere.name }} <small>({{ houseName(castleHere.owner) }}, {{ castleHere.units }})</small></dd></div>
            <div v-if="own.loanDue"><dt>IRON BANK</dt><dd>Repay 8 by round {{ own.loanDue }}</dd></div>
            <div v-if="isOathbreaker"><dt>OATHBREAKER</dt><dd>Until round {{ own.oathbreakerUntil }}</dd></div>
          </dl>

          <div v-if="isMyTurn" class="actions">
            <p class="eyebrow">YOUR TURN</p>
            <button class="gold-button" type="button" :disabled="busy || !canMove || movementMode" @click="startMove">➜ {{ own.dragons.length ? 'Fly' : 'Move' }}</button>
            <button v-for="target in attackTargets" :key="target.id" class="battle-button" type="button" :disabled="busy" @click="act('attack', { targetId: target.id })">
              <strong>⚔ Attack {{ target.label }}</strong><span>your {{ dice(own) }} dice vs {{ target.dice }}{{ alliesHere.length && target.kind === 'army' ? ' (+ their allies)' : '' }}</span>
            </button>
            <p v-if="!attackTargets.length && !own.attacked" class="hint">To fight, move onto an enemy army or castle.</p>
            <div class="spend">
              <button v-if="canRecruit" class="action-button" type="button" :disabled="busy || own.gold < game.recruitCost" @click="act('recruit', { count: 1 })">Raise 1 levy · {{ game.recruitCost }}g</button>
              <button v-if="canRecruit && own.recruitedThisTurn === 0" class="action-button" type="button" :disabled="busy || own.gold < game.recruitCost * 2" @click="act('recruit', { count: 2 })">Raise 2 · {{ game.recruitCost * 2 }}g</button>
              <button v-if="canFortify" class="action-button" type="button" :disabled="busy || own.gold < 2" @click="act('fortify')">Fortify · 2g</button>
              <button v-if="canHire" class="action-button" type="button" :disabled="busy || own.gold < 4" @click="act('sellswords')">Golden Company · 4g</button>
              <button v-if="canLoan" class="action-button" type="button" :disabled="busy" @click="act('loan')">Borrow 6g from the Iron Bank</button>
              <button v-if="canRepay" class="action-button" type="button" :disabled="busy || own.gold < 8" @click="act('repay')">Repay the Iron Bank · 8g</button>
              <button v-if="canBribe" class="action-button" type="button" :disabled="busy || own.gold < (castleHere.bribeCost ?? 99)" @click="act('bribe')">Buy {{ castleHere.name }} · {{ castleHere.bribeCost }}g</button>
            </div>
            <div v-if="!own.powerUsed && own.id !== 'tyrell'" class="power-box">
              <p class="eyebrow">ONCE PER GAME · {{ own.power.toUpperCase() }}</p>
              <p class="hint">{{ HOUSES[own.id].power }}</p>
              <select v-if="powerTargets.length" v-model="powerTarget" aria-label="Power target">
                <option v-for="target in powerTargets" :key="target.id" :value="target.id">{{ target.leader }} ({{ target.name }})</option>
              </select>
              <button class="action-button power" type="button" :disabled="busy || !powerReady" @click="act('power', { targetId: powerTarget || null })">Use {{ own.power }}</button>
            </div>
            <button class="action-button end" type="button" :disabled="busy" @click="act('end-turn')">End turn</button>
          </div>
          <p v-else-if="own.alive && !over" class="waiting">Waiting for {{ active?.leader }} of {{ active?.name }}.</p>

          <div v-if="own.alive && own.id !== 'dead' && !over" class="pacts">
            <p class="eyebrow">PACTS</p>
            <div v-for="offer in incomingOffers" :key="`in-${offer.from}`" class="offer">
              <span><b>{{ houseName(offer.from) }}</b> offers {{ offer.royal ? 'a Royal Wedding' : 'a pact' }}.</span>
              <button class="gold-button small" type="button" :disabled="busy" @click="act('pacts/respond', { houseId: offer.from, accept: true })">Accept</button>
              <button class="action-button small" type="button" :disabled="busy" @click="act('pacts/respond', { houseId: offer.from, accept: false })">Refuse</button>
            </div>
            <div v-for="row in pactRows" :key="row.house.id" class="pact-row" :style="{ '--house': houseColor(row.house.id) }">
              <span><i class="dot"></i>{{ row.house.name }}</span>
              <template v-if="row.pact">
                <small>{{ row.pact.lockedUntil >= game.round ? `wed until round ${row.pact.lockedUntil}` : `allies since round ${row.pact.sinceRound}` }}</small>
                <button v-if="isMyTurn && row.pact.lockedUntil < game.round" class="danger small" type="button" :disabled="busy"
                  @click="act('pacts/break', { houseId: row.house.id })">Betray</button>
              </template>
              <template v-else-if="row.offered"><small>offer sent</small></template>
              <template v-else>
                <button class="action-button small" type="button" :disabled="busy || isOathbreaker || row.house.oathbreakerUntil >= game.round"
                  @click="act('pacts/propose', { houseId: row.house.id })">Offer pact</button>
                <button v-if="own.id === 'tyrell' && !own.powerUsed" class="gold-button small" type="button" :disabled="busy || isOathbreaker"
                  @click="act('pacts/propose', { houseId: row.house.id, royal: true })">Royal Wedding</button>
              </template>
            </div>
            <p class="hint">Allies can't attack each other. Break a pact and attack the same turn for a Red Wedding (+2 per die), but you'll be an oathbreaker for 3 rounds.</p>
          </div>
        </template>
        <template v-else>
          <p class="eyebrow">HOST VIEW</p>
          <h1>The realm</h1>
          <p class="hint">You are watching as the host. Players act from their own devices.</p>
        </template>
      </aside>

      <nav class="tabs" aria-label="Game views">
        <button type="button" :class="{ selected: panel === 'map' }" @click="panel = 'map'">Map</button>
        <button type="button" :class="{ selected: panel === 'house' }" @click="panel = 'house'">{{ own ? 'My house' : 'Host' }}<i v-if="isMyTurn" class="ping"></i></button>
        <button type="button" :class="{ selected: panel === 'realm' }" @click="panel = 'realm'">Realm</button>
      </nav>
    </section>
  </main>
</template>

<style scoped>
.thrones-app {
  --ink: #ece6d8; --muted: #a69f90; --panel: #17161a; --panel-2: #1f1d22; --line: #3a3530;
  --gold: #d8b35f; --crimson: #8e2a25; --ice: #9fd6ea;
  height: 100dvh; min-height: 540px; display: flex; flex-direction: column; overflow: hidden;
  color: var(--ink); background: #0f0e11; font: 13px 'DM Sans', sans-serif;
}
.thrones-app * { box-sizing: border-box; }
.topbar { min-height: 58px; flex: none; display: flex; align-items: center; gap: 18px; padding: 0 18px; border-bottom: 1px solid #3a2f27; background: #141216; z-index: 5; }
.brand { display: flex; align-items: center; gap: 10px; color: inherit; text-decoration: none; }
.brand-mark { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid #8c6b33; background: #1c1712; }
.brand-mark svg { width: 22px; fill: var(--gold); }
.brand strong { display: block; font: 600 15px 'Cinzel', 'Barlow Condensed', serif; letter-spacing: 1.6px; color: #f1e2b8; }
.brand small { display: block; margin-top: 2px; color: #8f877a; font-size: 8px; letter-spacing: 1.5px; }
.topbar-title { flex: 1; padding-left: 18px; border-left: 1px solid #3a3029; color: var(--gold); font-size: 10px; font-weight: 700; letter-spacing: 1.7px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.topbar-actions { display: flex; align-items: center; gap: 10px; }
.status-pill { display: flex; align-items: center; gap: 7px; color: #c9bfa8; font-size: 9px; font-weight: 700; letter-spacing: 1.2px; white-space: nowrap; }
.status-pill i { width: 8px; height: 8px; border-radius: 50%; background: var(--gold); }
.ghost-button { min-height: 30px; padding: 0 10px; border: 1px solid #45392e; border-radius: 6px; background: transparent; color: #d6ccb6; font: 600 11px 'DM Sans', sans-serif; cursor: pointer; }
.ghost-button:hover { border-color: var(--gold); }
.message { display: flex; justify-content: space-between; gap: 10px; padding: 9px 16px; font-size: 12px; z-index: 4; }
.message button { border: 0; background: none; color: inherit; font-size: 18px; cursor: pointer; }
.message.error { background: #3d1f1c; color: #f3c3b6; border-bottom: 1px solid #8e4a3c; }
.message.notice { background: #222a1f; color: #d7dfbd; border-bottom: 1px solid #5d6e4f; }
.victory { padding: 12px 16px; background: linear-gradient(90deg, #3a2a14, #5a3d17, #3a2a14); color: #fbe7a6; font: 600 16px 'Cinzel', serif; text-align: center; border-bottom: 1px solid #b38a3c; }
.eyebrow { margin: 0; color: #b4a88f; font-size: 9px; font-weight: 700; letter-spacing: 1.5px; }
h1, h2 { font-family: 'Cinzel', 'Barlow Condensed', serif; font-weight: 600; color: #f4e9cc; }
.hint { margin: 6px 0; color: var(--muted); font-size: 11px; line-height: 1.5; }
.gold-button { min-height: 42px; padding: 0 14px; border: 1px solid #e4c47e; border-radius: 8px; background: linear-gradient(#e6c477, #a7772f); color: #21170b; font: 700 13px 'DM Sans', sans-serif; cursor: pointer; }
.gold-button:hover:not(:disabled) { filter: brightness(1.08); }
.gold-button:disabled, .action-button:disabled, .danger:disabled { opacity: .45; cursor: not-allowed; }
.action-button { min-height: 38px; padding: 0 12px; border: 1px solid #4a4038; border-radius: 7px; background: #221f24; color: #e6dfcf; font: 600 11px 'DM Sans', sans-serif; cursor: pointer; }
.action-button:hover:not(:disabled) { border-color: #8f7445; }
.action-button.inline { margin-top: 8px; }
.small { min-height: 28px !important; padding: 0 9px !important; font-size: 10px !important; }
.danger { border: 1px solid #a5463a; border-radius: 6px; background: #431c18; color: #ffd2c6; font: 700 10px 'DM Sans', sans-serif; cursor: pointer; }
.link-button { margin-top: 14px; border: 0; background: none; color: var(--gold); font: 600 12px 'DM Sans', sans-serif; cursor: pointer; }
.link-button.muted { display: block; margin: 8px auto 0; color: var(--muted); }
.sigil { width: 26px; height: 26px; fill: none; stroke: var(--house-ink, #f3e6c4); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.sigil.small { width: 22px; height: 22px; flex: none; padding: 3px; border-radius: 5px; background: var(--house); stroke: #fff; }
.sigil.big { width: 54px; height: 54px; flex: none; padding: 8px; border-radius: 10px; background: var(--house); }

/* welcome */
.welcome { flex: 1; display: grid; place-items: center; padding: 22px 16px; overflow-y: auto;
  background: linear-gradient(#0f0e11bb, #0f0e11e6), url('/map-assets/westeros/map.webp') center / cover; }
.welcome-card { width: min(100%, 520px); padding: 30px clamp(20px, 6vw, 64px) 24px; border: 1px solid #8c6b33; border-radius: 16px; background: #17151ae8; box-shadow: 0 24px 70px #000a; text-align: center; }
.welcome-card h1 { margin: 6px 0; font-size: 34px; }
.crown { width: 46px; fill: var(--gold); }
.subtitle { margin: 0 0 18px; color: #c9bea6; font-style: italic; }
.join-form, .host-card { display: grid; gap: 8px; text-align: left; }
.join-form label, .host-card label { color: #b4a88f; font-size: 9px; font-weight: 700; letter-spacing: 1.4px; }
.join-form input, .host-card input:not([type='range']):not([type='checkbox']), .claim-fields input, .claim-fields select, .power-box select {
  min-height: 42px; padding: 0 12px; border: 1px solid #4a4038; border-radius: 8px; background: #0f0e11; color: var(--ink); font: 600 14px 'DM Sans', sans-serif; }
.join-form input { text-align: center; letter-spacing: 6px; text-transform: uppercase; font-size: 18px; }
.host-card input[type='range'] { accent-color: var(--gold); }
.host-card .check { display: flex; align-items: center; gap: 8px; color: #d6ccb6; font-size: 12px; letter-spacing: 0; font-weight: 500; }
.host-card h1 { text-align: center; }
.saved { margin-top: 18px; display: grid; gap: 6px; text-align: left; }
.saved-row { display: flex; gap: 6px; }
.saved-button { flex: 1; display: flex; justify-content: space-between; align-items: center; min-height: 40px; padding: 0 12px; border: 1px solid #3d352d; border-radius: 7px; background: #1e1b20; color: var(--ink); cursor: pointer; font: inherit; }
.saved-button small { color: var(--muted); }
.forget { width: 40px; border: 1px solid #3d352d; border-radius: 7px; background: none; color: var(--muted); cursor: pointer; font-size: 18px; }

/* lobby */
.lobby { flex: 1; overflow-y: auto; padding: 20px clamp(14px, 4vw, 48px) 28px; text-align: center;
  background: linear-gradient(#0f0e11c4, #0f0e11ee), url('/map-assets/westeros/map.webp') center / cover; }
.lobby-head h1 { margin: 6px 0; font-size: 30px; }
.lobby-head h1.code { font: 700 46px 'Barlow Condensed', sans-serif; letter-spacing: 10px; color: #f6deA0; }
.house-grid { max-width: 980px; margin: 18px auto; display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.house-tile { display: grid; justify-items: center; gap: 6px; padding: 16px 12px; border: 1px solid #3b342d; border-top: 4px solid var(--house); border-radius: 10px; background: #19171c; color: var(--ink); text-align: center; cursor: pointer; font: inherit; }
.house-tile .sigil { width: 44px; height: 44px; padding: 7px; border-radius: 50%; background: var(--house); }
.house-tile strong { font: 600 15px 'Cinzel', serif; }
.house-tile small { color: var(--muted); font-size: 10px; line-height: 1.4; }
.house-tile.chosen { border-color: var(--gold); box-shadow: 0 0 0 2px #d8b35f55; }
.house-tile.taken { opacity: .55; cursor: default; }
.house-tile.static { cursor: default; }
.house-tile.static.taken { opacity: 1; }
.house-tile.mine { border-color: var(--gold); }
.claim-card { max-width: 620px; margin: 0 auto; padding: 16px; border: 1px solid #5b4a30; border-radius: 12px; background: #19171ce8; display: grid; gap: 10px; text-align: left; }
.house-rule { margin: 0; color: #d7ccb4; font-size: 12px; line-height: 1.5; }
.claim-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.claim-fields label { display: grid; gap: 5px; color: #b4a88f; font-size: 9px; font-weight: 700; letter-spacing: 1.4px; }
.lobby-foot { max-width: 420px; margin: 0 auto; display: grid; }

/* game layout */
.game-layout { flex: 1; min-height: 0; display: grid; grid-template-columns: 270px minmax(0, 1fr) 320px; }
.realm-panel, .house-panel { min-height: 0; overflow-y: auto; padding: 14px; background: var(--panel); border-right: 1px solid #2c2722; display: flex; flex-direction: column; gap: 10px; }
.house-panel { border-right: 0; border-left: 1px solid #2c2722; }
.season-card, .crown-card, .fate-mini, .houses-card, .events-card { padding: 11px 12px; border: 1px solid #332d27; border-radius: 10px; background: var(--panel-2); }
.season-card h2 { margin: 2px 0; font-size: 22px; }
.season-card p { margin: 0; color: #cdbfa1; font-size: 11px; }
.season-card.winter { border-color: #4b7083; background: linear-gradient(#1c2a33, #1a2026); }
.season-card.winter h2 { color: var(--ice); }
.season-card.autumn h2 { color: #e1a35a; }
.season-track { display: flex; gap: 3px; margin-top: 8px; }
.season-track i { flex: 1; height: 6px; border-radius: 3px; background: #4a5a32; }
.season-track i.autumn { background: #8a5a2a; }
.season-track i.winter { background: #5c8ea5; }
.season-track i.past { opacity: .35; }
.season-track i.now { outline: 2px solid #f1e2b8; outline-offset: 1px; }
.crown-card strong { display: block; margin: 3px 0; font: 600 14px 'Cinzel', serif; color: #f2dc9b; }
.crown-card small, .fate-mini small { color: var(--muted); font-size: 10px; line-height: 1.45; }
.fate-mini { cursor: pointer; border-color: #5a4126; }
.fate-mini strong { display: block; margin: 3px 0; font: 600 13px 'Cinzel', serif; color: #f0c98a; }
.house-row { display: flex; align-items: center; gap: 8px; padding: 6px 4px; border-radius: 6px; }
.house-row span:nth-child(2) { flex: 1; min-width: 0; }
.house-row strong { display: block; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.house-row small { display: block; color: var(--muted); font-size: 9px; }
.house-row.active { background: #2c2619; box-shadow: inset 3px 0 0 var(--gold); }
.house-row.fallen { opacity: .4; }
.row-stats { color: #d8ceb6; font-size: 10px; white-space: nowrap; }
.pacts-line { margin: 6px 0 0; color: #c3b79d; font-size: 10px; line-height: 1.5; }
.pacts-line.warn { color: #9fd6ea; }
.events-card { flex: 1; min-height: 160px; overflow-y: auto; }
.events-card p:not(.eyebrow) { margin: 6px 0; color: #d2c7b0; font-size: 11px; line-height: 1.45; border-bottom: 1px solid #2a2622; padding-bottom: 6px; }

/* map */
.map-panel { min-width: 0; min-height: 0; display: flex; flex-direction: column; background: #10161a; }
.map-stage { position: relative; flex: 1; min-height: 0; overflow: hidden; display: grid; place-items: center; touch-action: none; user-select: none; background: #123447; }
.map-stage.moving { cursor: crosshair; }
.map-world { position: relative; width: min(100%, calc((100dvh - 150px) * 2)); aspect-ratio: 1774 / 887; transform-origin: center; }
.map-world.gesturing { will-change: transform; }
.map-world > img { display: block; width: 100%; height: 100%; pointer-events: none; user-select: none; }
.snow-veil { position: absolute; left: 0; right: 0; top: 0; pointer-events: none;
  background: linear-gradient(to bottom, #f4fbff80, #e8f6ff60 85%, #e8f6ff10); mix-blend-mode: screen; border-bottom: 2px dashed #e4f6ffaa; }
.place-label { position: absolute; z-index: 1; transform: translate(-50%, calc(-50% + 22px)) scale(var(--marker-scale, 1)); padding: 1px 4px; border-radius: 3px;
  background: #0f0e11b0; color: #f4ead0; font: 600 10px 'DM Sans', sans-serif; white-space: nowrap; pointer-events: none; }
.place-label.capital { color: #ffe08a; font: 700 12px 'Cinzel', serif; }
.place-label.seat { color: #fff2cf; font-weight: 700; }
.place-label.landmark, .place-label.dead { font-style: italic; color: #cfe8f2; background: #0f0e1170; }
.marker { position: absolute; z-index: 2; width: 0; height: 0; transform: scale(var(--marker-scale, 1)); }
.marker.house { z-index: 3; }
.marker.mine { z-index: 4; }
.marker.selected { z-index: 8; }
.marker > button { position: absolute; left: 0; top: 0; transform: translate(-50%, -50%); padding: 0; border: 0; cursor: pointer; }
.castle-token { width: 26px; height: 26px; display: grid; place-items: center; border-radius: 6px; background: #1a1714e0; box-shadow: 0 0 0 2px var(--house), 0 2px 6px #000a; }
.castle-token svg { width: 17px; fill: var(--house); }
.castle-token.great { width: 30px; height: 30px; box-shadow: 0 0 0 3px var(--house), 0 0 0 4px #d8b35f, 0 2px 6px #000a; }
.castle-token.capital { width: 34px; height: 34px; }
.castle-token.minor { width: 13px; height: 13px; border-radius: 4px; background: var(--house); box-shadow: 0 0 0 2px #1a1714, 0 1px 4px #000a; }
.castle-token.minor svg, .castle-token.minor .garrison { display: none; }
.castle-token .garrison { position: absolute; right: -7px; bottom: -7px; min-width: 15px; height: 15px; padding: 0 3px; border-radius: 8px; background: #0f0e11; color: #f3e6c4; font: 700 9px/15px 'DM Sans', sans-serif; box-shadow: 0 0 0 1px var(--house); }
.army-token { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%; background: var(--house); box-shadow: 0 0 0 2px #f3e6c4, 0 3px 8px #000c; overflow: visible; }
.army-token img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; }
.army-token svg { width: 24px; height: 24px; fill: none; stroke: var(--house-ink); stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.marker.active .army-token { box-shadow: 0 0 0 2px #f3e6c4, 0 0 0 5px #f2cd6c, 0 3px 8px #000c; animation: pulse 1.6s ease-in-out infinite; }
.marker.mine .army-token { box-shadow: 0 0 0 3px #ffe7a0, 0 3px 8px #000c; }
@keyframes pulse { 50% { transform: translate(-50%, -50%) scale(1.08); } }
.unit-badge { position: absolute; right: -6px; bottom: -6px; min-width: 17px; height: 17px; padding: 0 3px; border-radius: 9px; background: #0f0e11; color: #fff; font: 700 10px/17px 'DM Sans', sans-serif; }
.dragon-badge { position: absolute; left: -10px; top: -10px; font-style: normal; font-size: 11px; background: #0f0e11d0; border-radius: 8px; padding: 0 3px; color: #ffb4a0; }
.marker-info { position: absolute; left: 22px; top: -10px; width: 210px; display: grid; gap: 4px; padding: 10px; border: 1px solid #6b5631; border-radius: 9px; background: #17151af2; box-shadow: 0 10px 30px #000b; font-size: 11px; color: #d8ceb6; }
.marker-info strong { font: 600 14px 'Cinzel', serif; color: #f4e9cc; }
.destination { position: absolute; z-index: 6; width: 20px; height: 20px; border: 3px solid #f2d578; border-radius: 50%; box-shadow: 0 0 0 4px #f2d57855; transform: translate(-50%, -50%) scale(var(--marker-scale, 1)); pointer-events: none; }
.destination.bad { border-color: #e0604c; box-shadow: 0 0 0 4px #e0604c55; }
.zoom-controls { position: absolute; right: 12px; bottom: 12px; z-index: 9; display: flex; align-items: center; gap: 4px; padding: 4px; border: 1px solid #3a3530; border-radius: 8px; background: #17151ae6; }
.zoom-controls button { min-width: 30px; height: 30px; border: 0; border-radius: 5px; background: #2a262b; color: #eee3c9; font: 600 13px 'DM Sans', sans-serif; cursor: pointer; }
.zoom-controls button:disabled { opacity: .4; }
.zoom-controls span { min-width: 44px; text-align: center; color: #c9bfa8; font-size: 11px; }
.map-hint { position: absolute; left: 50%; top: 12px; transform: translateX(-50%); z-index: 9; padding: 7px 12px; border-radius: 20px; background: #17151ae6; border: 1px solid #8c6b33; color: #f1dfaa; font-size: 12px; pointer-events: none; }
.move-bar { display: flex; align-items: center; gap: 10px; padding: 9px 12px; border-top: 1px solid #4a3c26; background: #1b1814; font-size: 12px; }
.move-bar > span { flex: 1; min-width: 0; }
.move-bar small { display: block; color: var(--muted); font-size: 10px; margin-top: 2px; text-transform: capitalize; }
.bad-text { color: #f0a796; }
.map-foot { display: flex; gap: 18px; padding: 7px 12px; border-top: 1px solid #2a2622; background: #141216; color: #9a917f; font-size: 10px; }
.map-foot b { color: #e2d3a8; }

/* house panel */
.house-head { display: flex; gap: 12px; align-items: center; padding-bottom: 10px; border-bottom: 2px solid var(--house); }
.house-head h1 { margin: 3px 0; font-size: 21px; }
.house-head small { color: var(--muted); font-size: 10px; line-height: 1.4; }
.portrait { width: 58px; height: 58px; border-radius: 10px; object-fit: cover; border: 2px solid var(--house); }
.stats { margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.stats div { padding: 7px 9px; border: 1px solid #312b26; border-radius: 8px; background: var(--panel-2); }
.stats dt { color: #9e957f; font-size: 8px; font-weight: 700; letter-spacing: 1.3px; }
.stats dd { margin: 3px 0 0; font-size: 13px; font-weight: 600; }
.stats dd small { color: var(--muted); font-weight: 500; font-size: 10px; }
.actions { display: grid; gap: 7px; padding: 11px; border: 1px solid #6b5631; border-radius: 10px; background: #1d1a15; }
.battle-button { display: grid; gap: 2px; padding: 9px 11px; border: 1px solid #a5463a; border-radius: 8px; background: #3a1714; color: #ffd8cd; text-align: left; cursor: pointer; font: inherit; }
.battle-button span { color: #e3b2a6; font-size: 10px; }
.spend { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.spend:empty { display: none; }
.power-box { display: grid; gap: 6px; padding: 9px; border: 1px dashed #6b5631; border-radius: 8px; }
.power-box .power { border-color: #b08a46; color: #f3d48a; }
.action-button.end { margin-top: 2px; }
.waiting { padding: 12px; border: 1px solid #332d27; border-radius: 10px; color: #cbbfa5; text-align: center; }
.pacts { display: grid; gap: 6px; padding: 11px; border: 1px solid #332d27; border-radius: 10px; background: var(--panel-2); }
.pact-row, .offer { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.pact-row > span { flex: 1; font-size: 12px; }
.pact-row small { color: var(--muted); font-size: 10px; }
.dot { display: inline-block; width: 9px; height: 9px; margin-right: 6px; border-radius: 50%; background: var(--house); }
.offer { padding: 7px; border-radius: 7px; background: #2c2619; }
.offer span { flex: 1; font-size: 11px; }

/* dialogs */
.backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 16px; background: #050407cc; }
.backdrop.light { background: #05040799; }
.sheet { width: min(100%, 760px); max-height: 88dvh; overflow-y: auto; padding: 18px 22px; border: 1px solid #6b5631; border-radius: 14px; background: #17151a; }
.sheet header { display: flex; justify-content: space-between; align-items: center; }
.sheet header h2 { margin: 0; font-size: 20px; }
.sheet header button { border: 0; background: none; color: var(--muted); font-size: 24px; cursor: pointer; }
.sheet h3 { margin: 16px 0 6px; color: var(--gold); font: 700 11px 'DM Sans', sans-serif; letter-spacing: 1.4px; text-transform: uppercase; }
.sheet ul { margin: 0; padding-left: 18px; color: #d8ceb6; font-size: 12px; line-height: 1.6; }
.fate-card { width: min(100%, 380px); padding: 26px 24px; border: 2px solid #b38a3c; border-radius: 16px; text-align: center;
  background: radial-gradient(circle at 50% 0, #3b2a16, #17151a 70%); box-shadow: 0 30px 80px #000; animation: reveal .6s ease-out; }
.fate-card h2 { margin: 8px 0; font-size: 26px; color: #f6d68e; }
.fate-card p:not(.eyebrow) { color: #e2d6bb; line-height: 1.55; margin-bottom: 18px; }
@keyframes reveal { from { transform: rotateY(90deg) scale(.8); opacity: 0; } to { transform: none; opacity: 1; } }
.battle-card { width: min(100%, 520px); padding: 18px; border: 1px solid #a5463a; border-radius: 14px; background: #1a1416; display: grid; gap: 12px; text-align: center; }
.battle-sides { display: grid; grid-template-columns: 1fr auto 1fr; gap: 10px; align-items: start; }
.battle-sides strong { display: block; font: 600 13px 'Cinzel', serif; }
.battle-sides small { display: block; color: var(--muted); font-size: 10px; margin-bottom: 6px; }
.vs { align-self: center; color: var(--gold); font: 700 14px 'Cinzel', serif; }
.dice { display: flex; flex-wrap: wrap; justify-content: center; gap: 5px; }
.dice i { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 6px; background: #f1e6cc; color: #2a1c12; font: 700 15px 'DM Sans', sans-serif; font-style: normal; box-shadow: inset 0 -3px 0 #c9b58c; }
.dice i.defender { background: #c9d6dd; box-shadow: inset 0 -3px 0 #8fa3ad; }
.dice i.win { outline: 2px solid #f2cd6c; }
.total { display: block; margin-top: 6px; color: #f3dc9c; font-weight: 700; }
.battle-result { margin: 0; color: #f4e3b5; font: 600 14px 'Cinzel', serif; }

/* tabs (phones) */
.tabs { display: none; }
.ping { display: inline-block; width: 7px; height: 7px; margin-left: 6px; border-radius: 50%; background: #f2cd6c; vertical-align: middle; }

.realm-toggle { display: none; }
/* ===================================================================================
 * Cinematic theme (shared tokens in src/theme.css), in crimson and steel. Layout lives
 * above; this layer sets surfaces, type, color and light. Phone layout still wins.
 * =================================================================================== */
.thrones-app {
  --ink: var(--cine-ink);
  --muted: var(--cine-muted);
  --panel: var(--cine-panel);
  --panel-2: var(--cine-panel-2);
  --line: var(--cine-line);
  --gold: var(--cine-gold);
  --crimson: #b3342b;
  --crimson-deep: #3a0d0c;
  --steel: #a9b8c4;
  background: radial-gradient(120% 80% at 50% 0%, #160d0e 0%, var(--cine-bg) 45%, var(--cine-void) 100%);
  font-family: var(--cine-body);
}

/* ---------- Header ---------- */
.topbar { border-bottom: 1px solid rgba(179,52,43,.35); background: linear-gradient(180deg, #120c0e, #09080b); box-shadow: 0 1px 0 rgba(0,0,0,.6), 0 10px 30px rgba(0,0,0,.45); }
.brand-mark { border: 1px solid var(--cine-line-strong); border-radius: 50%; background: radial-gradient(circle, #2b1512, #0c0a0d 70%); box-shadow: 0 0 18px rgba(179,52,43,.35), inset 0 0 10px rgba(217,178,106,.15); }
.brand-mark svg { fill: var(--cine-gold-hi); }
.brand strong { font: 700 15px var(--cine-title); letter-spacing: 3px; color: #f3e1b6; }
.brand small { color: var(--cine-dim); letter-spacing: 2.6px; }
.topbar-title { border-left-color: var(--cine-line); color: var(--cine-gold); font: 600 11px var(--cine-title); letter-spacing: 2.6px; }
.status-pill { color: var(--cine-muted); }
.status-pill i { background: var(--crimson); box-shadow: 0 0 10px rgba(179,52,43,.7); }
.ghost-button { border-color: var(--cine-line-strong); background: rgba(217,178,106,.04); color: #e1d3b0; }
.ghost-button:hover { border-color: var(--cine-gold); color: var(--cine-gold-hi); box-shadow: 0 0 12px var(--cine-glow); }

/* ---------- Messages and type ---------- */
.message.error { border-bottom-color: #7a2f22; background: linear-gradient(90deg, #2a0f0b, #1a0b09); }
.message.notice { border-bottom-color: var(--cine-line-strong); background: linear-gradient(90deg, #1d180f, #120f0b); color: #eadcb2; }
.victory { background: linear-gradient(90deg, #120607, #4a1a14 50%, #120607); color: var(--cine-gold-hi); font: 700 18px var(--cine-title); letter-spacing: 3px; text-shadow: 0 0 18px var(--cine-glow); border-bottom-color: var(--cine-gold-lo); }
.eyebrow { color: #b39a6a; letter-spacing: 2.4px; }
h1, h2 { font-family: var(--cine-title); font-weight: 700; letter-spacing: 1.2px; color: #f4e6c4; }
.hint { color: var(--cine-muted); }
.subtitle { color: #cbbd9c; font: italic 500 19px var(--cine-serif); }

/* ---------- Buttons and inputs ---------- */
.gold-button {
  border: 1px solid #f0d390;
  background: linear-gradient(180deg, #f3d792 0%, #d3a75a 48%, #a7772f 100%);
  color: #1c1307;
  font: 700 12px var(--cine-title);
  letter-spacing: 1.6px;
  text-transform: uppercase;
  box-shadow: inset 0 1px 0 rgba(255,250,225,.65), 0 6px 18px rgba(0,0,0,.45);
  transition: box-shadow .2s, filter .2s;
}
.gold-button:hover:not(:disabled) { filter: brightness(1.06); box-shadow: inset 0 1px 0 rgba(255,250,225,.7), 0 0 22px var(--cine-glow); }
.action-button { border: 1px solid var(--cine-line-strong); background: linear-gradient(180deg, #1d191f, #141117); color: #eadfc6; box-shadow: var(--cine-inset); }
.action-button:hover:not(:disabled) { border-color: var(--cine-gold); box-shadow: var(--cine-inset), 0 0 14px rgba(217,178,106,.18); }
.danger { border-color: #c0443a; background: linear-gradient(180deg, #4e1512, #2a0a09); color: #ffd2c6; }
.danger:hover:not(:disabled) { box-shadow: 0 0 16px rgba(179,52,43,.45); }
.link-button { color: var(--cine-gold); }
.link-button:hover { color: var(--cine-gold-hi); text-shadow: 0 0 10px var(--cine-glow); }
.join-form input, .host-card input:not([type='range']):not([type='checkbox']), .claim-fields input, .claim-fields select, .power-box select {
  border: 1px solid var(--cine-line-strong);
  background: rgba(6,5,8,.75);
}
.join-form input:focus, .host-card input:focus, .claim-fields input:focus, .claim-fields select:focus { outline: none; border-color: var(--cine-gold); box-shadow: 0 0 0 3px rgba(217,178,106,.14); }
.join-form input { font-family: var(--cine-title); }

/* ---------- Welcome and lobby ---------- */
.welcome {
  background:
    radial-gradient(60% 55% at 50% 45%, rgba(11,10,13,.3), rgba(6,5,8,.93) 75%),
    linear-gradient(180deg, rgba(6,5,8,.5), rgba(6,5,8,.2) 40%, rgba(40,6,6,.55) 100%),
    url('/map-assets/westeros/map.webp') center / cover;
}
.welcome-card, .claim-card {
  position: relative;
  border: 1px solid var(--cine-line-strong);
  border-radius: 4px;
  background: linear-gradient(180deg, rgba(22,15,17,.93), rgba(10,9,12,.96));
  box-shadow: 0 30px 90px rgba(0,0,0,.75), inset 0 0 60px rgba(179,52,43,.06);
  animation: cine-rise .6s ease-out both;
}
.welcome-card::before { content: ''; position: absolute; inset: 7px; border: 1px solid var(--cine-line); pointer-events: none; }
.welcome-card h1 {
  font: 800 clamp(30px, 6vw, 42px)/1.05 var(--cine-title);
  letter-spacing: 3px;
  background: linear-gradient(180deg, #fff1c4 0%, #e3bd70 55%, #9c7230 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  filter: drop-shadow(0 2px 14px rgba(217,178,106,.25));
}
.crown { fill: var(--cine-gold-hi); filter: drop-shadow(0 0 14px rgba(244,220,156,.45)); }
.saved-button, .forget { border-color: var(--cine-line); background: rgba(20,17,22,.85); }
.saved-button:hover, .forget:hover { border-color: var(--cine-line-strong); }
.lobby {
  background:
    radial-gradient(70% 60% at 50% 30%, rgba(11,10,13,.55), rgba(6,5,8,.95) 80%),
    url('/map-assets/westeros/map.webp') center / cover;
}
.lobby-head h1.code { font: 700 50px var(--cine-title); letter-spacing: 12px; color: var(--cine-gold-hi); text-shadow: 0 0 26px var(--cine-glow); }
.house-tile {
  border: 1px solid var(--cine-line);
  border-top: 3px solid var(--house);
  border-radius: 4px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--house) 14%, #121015), #0c0b0e);
  box-shadow: var(--cine-inset), 0 10px 24px rgba(0,0,0,.4);
  transition: transform .15s, box-shadow .2s, border-color .2s;
}
.house-tile:hover:not(.taken):not(.static) { transform: translateY(-2px); box-shadow: var(--cine-inset), 0 14px 30px rgba(0,0,0,.5), 0 0 18px color-mix(in srgb, var(--house) 35%, transparent); }
.house-tile .sigil { box-shadow: 0 0 0 2px rgba(0,0,0,.6), 0 0 16px color-mix(in srgb, var(--house) 45%, transparent); }
.house-tile strong { font: 700 15px var(--cine-title); letter-spacing: 1.4px; color: #f3e4be; }
.house-tile.chosen, .house-tile.mine { border-color: var(--cine-gold); box-shadow: 0 0 0 1px var(--cine-gold), 0 0 22px var(--cine-glow); }
.house-rule { color: #ddd0b4; font-family: var(--cine-serif); font-size: 15px; line-height: 1.35; }

/* ---------- Game panels ---------- */
.realm-panel, .house-panel { border-color: var(--cine-line); background: linear-gradient(180deg, #121015, #0b0a0d); }
.season-card, .crown-card, .fate-mini, .houses-card, .events-card, .pacts, .stats div, .waiting {
  border: 1px solid var(--cine-line);
  border-radius: 6px;
  background: linear-gradient(180deg, rgba(30,24,28,.7), rgba(12,11,14,.9));
  box-shadow: var(--cine-inset);
}
.season-card h2 { letter-spacing: 3px; }
.season-card p, .events-card p:not(.eyebrow) { font-family: var(--cine-serif); font-size: 14px; line-height: 1.3; color: #ddd0b4; }
.season-card.winter { border-color: rgba(159,214,234,.45); background: linear-gradient(180deg, rgba(30,50,62,.75), rgba(12,14,18,.95)); box-shadow: inset 0 0 40px rgba(159,214,234,.08); }
.season-card.winter h2 { text-shadow: 0 0 16px rgba(159,214,234,.55); }
.crown-card { border-color: var(--cine-line-strong); }
.crown-card strong { color: var(--cine-gold-hi); text-shadow: 0 0 12px var(--cine-glow); }
.fate-mini { border-color: rgba(224,112,60,.35); }
.fate-mini:hover { box-shadow: 0 0 18px var(--cine-ember-glow); }
.house-row.active { background: linear-gradient(90deg, rgba(217,178,106,.18), transparent); box-shadow: inset 2px 0 0 var(--cine-gold); }
.house-row strong { font-family: var(--cine-title); letter-spacing: .6px; }
.events-card p:not(.eyebrow) { border-bottom-color: rgba(217,178,106,.07); }
.house-head { border-bottom-color: var(--house); }
.house-head h1 { color: var(--cine-gold-hi); letter-spacing: 1.6px; }
.portrait { box-shadow: 0 0 0 3px rgba(0,0,0,.6), 0 0 22px color-mix(in srgb, var(--house) 45%, transparent); }
.stats dt { color: #a8916a; letter-spacing: 1.8px; }
.actions { border: 1px solid var(--cine-line-strong); border-radius: 6px; background: linear-gradient(180deg, rgba(42,34,22,.45), rgba(12,11,14,.92)); }
.battle-button { border-color: #c0443a; border-radius: 6px; background: linear-gradient(180deg, #4e1512, #2a0a09); }
.battle-button:hover { box-shadow: 0 0 18px rgba(179,52,43,.45); }
.power-box { border-color: var(--cine-line-strong); }
.offer { background: linear-gradient(90deg, rgba(217,178,106,.14), transparent); }

/* ---------- Map ---------- */
.map-panel { background: var(--cine-void); }
.map-stage { background: #071a24; }
.map-stage::after { content: ''; position: absolute; inset: 0; z-index: 7; pointer-events: none; box-shadow: inset 0 0 140px 40px rgba(4,3,5,.78), inset 0 0 0 1px rgba(217,178,106,.08); }
.place-label { background: rgba(8,7,10,.72); box-shadow: 0 0 0 1px rgba(217,178,106,.1); }
.place-label.capital { font-family: var(--cine-title); letter-spacing: 1.4px; color: var(--cine-gold-hi); text-shadow: 0 0 10px rgba(0,0,0,.9); }
.marker-info { border: 1px solid var(--cine-line-strong); border-radius: 4px; background: var(--cine-glass); box-shadow: var(--cine-shadow); backdrop-filter: blur(6px); }
.marker-info strong { color: var(--cine-gold-hi); letter-spacing: 1px; }
.army-token { box-shadow: 0 0 0 2px var(--cine-gold), 0 0 0 3px rgba(0,0,0,.7), 0 4px 12px rgba(0,0,0,.8), 0 0 16px color-mix(in srgb, var(--house) 50%, transparent); }
.marker.active .army-token { box-shadow: 0 0 0 2px var(--cine-gold-hi), 0 0 0 5px rgba(244,220,156,.35), 0 0 26px rgba(244,220,156,.55); }
.marker.mine .army-token { box-shadow: 0 0 0 3px var(--cine-gold-hi), 0 0 20px rgba(244,220,156,.45), 0 4px 12px rgba(0,0,0,.8); }
.castle-token { background: rgba(10,9,12,.9); }
.castle-token.great { box-shadow: 0 0 0 2px var(--house), 0 0 0 3px var(--cine-gold), 0 0 14px rgba(217,178,106,.35), 0 2px 6px #000a; }
.destination { border-color: var(--cine-gold-hi); box-shadow: 0 0 0 4px rgba(244,220,156,.25), 0 0 18px rgba(244,220,156,.6); }
.zoom-controls { z-index: 9; border: 1px solid var(--cine-line-strong); border-radius: 6px; background: var(--cine-glass); box-shadow: var(--cine-shadow); backdrop-filter: blur(6px); }
.zoom-controls button { border: 1px solid var(--cine-line); background: linear-gradient(#1d191f, #131016); color: #eadfc6; }
.zoom-controls span { color: var(--cine-gold); font-family: var(--cine-title); }
.map-hint { z-index: 9; border-color: var(--cine-line-strong); background: var(--cine-glass); color: var(--cine-gold-hi); font-family: var(--cine-title); letter-spacing: 1px; }
.move-bar { border-top-color: var(--cine-line-strong); background: linear-gradient(90deg, #1b150c, #0e0c0f); }
.map-foot { border-top-color: var(--cine-line); background: #08070a; color: var(--cine-dim); }
.map-foot b { color: var(--cine-gold); }

/* ---------- Dialogs ---------- */
.backdrop { background: radial-gradient(circle at 50% 45%, rgba(30,6,6,.5), rgba(3,2,4,.88)); backdrop-filter: blur(3px); }
.backdrop.light { background: radial-gradient(circle at 50% 45%, rgba(30,6,6,.35), rgba(3,2,4,.7)); }
.sheet, .battle-card {
  border: 1px solid var(--cine-line-strong);
  border-radius: 6px;
  background: linear-gradient(180deg, #17121a, #0c0a0e);
  box-shadow: 0 40px 120px rgba(0,0,0,.85), inset 0 0 80px rgba(179,52,43,.05);
  animation: cine-rise .35s ease-out both;
}
.sheet header h2 { color: var(--cine-gold-hi); letter-spacing: 3px; }
.sheet h3 { color: var(--cine-gold); font-family: var(--cine-title); letter-spacing: 3px; border-bottom: 1px solid var(--cine-line); padding-bottom: 4px; }
.sheet ul { color: #dcd1b9; }
.fate-card { border: 1px solid var(--cine-gold); border-radius: 6px; background: radial-gradient(circle at 50% 0, #4a2a12, #120d10 72%); box-shadow: 0 40px 120px #000, 0 0 60px rgba(224,112,60,.2), inset 0 0 0 6px rgba(0,0,0,.35), inset 0 0 0 7px var(--cine-line-strong); }
.fate-card h2 { color: var(--cine-gold-hi); letter-spacing: 2px; text-shadow: 0 0 20px var(--cine-glow); }
.fate-card p:not(.eyebrow) { font-family: var(--cine-serif); font-size: 17px; line-height: 1.35; }
.battle-card { box-shadow: 0 40px 120px rgba(0,0,0,.85), 0 0 60px rgba(179,52,43,.18); }
.vs { color: var(--crimson); text-shadow: 0 0 14px rgba(179,52,43,.6); }
.dice i { background: linear-gradient(#fdf4dc, #d8c497); box-shadow: inset 0 -3px 0 rgba(0,0,0,.2), 0 2px 6px rgba(0,0,0,.7); }
.dice i.defender { background: linear-gradient(#dfe7ec, #93a6b2); }
.dice i.win { outline: 2px solid var(--cine-gold-hi); box-shadow: 0 0 18px rgba(244,220,156,.55); }
.total { color: var(--cine-gold-hi); font-family: var(--cine-title); }
.battle-result { color: #f0e2c0; font: italic 500 18px var(--cine-serif); }
@media (max-width: 1280px) and (min-width: 861px) {
  .game-layout { position: relative; grid-template-columns: minmax(0, 1fr) 300px; }
  .realm-panel { position: absolute; z-index: 20; top: 0; bottom: 0; left: 0; width: 290px; transform: translateX(-100%);
    transition: transform .2s ease; box-shadow: 10px 0 30px #000a; }
  .game-layout.realm-open .realm-panel { transform: none; }
  .realm-toggle { display: inline-flex; }
}
@media (max-width: 860px) {
  .topbar { gap: 10px; padding: 0 12px; }
  .topbar-title, .brand small, .desktop-only, .exit-button { display: none; }
  .brand strong { font-size: 12px; white-space: nowrap; }
  .brand-mark { width: 30px; height: 30px; }
  .status-pill { font-size: 8px; }
  .game-layout { display: flex; flex-direction: column; }
  .realm-panel, .house-panel, .map-panel { display: none; flex: 1; border: 0; }
  .show-map .map-panel, .show-house .house-panel, .show-realm .realm-panel { display: flex; }
  .map-world { width: auto; height: 100%; max-width: none; }
  .map-stage { display: block; }
  .map-world { position: absolute; top: 0; left: 50%; translate: -50% 0; }
  .tabs { display: flex; flex: none; border-top: 1px solid #3a2f27; background: #141216; }
  .tabs button { flex: 1; min-height: 50px; border: 0; background: none; color: #b9ae96; font: 600 12px 'DM Sans', sans-serif; cursor: pointer; }
  .tabs button.selected { color: #f2d58b; box-shadow: inset 0 3px 0 var(--gold); }
  .claim-fields { grid-template-columns: 1fr; }
  .move-bar { flex-wrap: wrap; }
}
/* Cinematic theme on phones. */
@media (max-width: 860px) {
  .tabs { border-top: 1px solid var(--cine-line-strong); background: #0a090c; box-shadow: 0 -10px 30px rgba(0,0,0,.6); }
  .tabs button { color: var(--cine-muted); font-family: var(--cine-title); letter-spacing: 1px; }
  .tabs button.selected { color: var(--cine-gold-hi); box-shadow: inset 0 2px 0 var(--cine-gold); }
  .map-stage::after { box-shadow: inset 0 0 60px 14px rgba(4,3,5,.7); }
}
</style>
