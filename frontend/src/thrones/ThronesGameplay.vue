<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { HOUSES, houseColor, houseInk, isDead, sigil, PLACE_KINDS_WITH_LABELS } from './houses.js'
import TableTalk from '../TableTalk.vue'

const props = defineProps({ initialCode: { type: String, default: '' } })
const emit = defineEmits(['exit'])

const API = '/api/thrones'
const SAVED_KEY = 'thrones-saved-seats'
const GUIDE_KEY = 'thrones-guide-seen'
const TILE_MANIFEST = '/map-assets/westeros/tiles/tiles.json'
const BASE_MAP_WIDTH = 4096
const MIN_ZOOM = 1

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
// Table talk for the game, chosen by the host: off, text, voice or both.
const chatMode = ref('text')
const CHAT_MODES = [
  { id: 'off', label: 'Off', hint: 'No table talk' },
  { id: 'text', label: 'Text', hint: 'Live messages' },
  { id: 'voice', label: 'Voice', hint: 'Talk out loud' },
  { id: 'both', label: 'Text + voice', hint: 'Both' },
]
const chosenHouse = ref('')
const chosenHeir = ref('')
const savedSeats = ref([])
const rulesOpen = ref(false)
const guideOpen = ref(false)
const guideStep = ref(0)
const housesOpen = ref(false)
const longNightShown = ref(false)
const destination = ref(null)
const preview = ref(null)
const previewLoading = ref(false)
const powerTarget = ref('')
const selectedMarker = ref('')
const fateShown = ref(null)
const battleShown = ref(null)
const battlePhase = ref('done')
const battleFaces = ref({ attacker: [], defender: [] })
const toasts = ref([])
let pollTimer = null
let previewRequest = 0
let seenFate = 0
let seenBattle = 0
let lastEvent = null
let toastId = 0

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
  if (isDead(id)) return 'The Dead'
  return house(id)?.name || id
}
function coalitionOf(id) {
  return (game.value?.coalitions || []).find(coalition => coalition.members.includes(id)) || null
}
function allied(a, b) {
  if (!a || !b || a === b) return false
  if (isDead(a) && isDead(b)) return true
  return Boolean(coalitionOf(a)?.members.includes(b))
}
function friendly(a, b) {
  return a === b || allied(a, b)
}
/** A house and its coalition partners: the side that wins together. */
function sideIds(id) {
  const coalition = coalitionOf(id)
  return coalition ? coalition.members : [id]
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
function houseStyle(id) {
  return { '--house': houseColor(id), '--house-ink': houseInk(id) }
}

const castles = computed(() => game.value?.castles || [])
const castleHere = computed(() => own.value && own.value.x !== null
  ? castles.value.find(castle => same(castle.x, castle.y, own.value.x, own.value.y)) || null : null)
const enemiesHere = computed(() => !own.value || own.value.x === null ? [] : houses.value.filter(other =>
  other.alive && other.id !== own.value.id && other.x !== null && !allied(own.value.id, other.id)
  && same(other.x, other.y, own.value.x, own.value.y)))

function dice(item) {
  if (!item || item.units === null || item.units === undefined) return null
  let count = Math.max(1, item.units) + 2 * (item.dragons?.length || 0)
  if (item.id === 'dead' && game.value?.iceDragon) count += 2
  return count
}

// The chance to win a battle, from a quick simulation of the dice (ties go to the defender).
const oddsCache = new Map()
function winChance(attackDice, defenseDice, attackMod = 0, defenseMod = 0) {
  const key = `${attackDice}:${defenseDice}:${attackMod}:${defenseMod}`
  if (oddsCache.has(key)) return oddsCache.get(key)
  let wins = 0
  const trials = 1500
  for (let trial = 0; trial < trials; trial += 1) {
    let attack = 0
    let defense = 0
    for (let die = 0; die < attackDice; die += 1) attack += 1 + Math.floor(Math.random() * 6) + attackMod
    for (let die = 0; die < defenseDice; die += 1) defense += 1 + Math.floor(Math.random() * 6) + defenseMod
    if (attack > defense) wins += 1
  }
  const chance = Math.round(wins / trials * 100)
  oddsCache.set(key, chance)
  return chance
}

const attackTargets = computed(() => {
  if (!isMyTurn.value || !own.value || own.value.attacksLeft <= 0) return []
  const myMod = (own.value.sworn ? 1 : 0) + (own.value.screamers ? 1 : 0)
  if (enemiesHere.value.length) {
    return enemiesHere.value.map(enemy => {
      const helpers = houses.value.filter(other => other.alive && other.id !== enemy.id && other.x !== null
        && allied(enemy.id, other.id) && same(other.x, other.y, enemy.x, enemy.y))
      const theirDice = (dice(enemy) || 1) + helpers.reduce((sum, helper) => sum + (helper.units || 0), 0)
      return { id: enemy.id, label: enemy.leader, house: enemy.id, dice: theirDice, kind: 'army',
        odds: winChance(dice(own.value), theirDice, myMod, enemy.sworn ? 1 : 0) }
    })
  }
  const castle = castleHere.value
  if (castle && castle.owner !== own.value.id && !allied(own.value.id, castle.owner)) {
    const theirDice = Math.max(1, castle.units)
    return [{ id: castle.id, label: castle.name, house: castle.owner, dice: theirDice, kind: 'castle',
      odds: winChance(dice(own.value), theirDice, myMod, 0) }]
  }
  return []
})

const canMove = computed(() => isMyTurn.value && !own.value.moved && !own.value.attacked && own.value.movementRemaining > 0.01)
const canRecruit = computed(() => isMyTurn.value && castleHere.value?.owner === own.value.id && own.value.recruitedThisTurn < 2)

/** Where the player is in their turn: move, fight, raise troops, or nothing left but to end it. */
const step = computed(() => {
  if (!isMyTurn.value) return ''
  if (attackTargets.value.length) return 'fight'
  if (canMove.value) return 'move'
  if (canRecruit.value && own.value.gold >= game.value.recruitCost) return 'troops'
  return 'done'
})
const steps = computed(() => {
  const me = own.value
  if (!me) return []
  return [
    { id: 'move', label: 'Move', done: me.moved || me.attacked },
    { id: 'fight', label: 'Fight', done: me.attacked },
    { id: 'troops', label: 'Troops', done: me.recruitedThisTurn >= 2 },
    { id: 'done', label: 'End turn', done: false },
  ]
})
const stepHint = computed(() => {
  switch (step.value) {
    case 'fight': return 'An enemy is where you stand. Attack, or move on first if you still can.'
    case 'move': return canRecruit.value
      ? 'Tap anywhere on the map to march there, or raise troops here first.'
      : 'Tap anywhere on the map to march there. Tap a castle or army to go straight to it.'
    case 'troops': return "You're in your own castle. Raise troops before ending your turn."
    default: return 'Nothing left to do this turn.'
  }
})

const powerTargets = computed(() => {
  if (!own.value) return []
  if (own.value.id === 'baratheon') {
    return houses.value.filter(item => item.alive && item.id !== 'baratheon' && !isDead(item.id)
      && item.heirIndex < item.heirs.length - 1)
  }
  if (own.value.id === 'martell') return enemiesHere.value.filter(item => !isDead(item.id))
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
    default: return false
  }
})
watch(powerTargets, targets => {
  if (!targets.some(target => target.id === powerTarget.value)) powerTarget.value = targets[0]?.id || ''
})

// ------------------------------------------------------------------ coalitions and the goal
const invites = computed(() => game.value?.invites || [])
const incomingInvites = computed(() => invites.value.filter(invite => invite.to === own.value?.id))
const isOathbreaker = computed(() => Boolean(own.value) && own.value.oathbreakerUntil >= (game.value?.round || 0))
const mySide = computed(() => own.value ? sideIds(own.value.id) : [])
const myCoalition = computed(() => own.value ? coalitionOf(own.value.id) : null)
function castlesOf(ids) {
  return castles.value.filter(castle => ids.includes(castle.owner)).length
}
const deadCastles = computed(() => castles.value.filter(castle => isDead(castle.owner)).length)
const totalCastles = computed(() => game.value?.totalCastles || castles.value.length || 1)

/** Every side in the game: each coalition, each house standing alone, and the Dead. */
const sides = computed(() => {
  const list = []
  const seen = new Set()
  for (const coalition of game.value?.coalitions || []) {
    const members = coalition.members.map(house).filter(item => item?.alive)
    members.forEach(item => seen.add(item.id))
    list.push({ key: coalition.id, coalition: true, members, castles: castlesOf(coalition.members),
      forged: members.filter(item => item.sworn).length, title: 'Coalition' })
  }
  for (const item of houses.value) {
    if (!item.alive || seen.has(item.id) || isDead(item.id)) continue
    list.push({ key: item.id, coalition: false, members: [item], castles: castlesOf([item.id]), forged: 0, title: item.name })
  }
  const dead = houses.value.filter(item => isDead(item.id) && item.alive)
  if (dead.length) list.push({ key: 'dead', dead: true, members: dead, castles: deadCastles.value, forged: 0, title: 'The Dead' })
  return list.sort((a, b) => b.castles - a.castles)
})
const myCastles = computed(() => own.value ? castlesOf(mySide.value) : Math.max(0, ...sides.value.filter(side => !side.dead).map(side => side.castles)))
const goalLabel = computed(() => !own.value ? 'Leading side' : myCoalition.value ? 'Your coalition' : 'Your house')

const winterIn = computed(() => game.value ? Math.max(0, game.value.winterRound - game.value.round) : 0)
const recentEvents = computed(() => (game.value?.events || []).slice(-60).reverse())

// ------------------------------------------------------------------ map
const mapZoom = ref(1)
const mapPan = ref({ x: 0, y: 0 })
const mapStage = ref(null)
const mapWorld = ref(null)
const gesturing = ref(false)
const tileLevels = ref([])
const viewportTick = ref(0)
const pointers = new Map()
let drag = null
let pinch = null
let suppressClick = false

/** Zooming stops where one pixel of the sharpest map fills about one pixel of the screen. */
const maxZoom = computed(() => {
  void viewportTick.value
  const sharpest = Math.max(BASE_MAP_WIDTH, ...tileLevels.value.map(level => level.width))
  const width = mapWorld.value?.offsetWidth || 1200
  return Math.max(3, Math.min(16, sharpest / (width * (window.devicePixelRatio || 1))))
})

const worldStyle = computed(() => ({
  transform: `translate(${mapPan.value.x}px, ${mapPan.value.y}px) scale(${mapZoom.value})`,
  '--marker-scale': 1 / mapZoom.value,
}))

/** Sharp tiles for the part of the map in view, from the tile level that matches the zoom. */
const hdTiles = computed(() => {
  void viewportTick.value
  const stage = mapStage.value
  const world = mapWorld.value
  if (!stage || !world || !world.offsetWidth || !tileLevels.value.length) return []
  const zoom = mapZoom.value
  const shown = world.offsetWidth * zoom * (window.devicePixelRatio || 1)
  if (shown <= BASE_MAP_WIDTH * 1.1) return []
  const level = tileLevels.value.find(item => item.width >= shown * 0.9) || tileLevels.value[tileLevels.value.length - 1]
  const width = world.offsetWidth
  const height = world.offsetHeight
  const pan = mapPan.value
  const view = {
    left: (width / 2 + (-stage.clientWidth / 2 - pan.x) / zoom) / width,
    right: (width / 2 + (stage.clientWidth / 2 - pan.x) / zoom) / width,
    top: (height / 2 + (-stage.clientHeight / 2 - pan.y) / zoom) / height,
    bottom: (height / 2 + (stage.clientHeight / 2 - pan.y) / zoom) / height,
  }
  const tileWidth = level.width / level.cols
  const tileHeight = level.height / level.rows
  const tiles = []
  for (let row = 0; row < level.rows; row += 1) {
    for (let col = 0; col < level.cols; col += 1) {
      if ((col + 1.25) / level.cols < view.left || (col - 0.25) / level.cols > view.right
        || (row + 1.25) / level.rows < view.top || (row - 0.25) / level.rows > view.bottom) continue
      const x0 = Math.max(0, col * tileWidth - level.bleed)
      const y0 = Math.max(0, row * tileHeight - level.bleed)
      const x1 = Math.min(level.width, (col + 1) * tileWidth + level.bleed)
      const y1 = Math.min(level.height, (row + 1) * tileHeight + level.bleed)
      tiles.push({
        key: `${level.level}-${row}-${col}`,
        src: `/map-assets/westeros/tiles/${level.level}/${row}-${col}.webp`,
        style: { left: pct(x0 / level.width * 100), top: pct(y0 / level.height * 100),
          width: pct((x1 - x0) / level.width * 100), height: pct((y1 - y0) / level.height * 100) },
      })
    }
  }
  return tiles
})

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
  return Math.max(MIN_ZOOM, Math.min(maxZoom.value, Number(zoom.toFixed(3))))
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
function zoomBy(factor) {
  zoomAround(mapZoom.value * factor, { x: 0, y: 0 })
}
function resetView() {
  mapZoom.value = 1
  mapPan.value = { x: 0, y: 0 }
}
function centerOnOwn() {
  if (!own.value || own.value.x === null || !mapWorld.value || !mapStage.value) return
  if (mapZoom.value < 2.5) mapZoom.value = 2.5
  mapPan.value = clampPan({
    x: (0.5 - own.value.x / 100) * mapWorld.value.offsetWidth * mapZoom.value,
    y: (0.5 - own.value.y / 100) * mapWorld.value.offsetHeight * mapZoom.value,
  })
}
function onPointerDown(event) {
  if ((event.pointerType === 'mouse' && event.button !== 0) || event.target.closest?.('.zoom-controls, .marker, .marker-info, .move-bubble, .toasts')) return
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
  zoomAround(mapZoom.value * (event.deltaY < 0 ? 1.2 : 1 / 1.2), stageOffset(event.clientX, event.clientY))
}
/** On your turn, tapping the map plans a march there. */
function onMapClick(event) {
  if (suppressClick) {
    suppressClick = false
    return
  }
  selectedMarker.value = ''
  if (!canMove.value || !mapWorld.value) return
  const bounds = mapWorld.value.getBoundingClientRect()
  const x = Math.max(0, Math.min(100, ((event.clientX - bounds.left) / bounds.width) * 100))
  const y = Math.max(0, Math.min(100, ((event.clientY - bounds.top) / bounds.height) * 100))
  chooseDestination({ x, y })
}
function onMarkerClick(marker) {
  const here = own.value && same(marker.x, marker.y, own.value.x, own.value.y)
  if (canMove.value && !here) {
    selectedMarker.value = ''
    chooseDestination({ x: marker.x, y: marker.y })
    return
  }
  selectedMarker.value = selectedMarker.value === marker.key ? '' : marker.key
}
function onResize() {
  viewportTick.value += 1
  mapPan.value = clampPan(mapPan.value)
}
async function loadTiles() {
  try {
    const response = await fetch(TILE_MANIFEST)
    if (!response.ok) return
    const manifest = await response.json()
    tileLevels.value = (manifest.levels || []).slice().sort((a, b) => a.width - b.width)
  } catch {
    // No zoom tiles: the base map still works, just softer close up.
  }
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
function sessionNumber(key, fallback = 0) {
  try { return Number(sessionStorage.getItem(key) || fallback) } catch { return fallback }
}
function setSession(key, value) {
  try { sessionStorage.setItem(key, String(value)) } catch { /* private mode */ }
}

function show(gameView) {
  const firstLoad = !game.value || game.value.code !== gameView.code
  game.value = gameView
  code.value = gameView.code
  if (firstLoad) {
    seenFate = sessionNumber(`thrones-fate-${gameView.code}`)
    seenBattle = sessionNumber(`thrones-battle-${gameView.code}`, gameView.lastBattle?.id || 0)
    lastEvent = gameView.events[gameView.events.length - 1] || null
  } else {
    pushToasts(gameView.events)
  }
  view.value = gameView.status === 'lobby' ? (seat.value ? 'lobby' : 'seat') : 'game'
  // Phones show only part of the map: open it on this house's army.
  if (firstLoad && view.value === 'game' && window.innerWidth <= 760) window.setTimeout(centerOnOwn, 300)
  if (view.value === 'game' && !isHost.value) {
    let seen = true
    try { seen = Boolean(localStorage.getItem(GUIDE_KEY)) } catch { /* private mode */ }
    if (!seen) openGuide()
  }
  if (gameView.longNight && !sessionNumber(`thrones-longnight-${gameView.code}`)) {
    setSession(`thrones-longnight-${gameView.code}`, 1)
    if (!firstLoad || gameView.round === gameView.winterRound) longNightShown.value = true
  }
  if (gameView.fate && gameView.fate.count > seenFate) {
    seenFate = gameView.fate.count
    setSession(`thrones-fate-${gameView.code}`, seenFate)
    fateShown.value = gameView.fate
  }
  if (gameView.lastBattle && gameView.lastBattle.id > seenBattle) {
    seenBattle = gameView.lastBattle.id
    setSession(`thrones-battle-${gameView.code}`, seenBattle)
    playBattle(gameView.lastBattle)
  }
}

/** New events since the last update appear as short toasts over the map. */
function pushToasts(events) {
  const index = lastEvent === null ? -1 : events.lastIndexOf(lastEvent)
  const fresh = index < 0 ? events.slice(-1) : events.slice(index + 1)
  lastEvent = events[events.length - 1] || lastEvent
  for (const text of fresh.slice(-3)) {
    const id = ++toastId
    toasts.value = [...toasts.value.slice(-2), { id, text }]
    window.setTimeout(() => { toasts.value = toasts.value.filter(toast => toast.id !== id) }, 6500)
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
      body: JSON.stringify({ playerName: hostName.value, mapWidthMiles: mapWidthMiles.value, chatMode: chatMode.value }),
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

// The host can seat bots in the lobby, one house at a time or every open house at once.
const lobbyHouses = computed(() => houses.value.filter(item => !isDead(item.id) || item.id === 'dead'))
const openHouseCount = computed(() => houses.value.filter(item => !item.claimed && !isDead(item.id)).length)
function addBot(houseId, difficulty) {
  return run(async () => show(await api(`${API}/${encodeURIComponent(code.value)}/bots`, {
    method: 'POST', body: JSON.stringify({ houseId, difficulty }),
  })))
}
function removeBot(houseId) {
  return run(async () => show(await api(`${API}/${encodeURIComponent(code.value)}/bots/${encodeURIComponent(houseId)}`, {
    method: 'DELETE',
  })))
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
function cancelMove() {
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
const destinationName = computed(() => {
  if (!destination.value) return ''
  const castle = castles.value.find(item => same(item.x, item.y, destination.value.x, destination.value.y))
  if (castle) return castle.name
  const place = (game.value?.places || []).find(item => same(item.x, item.y, destination.value.x, destination.value.y))
  return place?.name || ''
})
const modeLabel = { land: 'March', sail: 'Sail', fly: 'Fly' }
function confirmMove() {
  if (!destination.value) return
  return act('move', { x: destination.value.x, y: destination.value.y })
}

// ------------------------------------------------------------------ battle, fate and the guide
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

const GUIDE = [
  { icon: 'castle', title: 'Hold the whole map',
    text: 'Win by holding every castle on the map, alone or with your coalition. Being the last side standing wins too.' },
  { icon: 'march', title: 'Your turn',
    text: 'Tap the map to march. Fight whatever stands where you end up. Raise troops in your own castles with gold. Then end your turn.' },
  { icon: 'pact', title: 'Join forces',
    text: "Open Houses to invite other houses. Partners can't attack each other, see each other's armies, defend together and win together. You can leave, but you'll be an oathbreaker for 3 rounds." },
  { icon: 'snow', title: 'The Long Night',
    text: 'When Winter comes, three armies of the Dead march south to take the realm. Fight them side by side to be forged in winter: +1 on every die for the rest of the game.' },
]
function openGuide() {
  guideStep.value = 0
  guideOpen.value = true
}
function closeGuide() {
  guideOpen.value = false
  try { localStorage.setItem(GUIDE_KEY, '1') } catch { /* private mode */ }
}

// ------------------------------------------------------------------ lobby helpers
const selectedHouseData = computed(() => houses.value.find(item => item.id === chosenHouse.value))
watch(chosenHouse, () => { chosenHeir.value = selectedHouseData.value?.heirs?.[0] || '' })
const claimedCount = computed(() => houses.value.filter(item => item.claimed && !isDead(item.id)).length)
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
  void loadTiles()
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
    <TableTalk v-if="game && seat && game.chatMode && game.chatMode !== 'off' && ['lobby', 'game'].includes(view)"
      :key="`${game.code}:${seat}`" :api-base="`/api/thrones/${game.code}`" :seat="seat" :mode="game.chatMode"
      :side-color="side => side ? houseColor(side) : ''" />
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
        <button v-if="game" class="ghost-button" type="button" @click="view === 'game' ? openGuide() : (rulesOpen = true)">How to play</button>
        <button class="ghost-button exit-button" type="button" @click="emit('exit')">All campaigns</button>
      </div>
    </header>

    <div v-if="error" class="message error" role="alert">{{ error }}<button type="button" aria-label="Dismiss" @click="error = ''">×</button></div>
    <div v-if="notice" class="message notice" role="status">{{ notice }}<button type="button" aria-label="Dismiss" @click="notice = ''">×</button></div>
    <div v-if="over" class="victory" role="status">{{ game.winnerText }}</div>

    <!-- first-game guide -->
    <div v-if="guideOpen" class="backdrop" @click.self="closeGuide">
      <section class="guide" role="dialog" aria-modal="true" aria-labelledby="guide-title">
        <div class="guide-art" :class="GUIDE[guideStep].icon" aria-hidden="true">
          <svg v-if="GUIDE[guideStep].icon === 'castle'" viewBox="0 0 20 20"><path d="M2 19V8h3V5h2.5v3h2V3h1v5h2V5H15v3h3v11h-6v-4a2 2 0 0 0-4 0v4z" /></svg>
          <svg v-else-if="GUIDE[guideStep].icon === 'march'" viewBox="0 0 24 24"><path d="M4 18c3-6 7-2 10-8l2 2 3-7-7 3 2 2c-3 6-7 2-10 8" /></svg>
          <svg v-else-if="GUIDE[guideStep].icon === 'pact'" viewBox="0 0 24 24"><path d="M3 12l4-4 5 5 5-5 4 4-9 9z M7 8l5-5 5 5" /></svg>
          <svg v-else viewBox="0 0 24 24"><path :d="sigil('dead')" /></svg>
        </div>
        <p class="eyebrow">HOW TO PLAY · {{ guideStep + 1 }} OF {{ GUIDE.length }}</p>
        <h2 id="guide-title">{{ GUIDE[guideStep].title }}</h2>
        <p class="guide-text">{{ GUIDE[guideStep].text }}</p>
        <div class="guide-dots"><i v-for="(card, index) in GUIDE" :key="card.title" :class="{ on: index === guideStep }"></i></div>
        <div class="guide-buttons">
          <button v-if="guideStep > 0" class="action-button" type="button" @click="guideStep -= 1">Back</button>
          <button v-if="guideStep < GUIDE.length - 1" class="gold-button" type="button" @click="guideStep += 1">Next</button>
          <button v-else class="gold-button" type="button" @click="closeGuide">Start playing</button>
        </div>
        <button class="link-button" type="button" @click="closeGuide(); rulesOpen = true">Read the full rules</button>
      </section>
    </div>

    <!-- full rules -->
    <div v-if="rulesOpen" class="backdrop" @click.self="rulesOpen = false">
      <section class="sheet rules" role="dialog" aria-modal="true" aria-labelledby="rules-title">
        <header><h2 id="rules-title">The rules</h2><button type="button" aria-label="Close" @click="rulesOpen = false">×</button></header>
        <h3>Winning</h3>
        <ul>
          <li>Hold <b>every castle on the map</b> at the end of a round, alone or with your coalition. Everyone in the coalition wins.</li>
          <li>Or be the <b>last side standing</b>: every house outside your coalition, and the Dead, destroyed.</li>
          <li>The Dead win if every living house falls, or if they hold every castle.</li>
        </ul>
        <h3>Your turn</h3>
        <ul>
          <li>Collect gold: 1 for each castle, 2 for your own seat, 3 for King's Landing (Northern castles give 1 less in Winter).</li>
          <li>Move once, then fight once. You can't move after fighting. Raise troops (2 gold each, 2 a turn) in a castle you hold.</li>
        </ul>
        <h3>Movement</h3>
        <ul>
          <li>12 movement points a turn. Miles per point: plains 15, forest 10, desert 10, river 10 (+1 per crossing), snow 8, mountains 7.5, volcanic ground 6.</li>
          <li>Armies sail from port to port, using the whole turn. The Greyjoys sail coast to coast. Dragons fly anywhere.</li>
          <li>The Wall can only be crossed through a castle on it that you or a partner hold, until the Dead breach it.</li>
          <li>Ending in mountains or volcanic ground hides your army; forest hides it half the time.</li>
        </ul>
        <h3>Battle</h3>
        <ul>
          <li>Each side rolls a die per unit (a lone leader or empty walls roll one); dragons add 2 dice each. Higher total wins; ties go to the defender. The loser loses a unit.</li>
          <li>A castle whose garrison is gone falls when you beat its walls once more. Partners standing together defend together.</li>
          <li><b>Valar morghulis:</b> an army with no units that loses again falls, and the next heir rises at the house's seat. With no heirs or castles left, the house is out.</li>
        </ul>
        <h3>Coalitions</h3>
        <ul>
          <li>Invite any house from the Houses panel, at any time. Accepting joins your coalitions together.</li>
          <li>Partners can't attack each other, see each other's armies, defend together and win together.</li>
          <li>Leave on your turn. Attacking a former partner that same turn is a <b>Red Wedding</b> (+2 on every die), and makes you an oathbreaker: no new partners and no income for 3 rounds.</li>
          <li><b>Forged in winter:</b> partners who both fight the Dead during the Long Night get +1 on every die for the rest of the game, while they stay together.</li>
        </ul>
        <h3>Seasons and the Long Night</h3>
        <ul>
          <li>Summer, then Autumn, then Winter (round 8, sooner if Fate wills it). From Autumn on, snow creeps south.</li>
          <li>In Winter the Long Night falls: the Dead's three armies grow every round and march south, taking castles and fighting armies. Each battle they win against an army raises another unit.</li>
          <li>Only dragons, Valyrian steel (Jon, Jaime or Arya leading) or Dragonstone's dragonglass can slay the Night King, and slaying him destroys every army of the Dead.</li>
          <li>Every round after the first begins with a Fate card.</li>
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

    <!-- the Long Night -->
    <div v-if="longNightShown" class="backdrop" @click.self="longNightShown = false">
      <section class="fate-card long-night" role="dialog" aria-modal="true" aria-labelledby="night-title">
        <svg class="night-sigil" viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil('dead')" /></svg>
        <p class="eyebrow">WINTER HAS COME</p>
        <h2 id="night-title">The Long Night falls</h2>
        <p>Three armies of the Dead march on the Wall and will try to take every castle in the realm. Houses that fight them side by side are forged in winter: +1 on every die for the rest of the game.</p>
        <button class="gold-button" type="button" @click="longNightShown = false">We stand together</button>
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
        <p class="eyebrow">A GAME OF HOUSES, ALLIANCES AND WINTER</p>
        <h1>The Iron Throne</h1>
        <p class="subtitle">Hold the realm together. Survive the Long Night.</p>
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
        <button class="link-button muted" type="button" @click="emit('exit')">← All campaigns</button>
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
        <label>TABLE TALK</label>
        <div class="chat-mode" role="radiogroup" aria-label="Table talk">
          <button v-for="option in CHAT_MODES" :key="option.id" type="button" role="radio" :aria-checked="chatMode === option.id"
            :class="{ selected: chatMode === option.id }" @click="chatMode = option.id">
            {{ option.label }}<small>{{ option.hint }}</small>
          </button>
        </div>
        <p class="hint">2 to 7 houses, played by people on their own phones or by bots you add in the lobby. The Dead are always in the game, played by the server.</p>
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
        <button v-for="item in lobbyHouses" :key="item.id" type="button" class="house-tile"
          :class="{ chosen: chosenHouse === item.id, taken: item.claimed || isDead(item.id) }" :disabled="item.claimed || isDead(item.id)"
          :style="houseStyle(item.id)" @click="chosenHouse = item.id">
          <svg class="sigil" viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil(item.id)" /></svg>
          <strong>{{ isDead(item.id) ? 'The Dead' : item.name }}</strong>
          <small>{{ isDead(item.id) ? 'Played by the server · three armies' : item.claimed ? `Taken by ${item.playerName}` : `${item.seat} · ${HOUSES[item.id].words}` }}</small>
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
        <p class="hint">Players open this app, choose <b>The Iron Throne</b>, and enter the code. {{ claimedCount }} of 7 houses claimed.</p>
        <button class="action-button inline" type="button" @click="copyLink">Copy join link</button>
      </div>
      <div class="house-grid">
        <div v-for="item in lobbyHouses" :key="item.id" class="house-tile static" :class="{ taken: item.claimed, mine: item.id === game.ownHouseId }"
          :style="houseStyle(item.id)">
          <svg class="sigil" viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil(item.id)" /></svg>
          <strong>{{ isDead(item.id) ? 'The Dead' : item.name }}</strong>
          <small>{{ isDead(item.id) ? 'Played by the server · three armies' : item.claimed ? `${item.playerName} · ${item.leader}` : 'Open · becomes neutral' }}</small>
          <span v-if="isHost && !item.claimed && !isDead(item.id)" class="bot-actions">
            <button type="button" :disabled="busy" @click="addBot(item.id, 'easy')">+ Easy bot</button>
            <button type="button" :disabled="busy" @click="addBot(item.id, 'hard')">+ Hard bot</button>
          </span>
          <button v-else-if="isHost && item.bot && !isDead(item.id)" class="bot-remove" type="button" :disabled="busy"
            :aria-label="`Remove the bot from ${item.name}`" title="Remove bot" @click="removeBot(item.id)">×</button>
        </div>
      </div>
      <div v-if="isHost && openHouseCount" class="bot-fill">
        <span>Fill {{ openHouseCount === 1 ? 'the open house' : `the ${openHouseCount} open houses` }} with bots:</span>
        <button type="button" :disabled="busy" @click="addBot(null, 'easy')">Easy</button>
        <button type="button" :disabled="busy" @click="addBot(null, 'hard')">Hard</button>
      </div>
      <div class="lobby-foot">
        <button v-if="isHost" class="gold-button" type="button" :disabled="busy || claimedCount < 2" @click="startGame">
          {{ claimedCount < 2 ? `Waiting for ${2 - claimedCount} more house${2 - claimedCount === 1 ? '' : 's'}` : 'Start the game' }}
        </button>
        <p v-else class="hint">Waiting for the host to start the game.</p>
      </div>
    </section>

    <!-- the game -->
    <section v-else-if="view === 'game' && game" class="play">
      <div class="statusbar">
        <span class="chip season" :class="game.season.toLowerCase()">
          Round {{ game.round }} · {{ game.season }}<small v-if="!game.longNight"> · the Long Night in {{ plural(winterIn, 'round') }}</small>
        </span>
        <div class="goal" :title="`Win by holding all ${totalCastles} castles`">
          <span class="goal-label">{{ goalLabel }}</span>
          <span class="goal-bar" aria-hidden="true">
            <i class="mine" :style="{ width: pct(myCastles / totalCastles * 100) }"></i>
            <i class="dead" :style="{ width: pct(deadCastles / totalCastles * 100) }"></i>
          </span>
          <span class="goal-count"><b>{{ myCastles }}</b> / {{ totalCastles }} castles</span>
          <span v-if="deadCastles" class="goal-dead">The Dead {{ deadCastles }}</span>
        </div>
        <span v-if="own?.alive" class="chip gold">{{ own.gold }} gold <small>+{{ own.income }}</small></span>
        <button class="ghost-button houses-button" type="button" @click="housesOpen = true">
          Houses<i v-if="incomingInvites.length" class="ping" aria-label="You have an invitation"></i>
        </button>
      </div>

      <div class="play-body">
        <section class="map-panel">
          <div ref="mapStage" class="map-stage" :class="{ moving: canMove }" @pointerdown="onPointerDown" @pointermove="onPointerMove"
            @pointerup="onPointerUp" @pointercancel="onPointerUp" @wheel.prevent="onWheel" @click="onMapClick">
            <div ref="mapWorld" class="map-world" :class="{ gesturing }" :style="worldStyle">
              <img src="/map-assets/westeros/map.webp" alt="Map of Westeros and Essos" draggable="false" />
              <img v-for="tile in hdTiles" :key="tile.key" class="hd-tile" :src="tile.src" :style="tile.style" alt="" draggable="false" decoding="async" />
              <div v-if="game.snowLine > 17" class="snow-veil" :style="{ height: pct(game.snowLine) }" aria-hidden="true"></div>
              <div v-if="game.snowLine > 17" class="snow-edge" :style="{ top: pct(game.snowLine) }" aria-hidden="true"></div>
              <span v-for="place in labels" :key="place.name" class="place-label" :class="place.kind"
                :style="{ left: pct(place.x), top: pct(place.y) }">{{ place.name }}</span>
              <span v-for="marker in markers" :key="marker.key" class="marker" :class="[marker.kind, { selected: selectedMarker === marker.key,
                active: marker.kind === 'house' && marker.house.id === game.activeHouse, mine: marker.kind === 'house' && marker.house.id === game.ownHouseId }]"
                :style="marker.style">
                <button v-if="marker.kind === 'house'" type="button" class="army-token" :class="{ ally: allied(own?.id, marker.house.id) }"
                  :style="houseStyle(marker.house.id)" :aria-label="`${marker.house.leader} of ${marker.house.name}`" @click.stop="onMarkerClick(marker)">
                  <img v-if="hasPortrait(marker.house.leader)" :src="`/portraits/thrones/${portraitSlug(marker.house.leader)}.png`" alt="" @error="portraitMissing(marker.house.leader)" />
                  <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil(marker.house.id)" /></svg>
                  <b class="unit-badge">{{ marker.house.units }}</b>
                  <i v-if="marker.house.dragons?.length" class="dragon-badge" :title="marker.house.dragons.join(', ')">{{ marker.house.dragons.length }} dragon{{ marker.house.dragons.length === 1 ? '' : 's' }}</i>
                </button>
                <button v-else type="button" class="castle-token"
                  :class="{ great: marker.castle.great, capital: marker.castle.kind === 'capital', minor: minorCastle(marker.castle),
                    ours: mySide.includes(marker.castle.owner), dead: isDead(marker.castle.owner) }"
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
                    <span v-if="allied(own?.id, marker.house.id)">Your partner{{ marker.house.sworn ? ' · forged in winter' : '' }}</span>
                  </template>
                  <template v-else>
                    <strong>{{ marker.castle.name }}</strong>
                    <span>{{ marker.castle.region }}{{ marker.castle.great ? ' · great seat' : '' }}{{ marker.castle.port ? ' · port' : '' }}</span>
                    <span>Held by {{ houseName(marker.castle.owner) }} · garrison {{ marker.castle.units }}</span>
                  </template>
                  <button v-for="target in attackTargets.filter(t => t.id === (marker.kind === 'house' ? marker.house.id : marker.castle.id))"
                    :key="target.id" type="button" class="gold-button small" @click="act('attack', { targetId: target.id })">Attack · {{ target.odds }}% to win</button>
                </div>
              </span>
              <span v-if="destination" class="destination" :class="{ bad: preview && (preview.error || preview.remaining < 0) }"
                :style="{ left: pct(destination.x), top: pct(destination.y) }"></span>
              <div v-if="destination" class="move-bubble" :class="{ below: destination.y < 40 }"
                :style="{ left: pct(destination.x), top: pct(destination.y) }" @click.stop>
                <template v-if="preview && !preview.error">
                  <strong>{{ modeLabel[preview.mode] || 'March' }}{{ destinationName ? ` to ${destinationName}` : ' here' }}</strong>
                  <span :class="{ 'bad-text': preview.remaining < 0 }">{{ preview.movementPoints.toFixed(1) }} of {{ own.movementRemaining.toFixed(1) }} points · {{ preview.miles.toFixed(0) }} miles</span>
                  <span v-if="preview.remaining < 0" class="bad-text">Too far for this turn.</span>
                  <div class="bubble-buttons">
                    <button class="gold-button small" type="button" :disabled="busy || !previewOk" @click="confirmMove">{{ modeLabel[preview.mode] || 'March' }}</button>
                    <button class="action-button small" type="button" @click="cancelMove">Cancel</button>
                  </div>
                </template>
                <template v-else-if="preview?.error">
                  <span class="bad-text">{{ preview.error }}</span>
                  <button class="action-button small" type="button" @click="cancelMove">OK</button>
                </template>
                <span v-else>Measuring the road…</span>
              </div>
            </div>
            <div v-if="!isMyTurn && active && !over" class="turn-banner" :style="houseStyle(active.id)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil(active.id)" /></svg>
              {{ active.leader }} is on the move{{ active.bot ? '…' : '' }}
            </div>
            <div class="toasts" aria-live="polite">
              <p v-for="toast in toasts" :key="toast.id">{{ toast.text }}</p>
            </div>
            <div class="zoom-controls" @click.stop>
              <button type="button" aria-label="Zoom in" :disabled="mapZoom >= maxZoom - 0.001" @click="zoomBy(1.5)">+</button>
              <button type="button" aria-label="Zoom out" :disabled="mapZoom <= MIN_ZOOM" @click="zoomBy(1 / 1.5)">−</button>
              <button type="button" @click="resetView">Whole map</button>
              <button v-if="own && own.x !== null" type="button" aria-label="Find my army" title="Find my army" @click="centerOnOwn">◎</button>
            </div>
          </div>
        </section>

        <aside class="turn-card">
          <template v-if="own">
            <header class="tc-head" :style="houseStyle(own.id)">
              <img v-if="hasPortrait(own.leader)" class="portrait" :src="`/portraits/thrones/${portraitSlug(own.leader)}.png`" :alt="own.leader" @error="portraitMissing(own.leader)" />
              <svg v-else class="sigil big" viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil(own.id)" /></svg>
              <div>
                <h1>{{ own.leader }}</h1>
                <p>{{ own.name }}<template v-if="own.alive"> · {{ own.units }} units · {{ dice(own) }} dice</template></p>
                <p class="badges">
                  <span v-if="own.sworn" class="badge winter">Forged in winter +1</span>
                  <span v-if="own.dragons.length" class="badge">{{ own.dragons.length }} dragon{{ own.dragons.length === 1 ? '' : 's' }}</span>
                  <span v-if="isOathbreaker" class="badge danger-badge">Oathbreaker until round {{ own.oathbreakerUntil }}</span>
                </p>
              </div>
            </header>

            <p v-if="!own.alive" class="waiting">Your house has fallen. You can keep watching the realm.</p>
            <template v-else-if="isMyTurn">
              <ol class="steps">
                <li v-for="item in steps" :key="item.id" :class="{ done: item.done, now: step === item.id }">{{ item.label }}</li>
              </ol>
              <p class="step-hint">{{ stepHint }}</p>

              <div v-if="destination && preview && !preview.error" class="move-box">
                <span>{{ modeLabel[preview.mode] || 'March' }}{{ destinationName ? ` to ${destinationName}` : '' }} · {{ preview.movementPoints.toFixed(1) }} of {{ own.movementRemaining.toFixed(1) }} points</span>
                <button class="gold-button" type="button" :disabled="busy || !previewOk" @click="confirmMove">{{ modeLabel[preview.mode] || 'March' }}</button>
              </div>

              <button v-for="target in attackTargets" :key="target.id" class="battle-button" type="button" :disabled="busy"
                @click="act('attack', { targetId: target.id })">
                <strong>Attack {{ target.label }}</strong>
                <span>Your {{ dice(own) }} dice against {{ target.dice }} · about {{ target.odds }}% to win</span>
              </button>

              <div v-if="canRecruit" class="troops">
                <span>Raise troops here</span>
                <button class="action-button" type="button" :disabled="busy || own.gold < game.recruitCost" @click="act('recruit', { count: 1 })">+1 · {{ game.recruitCost }} gold</button>
                <button v-if="own.recruitedThisTurn === 0" class="action-button" type="button" :disabled="busy || own.gold < game.recruitCost * 2"
                  @click="act('recruit', { count: 2 })">+2 · {{ game.recruitCost * 2 }} gold</button>
              </div>

              <details v-if="!own.powerUsed && own.id !== 'tyrell'" class="power-box">
                <summary>Once per game: {{ own.power }}</summary>
                <p class="hint">{{ HOUSES[own.id].power }}</p>
                <select v-if="powerTargets.length" v-model="powerTarget" aria-label="Power target">
                  <option v-for="target in powerTargets" :key="target.id" :value="target.id">{{ target.leader }} ({{ target.name }})</option>
                </select>
                <button class="action-button" type="button" :disabled="busy || !powerReady" @click="act('power', { targetId: powerTarget || null })">Use {{ own.power }}</button>
                <p v-if="!powerReady" class="hint">Not available where you stand right now.</p>
              </details>

              <button class="end-turn" :class="step === 'done' ? 'gold-button' : 'action-button'" type="button" :disabled="busy" @click="act('end-turn')">End turn</button>
            </template>
            <p v-else-if="!over" class="waiting">Waiting for {{ active?.leader }} of {{ active?.name }}.</p>

            <div v-for="invite in incomingInvites" :key="`in-${invite.from}`" class="invite-card" :style="houseStyle(invite.from)">
              <p><b>{{ houseName(invite.from) }}</b> invites you to {{ invite.royal ? 'a Royal Wedding' : 'join forces' }}.</p>
              <div>
                <button class="gold-button small" type="button" :disabled="busy" @click="act('coalitions/respond', { houseId: invite.from, accept: true })">Accept</button>
                <button class="action-button small" type="button" :disabled="busy" @click="act('coalitions/respond', { houseId: invite.from, accept: false })">Refuse</button>
              </div>
            </div>
          </template>
          <template v-else>
            <p class="eyebrow">HOST VIEW</p>
            <h1>The realm</h1>
            <p class="hint">You are watching as the host. Players act from their own devices.</p>
            <p class="waiting">{{ over ? game.winnerText : `${active?.leader} of ${active?.name} is taking their turn.` }}</p>
          </template>

          <button v-if="game.fate" class="fate-mini" type="button" @click="fateShown = game.fate">
            <span class="eyebrow">FATE · ROUND {{ game.fate.round }}</span>
            <strong>{{ game.fate.title }}</strong>
          </button>
        </aside>
      </div>

      <!-- houses and coalitions -->
      <div v-if="housesOpen" class="drawer-backdrop" @click.self="housesOpen = false">
        <aside class="drawer" role="dialog" aria-modal="true" aria-labelledby="houses-title">
          <header><h2 id="houses-title">Houses</h2><button type="button" aria-label="Close" @click="housesOpen = false">×</button></header>
          <p class="hint">Hold every castle together to win. Partners can't attack each other, see each other's armies, defend together and win together.</p>
          <section v-for="side in sides" :key="side.key" class="side" :class="{ mine: side.members.some(m => m.id === own?.id), dead: side.dead }">
            <div class="side-head">
              <strong>{{ side.title }}</strong>
              <span>{{ side.castles }} / {{ totalCastles }} castles</span>
              <span v-if="side.forged >= 2" class="badge winter">Forged in winter</span>
            </div>
            <div v-for="member in side.members" :key="member.id" class="member" :style="houseStyle(member.id)">
              <svg class="sigil small" viewBox="0 0 24 24" aria-hidden="true"><path :d="sigil(member.id)" /></svg>
              <span class="member-name">
                <strong>{{ member.leader }}</strong>
                <small>{{ member.name }} · {{ member.units === null ? 'hidden' : `${member.units} units` }}{{ member.ai ? '' : member.bot ? ' · bot' : member.playerName ? ` · ${member.playerName}` : '' }}</small>
              </span>
              <template v-if="own?.alive && member.id !== own.id && !member.dead && !allied(own.id, member.id) && !over">
                <span v-if="invites.some(i => i.from === own.id && i.to === member.id)" class="hint">invited</span>
                <template v-else>
                  <button class="action-button small" type="button" :disabled="busy || isOathbreaker || member.oathbreakerUntil >= game.round"
                    @click="act('coalitions/invite', { houseId: member.id })">Invite</button>
                  <button v-if="own.id === 'tyrell' && !own.powerUsed" class="gold-button small" type="button" :disabled="busy || isOathbreaker"
                    @click="act('coalitions/invite', { houseId: member.id, royal: true })">Royal Wedding</button>
                </template>
              </template>
            </div>
            <div v-if="myCoalition && side.key === myCoalition.id && !over" class="leave">
              <button class="danger small" type="button" :disabled="busy || !isMyTurn || own.lockedUntil >= game.round" @click="act('coalitions/leave')">Leave the coalition</button>
              <span class="hint">{{ own.lockedUntil >= game.round ? `A Royal Wedding binds you until round ${own.lockedUntil}.`
                : 'On your turn. Attack a former partner the same turn for a Red Wedding (+2 per die), but you become an oathbreaker for 3 rounds.' }}</span>
            </div>
          </section>
          <h3>The maester's record</h3>
          <div class="record">
            <p v-for="(event, index) in recentEvents" :key="`${index}-${event}`">{{ event }}</p>
          </div>
        </aside>
      </div>
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
.ghost-button { position: relative; min-height: 30px; padding: 0 10px; border: 1px solid #45392e; border-radius: 6px; background: transparent; color: #d6ccb6; font: 600 11px 'DM Sans', sans-serif; cursor: pointer; }
.ghost-button:hover { border-color: var(--gold); }
.message { display: flex; justify-content: space-between; gap: 10px; padding: 9px 16px; font-size: 12px; z-index: 4; }
.message button { border: 0; background: none; color: inherit; font-size: 18px; cursor: pointer; }
.message.error { background: #3d1f1c; color: #f3c3b6; border-bottom: 1px solid #8e4a3c; }
.message.notice { background: #222a1f; color: #d7dfbd; border-bottom: 1px solid #5d6e4f; }
.victory { padding: 12px 16px; color: #fbe7a6; text-align: center; border-bottom: 1px solid #b38a3c; }
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
.sigil.small { width: 24px; height: 24px; flex: none; padding: 3px; border-radius: 5px; background: var(--house); }
.sigil.big { width: 52px; height: 52px; flex: none; padding: 8px; border-radius: 10px; background: var(--house); }
.ping { position: absolute; top: -4px; right: -4px; width: 10px; height: 10px; border-radius: 50%; background: #f2cd6c; box-shadow: 0 0 10px #f2cd6c; }
.badge { display: inline-block; margin: 2px 4px 0 0; padding: 2px 7px; border: 1px solid #5b4a30; border-radius: 10px; color: #e6cf98; font-size: 10px; }
.badge.winter { border-color: #4b7083; color: var(--ice); }
.danger-badge { border-color: #a5463a; color: #ffb9a8; }
.bad-text { color: #f0a796; }

/* welcome */
.welcome { flex: 1; min-height: 0; display: grid; place-items: safe center; padding: 22px 16px; overflow-y: auto;
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
.host-card h1 { text-align: center; }
.saved { margin-top: 18px; display: grid; gap: 6px; text-align: left; }
.saved-row { display: flex; gap: 6px; }
.saved-button { flex: 1; display: flex; justify-content: space-between; align-items: center; min-height: 40px; padding: 0 12px; border: 1px solid #3d352d; border-radius: 7px; background: #1e1b20; color: var(--ink); cursor: pointer; font: inherit; }
.saved-button small { color: var(--muted); }
.forget { width: 40px; border: 1px solid #3d352d; border-radius: 7px; background: none; color: var(--muted); cursor: pointer; font-size: 18px; }

/* lobby */
.lobby { flex: 1; min-height: 0; overflow-y: auto; padding: 20px clamp(14px, 4vw, 48px) 28px; text-align: center;
  background: linear-gradient(#0f0e11c4, #0f0e11ee), url('/map-assets/westeros/map.webp') center / cover; }
.lobby-head h1 { margin: 6px 0; font-size: 30px; }
.lobby-head h1.code { font: 700 46px 'Barlow Condensed', sans-serif; letter-spacing: 10px; color: #f6dea0; }
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
.bot-actions { display: flex; gap: 4px; margin: 4px 0 0; }
.bot-actions button, .bot-fill button { min-height: 26px; padding: 0 8px; border: 1px solid #5b4a30; border-radius: 13px; background: rgba(217,178,106,.05); color: #e1d3b0; font: 600 10px 'DM Sans', sans-serif; cursor: pointer; white-space: nowrap; }
.bot-actions button:disabled, .bot-fill button:disabled, .bot-remove:disabled { opacity: .45; cursor: wait; }
.bot-remove { width: 24px; height: 24px; margin: 2px 0 0; border: 1px solid #3a3530; border-radius: 50%; background: transparent; color: var(--muted); font-size: 15px; line-height: 1; cursor: pointer; }
.bot-fill { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 8px; margin: 12px 0 4px; color: var(--muted); }

/* the game: status bar, map and the Your turn card */
.play { position: relative; flex: 1; min-height: 0; display: flex; flex-direction: column; }
.statusbar { flex: none; display: flex; align-items: center; gap: 12px; padding: 8px 14px; border-bottom: 1px solid #2c2722; background: #121015; }
.chip { flex: none; padding: 5px 10px; border: 1px solid #3a3530; border-radius: 14px; color: #e3d6b6; font-size: 12px; font-weight: 600; white-space: nowrap; }
.chip small { color: var(--muted); font-weight: 500; }
.chip.winter { border-color: #4b7083; color: var(--ice); }
.chip.autumn { border-color: #7a5229; color: #e8b277; }
.chip.gold { border-color: #6b5631; color: #f2d58b; }
.goal { flex: 1; min-width: 0; display: flex; align-items: center; gap: 9px; }
.goal-label { color: var(--muted); font-size: 11px; white-space: nowrap; }
.goal-bar { position: relative; flex: 1; min-width: 60px; height: 10px; overflow: hidden; border-radius: 5px; background: #2a2620; box-shadow: inset 0 0 0 1px #3a3530; }
.goal-bar i { position: absolute; top: 0; bottom: 0; }
.goal-bar .mine { left: 0; background: linear-gradient(90deg, #a7772f, #f2d58b); transition: width .6s; }
.goal-bar .dead { right: 0; background: linear-gradient(90deg, #6aa8c4, #cdeefa); transition: width .6s; }
.goal-count { color: #eadfc6; font-size: 12px; white-space: nowrap; }
.goal-count b { color: #f6dea0; font-size: 14px; }
.goal-dead { color: var(--ice); font-size: 11px; white-space: nowrap; }
.play-body { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr) 320px; }
.map-panel { position: relative; min-width: 0; min-height: 0; display: flex; flex-direction: column; background: #10161a; }
.map-stage { position: relative; flex: 1; min-height: 0; overflow: hidden; display: grid; place-items: center; touch-action: none; user-select: none; background: #123447; }
.map-stage.moving { cursor: crosshair; }
.map-world { position: relative; width: min(100%, calc((100dvh - 140px) * 2)); aspect-ratio: 1774 / 887; transform-origin: center; }
.map-world.gesturing { will-change: transform; }
.map-world > img { display: block; width: 100%; height: 100%; pointer-events: none; user-select: none; }
.map-world > img.hd-tile { position: absolute; max-width: none; }
.snow-veil { position: absolute; left: 0; right: 0; top: 0; pointer-events: none;
  background: linear-gradient(to bottom, #f4fbff80, #e8f6ff60 85%, #e8f6ff10); mix-blend-mode: screen; }
/* The snow line: a 2-px strip that shrinks as the map zooms in, so it stays 2 px on screen. */
.snow-edge { position: absolute; left: 0; right: 0; height: 2px; pointer-events: none; transform-origin: 0 0;
  transform: scaleY(var(--marker-scale, 1)); background: repeating-linear-gradient(90deg, #e4f6ffcc 0 6px, transparent 6px 12px); }
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
.castle-token.ours { box-shadow: 0 0 0 2px var(--house), 0 0 0 5px #f2d58b, 0 0 14px #f2d58b88; }
.castle-token.dead { box-shadow: 0 0 0 2px #8fd3ee, 0 0 14px #8fd3eeaa; }
.castle-token.minor { width: 13px; height: 13px; border-radius: 4px; background: var(--house); box-shadow: 0 0 0 2px #1a1714, 0 1px 4px #000a; }
.castle-token.minor.ours { box-shadow: 0 0 0 2px #f2d58b, 0 1px 4px #000a; }
.castle-token.minor svg, .castle-token.minor .garrison { display: none; }
.castle-token .garrison { position: absolute; right: -7px; bottom: -7px; min-width: 15px; height: 15px; padding: 0 3px; border-radius: 8px; background: #0f0e11; color: #f3e6c4; font: 700 9px/15px 'DM Sans', sans-serif; box-shadow: 0 0 0 1px var(--house); }
.army-token { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%; background: var(--house); box-shadow: 0 0 0 2px #f3e6c4, 0 3px 8px #000c; overflow: visible; }
.army-token img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; }
.army-token svg { width: 24px; height: 24px; fill: none; stroke: var(--house-ink); stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.army-token.ally { box-shadow: 0 0 0 2px #f2d58b, 0 0 12px #f2d58b88, 0 3px 8px #000c; }
.marker.active .army-token { box-shadow: 0 0 0 2px #f3e6c4, 0 0 0 5px #f2cd6c, 0 3px 8px #000c; animation: pulse 1.6s ease-in-out infinite; }
.marker.mine .army-token { box-shadow: 0 0 0 3px #ffe7a0, 0 3px 8px #000c; }
@keyframes pulse { 50% { transform: translate(-50%, -50%) scale(1.08); } }
.unit-badge { position: absolute; right: -6px; bottom: -6px; min-width: 17px; height: 17px; padding: 0 3px; border-radius: 9px; background: #0f0e11; color: #fff; font: 700 10px/17px 'DM Sans', sans-serif; }
.dragon-badge { position: absolute; left: 50%; top: -18px; transform: translateX(-50%); white-space: nowrap; font-style: normal; font-size: 9px; background: #2b1a1dee; border: 1px solid #e0473a; border-radius: 8px; padding: 0 5px; color: #ffb4a0; }
.marker-info { position: absolute; left: 22px; top: -10px; width: 220px; display: grid; gap: 4px; padding: 10px; border: 1px solid #6b5631; border-radius: 9px; background: #17151af2; box-shadow: 0 10px 30px #000b; font-size: 11px; color: #d8ceb6; }
.marker-info strong { font: 600 14px 'Cinzel', serif; color: #f4e9cc; }
.destination { position: absolute; z-index: 6; width: 20px; height: 20px; border: 3px solid #f2d578; border-radius: 50%; box-shadow: 0 0 0 4px #f2d57855; transform: translate(-50%, -50%) scale(var(--marker-scale, 1)); pointer-events: none; }
.destination.bad { border-color: #e0604c; box-shadow: 0 0 0 4px #e0604c55; }
.move-bubble { position: absolute; z-index: 9; width: 220px; display: grid; gap: 4px; padding: 10px 11px; border: 1px solid #8c6b33; border-radius: 10px;
  background: #17151af4; box-shadow: 0 12px 30px #000c; color: #e6dcc2; font-size: 11px; transform-origin: 50% 100%;
  transform: translate(-50%, calc(-100% - 18px)) scale(var(--marker-scale, 1)); }
.move-bubble.below { transform-origin: 50% 0; transform: translate(-50%, 18px) scale(var(--marker-scale, 1)); }
.move-bubble strong { font: 600 14px 'Cinzel', serif; color: #f6dea0; }
.bubble-buttons { display: flex; gap: 6px; margin-top: 4px; }
.turn-banner { position: absolute; left: 50%; top: 12px; z-index: 9; display: flex; align-items: center; gap: 8px; padding: 6px 12px 6px 8px; transform: translateX(-50%);
  border: 1px solid #5b4a30; border-radius: 20px; background: #17151ae6; color: #f1dfaa; font-size: 12px; pointer-events: none; white-space: nowrap; }
.turn-banner svg { width: 22px; height: 22px; padding: 3px; border-radius: 50%; background: var(--house); fill: none; stroke: var(--house-ink); stroke-width: 2; }
.toasts { position: absolute; left: 12px; bottom: 12px; z-index: 9; display: grid; gap: 6px; max-width: min(420px, 70%); pointer-events: none; }
.toasts p { margin: 0; padding: 8px 11px; border: 1px solid #3a3530; border-left: 3px solid var(--gold); border-radius: 6px; background: #17151af0; color: #e6dcc2; font-size: 12px; line-height: 1.4;
  box-shadow: 0 8px 22px #000a; animation: toast-in .3s ease-out; }
@keyframes toast-in { from { opacity: 0; transform: translateY(8px); } }
.zoom-controls { position: absolute; right: 12px; bottom: 12px; z-index: 9; display: flex; align-items: center; gap: 4px; padding: 4px; border: 1px solid #3a3530; border-radius: 8px; background: #17151ae6; }
.zoom-controls button { min-width: 30px; height: 30px; padding: 0 8px; border: 0; border-radius: 5px; background: #2a262b; color: #eee3c9; font: 600 13px 'DM Sans', sans-serif; cursor: pointer; }
.zoom-controls button:disabled { opacity: .4; }

.turn-card { min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; padding: 14px; border-left: 1px solid #2c2722; background: var(--panel); }
.tc-head { display: flex; gap: 12px; align-items: center; padding-bottom: 10px; border-bottom: 2px solid var(--house); }
.tc-head h1 { margin: 0; font-size: 20px; }
.tc-head p { margin: 3px 0 0; color: var(--muted); font-size: 11px; }
.portrait { width: 56px; height: 56px; border-radius: 10px; object-fit: cover; border: 2px solid var(--house); }
.steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; margin: 0; padding: 0; list-style: none; counter-reset: step; }
.steps li { counter-increment: step; padding: 6px 2px; border: 1px solid #3a3530; border-radius: 6px; color: #8f877a; font-size: 10px; font-weight: 700; text-align: center; letter-spacing: .4px; }
.steps li::before { content: counter(step) ' '; }
.steps li.done { border-color: #3d4a33; color: #8fb07a; text-decoration: line-through; }
.steps li.now { border-color: var(--gold); background: #2c2619; color: #f6dea0; box-shadow: 0 0 12px #d8b35f44; }
.step-hint { margin: 0; color: #e6dcc2; font-size: 13px; line-height: 1.45; }
.move-box { display: grid; gap: 6px; padding: 9px; border: 1px solid #6b5631; border-radius: 8px; background: #1d1a15; color: #e6dcc2; font-size: 12px; }
.battle-button { display: grid; gap: 2px; padding: 9px 11px; border: 1px solid #a5463a; border-radius: 8px; background: #3a1714; color: #ffd8cd; text-align: left; cursor: pointer; font: inherit; }
.battle-button strong { font-size: 13px; }
.battle-button span { color: #e3b2a6; font-size: 11px; }
.troops { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 9px; border: 1px solid #3a3530; border-radius: 8px; }
.troops span { flex: 1 1 100%; color: var(--muted); font-size: 11px; }
.power-box { display: grid; gap: 6px; padding: 9px; border: 1px dashed #6b5631; border-radius: 8px; }
.power-box summary { color: #f3d48a; font-size: 12px; font-weight: 600; cursor: pointer; }
.power-box[open] { gap: 8px; }
.end-turn { width: 100%; }
.waiting { margin: 0; padding: 12px; border: 1px solid #332d27; border-radius: 10px; color: #cbbfa5; text-align: center; }
.invite-card { display: grid; gap: 8px; padding: 10px; border: 1px solid var(--house); border-radius: 8px; background: #2c2619; }
.invite-card p { margin: 0; font-size: 12px; }
.invite-card div { display: flex; gap: 6px; }
.fate-mini { display: grid; gap: 2px; margin-top: auto; padding: 9px 11px; border: 1px solid #5a4126; border-radius: 8px; background: #1d1a15; color: #f0c98a; text-align: left; cursor: pointer; font: inherit; }
.fate-mini strong { font: 600 13px 'Cinzel', serif; }

/* houses drawer */
.drawer-backdrop { position: absolute; inset: 0; z-index: 30; display: flex; justify-content: flex-end; background: #05040799; }
.drawer { width: min(100%, 420px); height: 100%; overflow-y: auto; padding: 16px; border-left: 1px solid #5b4a30; background: #141216; box-shadow: -20px 0 50px #000b; animation: slide-in .2s ease-out; }
@keyframes slide-in { from { transform: translateX(30px); opacity: 0; } }
.drawer header { display: flex; justify-content: space-between; align-items: center; }
.drawer header h2 { margin: 0; font-size: 22px; }
.drawer header button { border: 0; background: none; color: var(--muted); font-size: 24px; cursor: pointer; }
.drawer h3 { margin: 18px 0 6px; color: var(--gold); font-size: 11px; letter-spacing: 1.4px; text-transform: uppercase; }
.side { display: grid; gap: 6px; margin-top: 10px; padding: 10px; border: 1px solid #332d27; border-radius: 10px; background: #1a181d; }
.side.mine { border-color: var(--gold); box-shadow: 0 0 0 1px #d8b35f55; }
.side.dead { border-color: #4b7083; background: #141c21; }
.side-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; }
.side-head strong { font: 600 14px 'Cinzel', serif; color: #f4e9cc; }
.side-head > span:not(.badge) { color: var(--muted); font-size: 11px; }
.member { display: flex; align-items: center; gap: 8px; }
.member-name { flex: 1; min-width: 0; }
.member-name strong { display: block; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.member-name small { display: block; color: var(--muted); font-size: 10px; }
.leave { display: grid; gap: 4px; margin-top: 4px; }
.record p { margin: 6px 0; padding-bottom: 6px; border-bottom: 1px solid #2a2622; color: #d2c7b0; font-size: 11px; line-height: 1.45; }

/* dialogs */
.backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 16px; background: #050407cc; }
.backdrop.light { background: #05040799; }
.sheet { width: min(100%, 760px); max-height: 88dvh; overflow-y: auto; padding: 18px 22px; border: 1px solid #6b5631; border-radius: 14px; background: #17151a; }
.sheet header { display: flex; justify-content: space-between; align-items: center; }
.sheet header h2 { margin: 0; font-size: 20px; }
.sheet header button { border: 0; background: none; color: var(--muted); font-size: 24px; cursor: pointer; }
.sheet h3 { margin: 16px 0 6px; color: var(--gold); font: 700 11px 'DM Sans', sans-serif; letter-spacing: 1.4px; text-transform: uppercase; }
.sheet ul { margin: 0; padding-left: 18px; color: #d8ceb6; font-size: 12px; line-height: 1.6; }
.guide { width: min(100%, 420px); display: grid; justify-items: center; gap: 8px; padding: 26px 24px 18px; border: 1px solid #8c6b33; border-radius: 16px; background: #17151a; text-align: center; box-shadow: 0 30px 80px #000; }
.guide-art { width: 84px; height: 84px; display: grid; place-items: center; border-radius: 50%; background: radial-gradient(circle, #3b2a16, #17151a 70%); box-shadow: 0 0 0 2px #8c6b33, 0 0 30px #d8b35f44; }
.guide-art svg { width: 46px; height: 46px; fill: none; stroke: #f2d58b; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.guide-art.castle svg { fill: #f2d58b; stroke: none; }
.guide-art.snow { background: radial-gradient(circle, #1c2a33, #17151a 70%); box-shadow: 0 0 0 2px #4b7083, 0 0 30px #9fd6ea44; }
.guide-art.snow svg { stroke: #cdeefa; }
.guide h2 { margin: 4px 0 0; font-size: 24px; }
.guide-text { margin: 0; color: #e2d6bb; font-size: 15px; line-height: 1.55; }
.guide-dots { display: flex; gap: 6px; margin: 6px 0; }
.guide-dots i { width: 8px; height: 8px; border-radius: 50%; background: #3a3530; }
.guide-dots i.on { background: var(--gold); }
.guide-buttons { display: flex; gap: 8px; }
.fate-card { width: min(100%, 400px); padding: 26px 24px; border: 2px solid #b38a3c; border-radius: 16px; text-align: center;
  background: radial-gradient(circle at 50% 0, #3b2a16, #17151a 70%); box-shadow: 0 30px 80px #000; animation: reveal .6s ease-out; }
.fate-card h2 { margin: 8px 0; font-size: 26px; color: #f6d68e; }
.fate-card p:not(.eyebrow) { color: #e2d6bb; line-height: 1.55; margin-bottom: 18px; }
.fate-card.long-night { border-color: #6aa8c4; background: radial-gradient(circle at 50% 0, #1c3342, #0f1216 72%); }
.fate-card.long-night h2 { color: #cdeefa; text-shadow: 0 0 20px #9fd6ea88; }
.night-sigil { width: 54px; height: 54px; fill: none; stroke: #cdeefa; stroke-width: 1.4; filter: drop-shadow(0 0 12px #9fd6eaaa); }
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

/* ===================================================================================
 * Cinematic theme (shared tokens in src/theme.css), in crimson and steel.
 * =================================================================================== */
.thrones-app {
  --ink: var(--cine-ink); --muted: var(--cine-muted); --panel: var(--cine-panel); --panel-2: var(--cine-panel-2);
  --line: var(--cine-line); --gold: var(--cine-gold);
  background: radial-gradient(120% 80% at 50% 0%, #160d0e 0%, var(--cine-bg) 45%, var(--cine-void) 100%);
  font-family: var(--cine-body);
}
.topbar { border-bottom: 1px solid rgba(179,52,43,.35); background: linear-gradient(180deg, #120c0e, #09080b); box-shadow: 0 1px 0 rgba(0,0,0,.6), 0 10px 30px rgba(0,0,0,.45); }
.brand-mark { border: 1px solid var(--cine-line-strong); border-radius: 50%; background: radial-gradient(circle, #2b1512, #0c0a0d 70%); box-shadow: 0 0 18px rgba(179,52,43,.35), inset 0 0 10px rgba(217,178,106,.15); }
.brand-mark svg { fill: var(--cine-gold-hi); }
.brand strong { font: 700 15px var(--cine-title); letter-spacing: 3px; color: #f3e1b6; }
.brand small { color: var(--cine-dim); letter-spacing: 2.6px; }
.topbar-title { border-left-color: var(--cine-line); color: var(--cine-gold); font: 600 11px var(--cine-title); letter-spacing: 2.6px; }
.ghost-button { border-color: var(--cine-line-strong); background: rgba(217,178,106,.04); color: #e1d3b0; }
.ghost-button:hover { border-color: var(--cine-gold); color: var(--cine-gold-hi); box-shadow: 0 0 12px var(--cine-glow); }
.message.error { border-bottom-color: #7a2f22; background: linear-gradient(90deg, #2a0f0b, #1a0b09); }
.message.notice { border-bottom-color: var(--cine-line-strong); background: linear-gradient(90deg, #1d180f, #120f0b); color: #eadcb2; }
.victory { background: linear-gradient(90deg, #120607, #4a1a14 50%, #120607); color: var(--cine-gold-hi); font: 700 18px var(--cine-title); letter-spacing: 3px; text-shadow: 0 0 18px var(--cine-glow); border-bottom-color: var(--cine-gold-lo); }
.eyebrow { color: #b39a6a; letter-spacing: 2.4px; }
h1, h2 { font-family: var(--cine-title); font-weight: 700; letter-spacing: 1.2px; color: #f4e6c4; }
.hint { color: var(--cine-muted); }
.subtitle { color: #cbbd9c; font: italic 500 19px var(--cine-serif); }
.gold-button { border: 1px solid #f0d390; background: linear-gradient(180deg, #f3d792 0%, #d3a75a 48%, #a7772f 100%); color: #1c1307;
  font: 700 12px var(--cine-title); letter-spacing: 1.6px; text-transform: uppercase;
  box-shadow: inset 0 1px 0 rgba(255,250,225,.65), 0 6px 18px rgba(0,0,0,.45); transition: box-shadow .2s, filter .2s; }
.gold-button:hover:not(:disabled) { filter: brightness(1.06); box-shadow: inset 0 1px 0 rgba(255,250,225,.7), 0 0 22px var(--cine-glow); }
.action-button { border: 1px solid var(--cine-line-strong); background: linear-gradient(180deg, #1d191f, #141117); color: #eadfc6; box-shadow: var(--cine-inset); }
.action-button:hover:not(:disabled) { border-color: var(--cine-gold); box-shadow: var(--cine-inset), 0 0 14px rgba(217,178,106,.18); }
.danger { border-color: #c0443a; background: linear-gradient(180deg, #4e1512, #2a0a09); color: #ffd2c6; }
.link-button { color: var(--cine-gold); }
.join-form input, .host-card input:not([type='range']):not([type='checkbox']), .claim-fields input, .claim-fields select, .power-box select {
  border: 1px solid var(--cine-line-strong); background: rgba(6,5,8,.75); }
.join-form input:focus, .host-card input:focus, .claim-fields input:focus, .claim-fields select:focus { outline: none; border-color: var(--cine-gold); box-shadow: 0 0 0 3px rgba(217,178,106,.14); }
.join-form input { font-family: var(--cine-title); }
.welcome { background: radial-gradient(60% 55% at 50% 45%, rgba(11,10,13,.3), rgba(6,5,8,.93) 75%),
  linear-gradient(180deg, rgba(6,5,8,.5), rgba(6,5,8,.2) 40%, rgba(40,6,6,.55) 100%), url('/map-assets/westeros/map.webp') center / cover; }
.welcome-card, .claim-card { position: relative; border: 1px solid var(--cine-line-strong); border-radius: 4px;
  background: linear-gradient(180deg, rgba(22,15,17,.93), rgba(10,9,12,.96)); box-shadow: 0 30px 90px rgba(0,0,0,.75), inset 0 0 60px rgba(179,52,43,.06);
  animation: cine-rise .6s ease-out both; }
.welcome-card::before { content: ''; position: absolute; inset: 7px; border: 1px solid var(--cine-line); pointer-events: none; }
.welcome-card h1 { font: 800 clamp(30px, 6vw, 42px)/1.05 var(--cine-title); letter-spacing: 3px;
  background: linear-gradient(180deg, #fff1c4 0%, #e3bd70 55%, #9c7230 100%); -webkit-background-clip: text; background-clip: text; color: transparent;
  filter: drop-shadow(0 2px 14px rgba(217,178,106,.25)); }
.crown { fill: var(--cine-gold-hi); filter: drop-shadow(0 0 14px rgba(244,220,156,.45)); }
.saved-button, .forget { border-color: var(--cine-line); background: rgba(20,17,22,.85); }
.lobby { background: radial-gradient(70% 60% at 50% 30%, rgba(11,10,13,.55), rgba(6,5,8,.95) 80%), url('/map-assets/westeros/map.webp') center / cover; }
.lobby-head h1.code { font: 700 50px var(--cine-title); letter-spacing: 12px; color: var(--cine-gold-hi); text-shadow: 0 0 26px var(--cine-glow); }
.house-tile { border: 1px solid var(--cine-line); border-top: 3px solid var(--house); border-radius: 4px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--house) 14%, #121015), #0c0b0e); box-shadow: var(--cine-inset), 0 10px 24px rgba(0,0,0,.4);
  transition: transform .15s, box-shadow .2s, border-color .2s; }
.house-tile:hover:not(.taken):not(.static) { transform: translateY(-2px); box-shadow: var(--cine-inset), 0 14px 30px rgba(0,0,0,.5), 0 0 18px color-mix(in srgb, var(--house) 35%, transparent); }
.house-tile strong { font: 700 15px var(--cine-title); letter-spacing: 1.4px; color: #f3e4be; }
.house-tile.chosen, .house-tile.mine { border-color: var(--cine-gold); box-shadow: 0 0 0 1px var(--cine-gold), 0 0 22px var(--cine-glow); }
.house-rule { color: #ddd0b4; font-family: var(--cine-serif); font-size: 15px; line-height: 1.35; }
.statusbar { border-bottom-color: var(--cine-line); background: linear-gradient(180deg, #110d10, #09080b); }
.turn-card { border-color: var(--cine-line); background: linear-gradient(180deg, #121015, #0b0a0d); }
.tc-head h1 { color: var(--cine-gold-hi); letter-spacing: 1.4px; }
.step-hint { font-family: var(--cine-serif); font-size: 16px; line-height: 1.3; color: #e6dcc2; }
.guide, .drawer, .sheet, .battle-card { border-color: var(--cine-line-strong); }
.guide-text { font-family: var(--cine-serif); font-size: 18px; line-height: 1.35; }
.record p { font-family: var(--cine-serif); font-size: 14px; line-height: 1.3; }
.map-panel { background: var(--cine-void); }
.map-stage { background: #071a24; }
.map-stage::after { content: ''; position: absolute; inset: 0; z-index: 7; pointer-events: none; box-shadow: inset 0 0 140px 40px rgba(4,3,5,.78), inset 0 0 0 1px rgba(217,178,106,.08); }
.place-label { background: rgba(8,7,10,.72); box-shadow: 0 0 0 1px rgba(217,178,106,.1); }
.place-label.capital { font-family: var(--cine-title); letter-spacing: 1.4px; color: var(--cine-gold-hi); text-shadow: 0 0 10px rgba(0,0,0,.9); }
.marker-info, .move-bubble, .toasts p, .zoom-controls, .turn-banner { border-color: var(--cine-line-strong); background: var(--cine-glass); backdrop-filter: blur(6px); }
.backdrop { background: radial-gradient(circle at 50% 45%, rgba(30,6,6,.5), rgba(3,2,4,.88)); backdrop-filter: blur(3px); }
.backdrop.light { background: radial-gradient(circle at 50% 45%, rgba(30,6,6,.35), rgba(3,2,4,.7)); }
.dice i { background: linear-gradient(#fdf4dc, #d8c497); box-shadow: inset 0 -3px 0 rgba(0,0,0,.2), 0 2px 6px rgba(0,0,0,.7); }
.dice i.defender { background: linear-gradient(#dfe7ec, #93a6b2); }
.battle-result { color: #f0e2c0; font: italic 500 18px var(--cine-serif); }

/* mid-size screens: a narrower turn card */
@media (max-width: 1100px) {
  .play-body { grid-template-columns: minmax(0, 1fr) 280px; }
  .goal-label, .goal-dead { display: none; }
}
/* phones: the map on top, the turn card below it */
@media (max-width: 760px) {
  .topbar { gap: 10px; padding: 0 12px; }
  .topbar-title, .brand small, .exit-button { display: none; }
  .brand strong { font-size: 12px; white-space: nowrap; }
  .brand-mark { width: 30px; height: 30px; }
  .statusbar { flex-wrap: wrap; gap: 6px 8px; padding: 6px 10px; }
  .goal { order: 5; flex: 1 1 100%; }
  .chip.season small { display: none; }
  .play-body { display: flex; flex-direction: column; }
  .map-panel { flex: 1 1 55%; }
  .map-stage { display: block; }
  .map-world { position: absolute; top: 0; left: 50%; width: auto; height: 100%; max-width: none; translate: -50% 0; }
  .turn-card { flex: 0 1 45%; border-left: 0; border-top: 1px solid var(--cine-line-strong); padding: 10px 12px; }
  .tc-head .sigil.big, .tc-head .portrait { width: 40px; height: 40px; padding: 6px; }
  .tc-head h1 { font-size: 17px; }
  .toasts { max-width: calc(100% - 24px); bottom: 56px; }
  .claim-fields { grid-template-columns: 1fr; }
  .drawer { width: 100%; }
}
/* Table talk choice on the host's setup screen */
.chat-mode { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 5px; margin-bottom: 12px; }
.chat-mode button { padding: 7px 4px; border: 1px solid var(--cine-line, rgba(217,178,106,.16)); border-radius: 6px; background: rgba(6,5,8,.7); color: #ddd1b6; font: 600 11px var(--cine-title, serif); letter-spacing: .4px; cursor: pointer; text-align: center; }
.chat-mode button small { display: block; margin-top: 2px; color: var(--cine-dim, #6d6557); font: 500 9px var(--cine-body, sans-serif); letter-spacing: 0; }
.chat-mode button.selected { border-color: var(--cine-gold, #d9b26a); background: linear-gradient(#2a2216, #17120c); color: var(--cine-gold-hi, #f4dc9c); box-shadow: 0 0 16px rgba(217,178,106,.16); }
@media (max-width: 860px) {
  .chat-mode { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
