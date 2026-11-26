<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits(['open-map-editor'])
const roster = [
  { id: 'frodo', name: 'Frodo', team: 'Free Peoples', army: 'Hobbits', units: 1, start: 'Hobbiton', initial: 'F' },
  { id: 'theoden', name: 'Théoden', team: 'Free Peoples', army: 'Riders of Rohan', units: 2, start: 'Edoras', initial: 'T' },
  { id: 'aragorn', name: 'Aragorn', team: 'Free Peoples', army: 'Fellowship', units: 3, start: 'Rivendell', initial: 'A' },
  { id: 'khamul', name: 'Khamûl', team: "Sauron's Forces", army: 'Easterlings', units: 1, start: 'Rhûn', initial: 'K' },
  { id: 'saruman', name: 'Saruman', team: "Sauron's Forces", army: 'Uruk-hai', units: 2, start: 'Isengard', initial: 'Sm' },
  { id: 'sauron', name: 'Sauron', team: "Sauron's Forces", army: 'Orcs', units: 3, start: 'Barad-dûr', initial: 'S' },
]
const scaleRatio = 887 / 1774
const currentView = ref('home')
const joinCode = ref('')
const code = ref('')
const playerName = ref('')
const hostName = ref('')
const mapWidthMiles = ref(1000)
const game = ref(null)
const mapPlaces = ref([])
const savedSessions = ref([])
// The seat this tab acts as ("host" or a character id). The secret that proves it lives in
// an HttpOnly cookie the browser sends automatically; scripts here never see it.
const currentSeat = ref('')
const error = ref('')
const notice = ref('')
const selectedSeat = ref('')
const isSubmitting = ref(false)
const movementMode = ref(false)
const destination = ref(null)
const movementPreview = ref(null)
const movementPreviewLoading = ref(false)
const mapStage = ref(null)
const mapWorld = ref(null)
const MIN_MAP_ZOOM = 1
const MAX_MAP_ZOOM = 4
const mapZoom = ref(1)
const mapPan = ref({ x: 0, y: 0 })
const selectedMarkerId = ref('')
let pollTimer = null
let pollInFlight = false
let previewRequestId = 0
let mapPointer = null
let mapPinch = null
const mapPointers = new Map()
let suppressMapClick = false
let suppressMapClickTimer = null
const joinLink = computed(() =>
  `${window.location.host}/?game=${game.value?.code || ''}`)
const hostObjectivePlaces = computed(() =>
  [
    { name: 'The Shire', place: 'Hobbiton', owner: 'Free Peoples' },
    { name: 'Rohan', place: 'Edoras', owner: 'Free Peoples' },
    { name: 'Gondor', place: 'Minas Tirith', owner: 'Free Peoples' },
    { name: 'Rhûn', place: 'Rhûn', owner: "Sauron's Forces" },
    { name: 'Mordor', place: 'Barad-dûr', owner: "Sauron's Forces" },
    { name: 'Isengard', place: 'Isengard', owner: "Sauron's Forces" },
  ].map(objective => {
    const place = mappedPlace(objective.place)
    return place ? { ...place, objectiveName: objective.name } : null
  }).filter(Boolean))
const hostMapMarkers = computed(() => tileMapMarkers([
  ...roster.map(party => {
    const place = hostPartyPlace(party)
    return place ? {
      id: party.id,
      key: `party:${party.id}`,
      kind: 'party',
      party,
      x: place.x,
      y: place.y,
    } : null
  }).filter(Boolean),
  ...hostObjectivePlaces.value.map(city => ({
    id: city.objectiveName,
    key: `city:${city.objectiveName}`,
    kind: 'city',
    city,
    x: city.x,
    y: city.y,
  })),
]))

function defaultCampaignView(gameView) {
  return gameView.ownCharacterId ? 'character' : 'campaign'
}

const isHost = computed(() => Boolean(game.value?.host))
const ownParty = computed(() =>
  game.value?.parties.find(party => party.id === game.value.ownCharacterId) || null)
const activeParty = computed(() =>
  game.value?.parties.find(party => party.id === game.value.activeCharacter) || null)
const isMyTurn = computed(() =>
  Boolean(ownParty.value && activeParty.value?.id === ownParty.value.id && ownParty.value.alive
    && !game.value.winner))
const freeSeats = computed(() =>
  (game.value?.seats || []).filter(seat => seat.team === 'Free Peoples'))
const sauronSeats = computed(() =>
  (game.value?.seats || []).filter(seat => seat.team === "Sauron's Forces"))
const allSeatsTaken = computed(() =>
  Boolean(game.value?.seats.length && game.value.seats.every(seat => seat.connected)))
const visibleParties = computed(() =>
  (game.value?.parties || []).filter(party => party.x !== null && party.alive))
const campaignMapMarkers = computed(() => tileMapMarkers([
  ...(game.value?.objectives || []).map(city => ({
    id: city.id,
    key: `city:${city.id}`,
    kind: 'city',
    city,
    x: city.x,
    y: city.y,
  })),
  ...(game.value ? [{
    id: 'mount-doom',
    key: 'site:mount-doom',
    kind: 'ring-site',
    site: { name: 'Mount Doom' },
    x: game.value.mountDoomX,
    y: game.value.mountDoomY,
  }] : []),
  ...visibleParties.value.map(party => ({
    id: party.id,
    key: `party:${party.id}`,
    kind: 'party',
    party,
    x: party.x,
    y: party.y,
  })),
]))
const visibleDetailPlaces = computed(() =>
  mapPlaces.value.filter(place =>
    (place.tier === 1 && mapZoom.value >= 1.5)
      || (place.tier >= 2 && mapZoom.value >= 2.25)))
const currentPosition = computed(() =>
  ownParty.value && mapPlaces.value.find(place => place.name === closestPlace(ownParty.value.x, ownParty.value.y)))
const contactTargets = computed(() => {
  if (!ownParty.value || !isMyTurn.value) return []
  const defenders = visibleParties.value.filter(party =>
    party.team !== ownParty.value.team && sameMapPoint(ownParty.value.x, ownParty.value.y, party.x, party.y))
  if (defenders.length) {
    return defenders.map(party => ({
      id: party.id,
      label: `${party.name} · ${party.army}`,
      armorClass: party.armorClass,
      kind: 'party',
    }))
  }
  return (game.value?.objectives || [])
    .filter(city => city.owner !== ownParty.value.team
      && sameMapPoint(ownParty.value.x, ownParty.value.y, city.x, city.y))
    .map(city => ({ id: city.id, label: city.name, armorClass: city.armorClass, kind: 'city' }))
})
const canMove = computed(() =>
  isMyTurn.value && !ownParty.value.moved && !ownParty.value.attacked)
const atMountDoom = computed(() =>
  Boolean(ownParty.value && game.value
    && sameMapPoint(ownParty.value.x, ownParty.value.y, game.value.mountDoomX, game.value.mountDoomY)))
const canDestroyRing = computed(() =>
  Boolean(ownParty.value?.id === 'frodo' && game.value?.ringHolder === 'frodo'
    && atMountDoom.value && isMyTurn.value && !game.value.winner))
const playMapWorldStyle = computed(() => ({
  transform: `translate(${mapPan.value.x}px, ${mapPan.value.y}px) scale(${mapZoom.value})`,
}))
const selectedSeatData = computed(() => roster.find(party => party.id === selectedSeat.value))

function sameMapPoint(x1, y1, x2, y2) {
  return x1 !== null && y1 !== null && x2 !== null && y2 !== null
    && Math.hypot(x1 - x2, y1 - y2) < 0.5
}

function closestPlace(x, y) {
  if (x === null || y === null) return ''
  return mapPlaces.value
    .filter(place => place.tier > 0)
    .sort((left, right) =>
      Math.hypot(left.x - x, left.y - y) - Math.hypot(right.x - x, right.y - y))[0]?.name || ''
}

function tileMapMarkers(markers) {
  const clusters = []
  for (const marker of markers) {
    const cluster = clusters.find(items => items.some(item =>
      Math.hypot(marker.x - item.x, (marker.y - item.y) * scaleRatio) < 4.2))
    if (cluster) cluster.push(marker)
    else clusters.push([marker])
  }

  return clusters.flatMap(cluster => {
    const centerX = cluster.reduce((sum, marker) => sum + marker.x, 0) / cluster.length
    const centerY = cluster.reduce((sum, marker) => sum + marker.y, 0) / cluster.length
    const columns = Math.ceil(Math.sqrt(cluster.length))
    const rows = Math.ceil(cluster.length / columns)
    return cluster.map((marker, index) => {
      const row = Math.floor(index / columns)
      const rowLength = Math.min(columns, cluster.length - row * columns)
      const column = index % columns
      const offsetX = (column - (rowLength - 1) / 2) * 36
      const offsetY = (row - (rows - 1) / 2) * 36
      return {
        ...marker,
        style: {
          left: `calc(${centerX}% + ${offsetX}px)`,
          top: `calc(${centerY}% + ${offsetY}px)`,
        },
      }
    })
  })
}

async function apiRequest(url, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  const response = await fetch(url, { ...options, headers })
  const contentType = response.headers.get('content-type') || ''
  const payload = contentType.includes('json') ? await response.json() : await response.text()
  if (!response.ok) {
    const message = payload && typeof payload === 'object'
      ? payload.message || payload.error || payload.detail
      : payload
    throw new Error(message || `Request failed (${response.status})`)
  }
  return payload
}

const SAVED_SEATS_KEY = 'wargames-saved-seats'
const LEGACY_TOKEN_KEY = /^wargames-token-([A-Z0-9]{6})(?:-([a-z]+))?$/

function tabSeatKey(gameCode) {
  return `wargames-tab-seat-${gameCode}`
}

function readSavedSeats() {
  try {
    const seats = JSON.parse(localStorage.getItem(SAVED_SEATS_KEY) || '[]')
    return Array.isArray(seats) ? seats.filter(seat => seat?.code && seat?.seatId) : []
  } catch {
    return []
  }
}

function writeSavedSeats(seats) {
  try {
    localStorage.setItem(SAVED_SEATS_KEY, JSON.stringify(seats))
  } catch {
    // Storage can be unavailable (private mode); the seat cookie still works this session.
  }
  refreshSavedSessions()
}

function refreshSavedSessions() {
  savedSessions.value = readSavedSeats().map(seat => ({
    ...seat,
    name: seat.seatId === 'host' ? 'Host' : roster.find(item => item.id === seat.seatId)?.name || seat.seatId,
  })).sort((left, right) => left.code.localeCompare(right.code) || left.name.localeCompare(right.name))
}

function setGameAddress(gameCode, seatId) {
  const url = new URL(window.location.href)
  url.searchParams.delete('view')
  url.searchParams.set('game', gameCode)
  url.searchParams.set('seat', seatId)
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

function seatOf(gameView) {
  return gameView.host ? 'host' : gameView.ownCharacterId || ''
}

// Adds the seat to this device's saved list. With activate, this tab also switches to it.
function rememberGameSession(gameView, { activate = true } = {}) {
  const seatId = seatOf(gameView)
  if (!seatId) return
  const seats = readSavedSeats().filter(seat => !(seat.code === gameView.code && seat.seatId === seatId))
  writeSavedSeats([...seats, { code: gameView.code, seatId }])
  if (!activate) return
  currentSeat.value = seatId
  try {
    sessionStorage.setItem(tabSeatKey(gameView.code), seatId)
  } catch {
    // Not fatal: the seat is also in the address bar.
  }
  setGameAddress(gameView.code, seatId)
}

function forgetSavedSeat(gameCode, seatId) {
  writeSavedSeats(readSavedSeats().filter(seat => !(seat.code === gameCode && seat.seatId === seatId)))
}

async function forgetSeatOnDevice(session) {
  try {
    await apiRequest(`/api/games/${encodeURIComponent(session.code)}/session`, {
      method: 'DELETE',
      headers: { 'X-Game-Seat': session.seatId },
    })
  } catch {
    // The cookie expires on its own; dropping it from the list is what the player asked for.
  }
  forgetSavedSeat(session.code, session.seatId)
}

function gameHeaders(seatId = currentSeat.value) {
  return seatId ? { 'X-Game-Seat': seatId } : {}
}

// Earlier versions kept each seat's secret in localStorage. Hand each one to the server
// once, which turns it into this device's HttpOnly cookie, then delete the stored copy.
async function migrateLegacySeatTokens() {
  const found = new Map()
  for (const storage of [localStorage, sessionStorage]) {
    try {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index)
        const match = key?.match(LEGACY_TOKEN_KEY)
        const token = match && storage.getItem(key)
        if (token) found.set(token, { code: match[1], keys: [...(found.get(token)?.keys || []), [storage, key]] })
      }
    } catch {
      return
    }
  }
  for (const [token, { code: gameCode, keys }] of found) {
    try {
      const response = await fetch(`/api/games/${encodeURIComponent(gameCode)}/session`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Game-Token': token },
      })
      if (response.ok) rememberGameSession(await response.json(), { activate: false })
      else if (response.status !== 403 && response.status !== 404) continue
    } catch {
      continue // Offline: keep the saved copy and try again next time.
    }
    for (const [storage, key] of keys) storage.removeItem(key)
  }
  refreshSavedSessions()
}

function saveGame(gameView) {
  game.value = gameView
  code.value = gameView.code
  rememberGameSession(gameView)
  error.value = ''
  notice.value = ''
  if (gameView.status === 'campaign') currentView.value = defaultCampaignView(gameView)
  else currentView.value = 'lobby'
  beginPolling()
}

async function loadMapPlaces() {
  const campaigns = await apiRequest('/api/campaigns')
  const campaign = campaigns.find(item => item.slug === 'middle-earth')
  if (!campaign) throw new Error('The Middle-earth map is unavailable.')
  mapPlaces.value = await apiRequest(`/api/places?campaignId=${campaign.id}`)
}

async function hostGame() {
  error.value = ''
  isSubmitting.value = true
  try {
    const response = await apiRequest('/api/games', {
      method: 'POST',
      body: JSON.stringify({ playerName: hostName.value, mapWidthMiles: mapWidthMiles.value }),
    })
    saveGame(response)
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    isSubmitting.value = false
  }
}

async function findGame() {
  error.value = ''
  notice.value = ''
  isSubmitting.value = true
  try {
    const room = await apiRequest(`/api/games/${encodeURIComponent(joinCode.value.trim().toUpperCase())}`)
    if (room.status !== 'lobby') throw new Error('This campaign has already started.')
    game.value = room
    code.value = room.code
    currentView.value = 'seat'
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    isSubmitting.value = false
  }
}

async function joinSeat() {
  if (!selectedSeat.value) {
    error.value = 'Choose an open character seat.'
    return
  }
  error.value = ''
  isSubmitting.value = true
  try {
    const response = await apiRequest(`/api/games/${encodeURIComponent(code.value)}/join`, {
      method: 'POST',
      body: JSON.stringify({ playerName: playerName.value, characterId: selectedSeat.value }),
    })
    saveGame(response)
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    isSubmitting.value = false
  }
}

async function refreshGame() {
  if (!code.value || !currentSeat.value) return
  try {
    const response = await apiRequest(`/api/games/${encodeURIComponent(code.value)}`, {
      headers: gameHeaders(),
    })
    game.value = response
    if (response.status === 'campaign' && !['campaign', 'character'].includes(currentView.value)) {
      currentView.value = defaultCampaignView(response)
    }
    if (response.status === 'lobby') currentView.value = 'lobby'
  } catch (requestError) {
    error.value = requestError.message
    stopPolling()
  }
}

function beginPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (pollInFlight) return
    pollInFlight = true
    await refreshGame()
    pollInFlight = false
  }, 2000)
}

function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = null
}

async function startCampaign() {
  error.value = ''
  isSubmitting.value = true
  try {
    const response = await apiRequest(`/api/games/${encodeURIComponent(code.value)}/start`, {
      method: 'POST',
      headers: gameHeaders(),
    })
    game.value = response
    currentView.value = defaultCampaignView(response)
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    isSubmitting.value = false
  }
}

async function submitAction(action, body) {
  error.value = ''
  isSubmitting.value = true
  previewRequestId += 1
  try {
    const response = await apiRequest(`/api/games/${encodeURIComponent(code.value)}/${action}`, {
      method: 'POST',
      headers: gameHeaders(),
      body: JSON.stringify(body),
    })
    const previousEventCount = game.value?.events.length ?? 0
    game.value = response
    notice.value = ''
    if (['attack', 'destroy-ring'].includes(action)) {
      const newEvents = response.events.slice(previousEventCount)
      notice.value = newEvents.join(' ') || response.events[response.events.length - 1] || 'Action resolved.'
    }
    movementMode.value = false
    destination.value = null
    movementPreview.value = null
    movementPreviewLoading.value = false
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    isSubmitting.value = false
  }
}

async function updateMovementPreview(point) {
  const requestId = ++previewRequestId
  movementPreview.value = null
  if (!ownParty.value || !isMyTurn.value) return
  movementPreviewLoading.value = true
  try {
    const preview = await apiRequest(
      `/api/games/${encodeURIComponent(code.value)}/movement-preview`,
      {
        method: 'POST',
        headers: gameHeaders(),
        body: JSON.stringify({ characterId: ownParty.value.id, x: point.x, y: point.y }),
      },
    )
    if (requestId === previewRequestId) movementPreview.value = preview
  } catch (requestError) {
    if (requestId === previewRequestId) error.value = requestError.message
  } finally {
    if (requestId === previewRequestId) movementPreviewLoading.value = false
  }
}

function startMove() {
  if (!canMove.value) return
  movementMode.value = true
  destination.value = null
  movementPreview.value = null
  notice.value = 'Tap a destination on the map, then confirm the move.'
}

function selectDestination(event) {
  if (suppressMapClick) {
    suppressMapClick = false
    return
  }
  if (!movementMode.value || !mapWorld.value) return
  const bounds = mapWorld.value.getBoundingClientRect()
  const x = Math.max(0, Math.min(100, ((event.clientX - bounds.left) / bounds.width) * 100))
  const y = Math.max(0, Math.min(100, ((event.clientY - bounds.top) / bounds.height) * 100))
  destination.value = { x, y }
  selectedMarkerId.value = ''
  void updateMovementPreview(destination.value)
}

function clampMapZoom(zoom) {
  return Math.max(MIN_MAP_ZOOM, Math.min(MAX_MAP_ZOOM, Number(zoom.toFixed(2))))
}

// The world is centered in the stage and scaled about its own center, so a pan of
// (limit) moves the scaled map's edge exactly to the stage edge.
function clampMapPan(pan, zoom = mapZoom.value) {
  const stage = mapStage.value
  const world = mapWorld.value
  if (!stage || !world) return pan
  const limitX = Math.max(0, (world.offsetWidth * zoom - stage.clientWidth) / 2)
  const limitY = Math.max(0, (world.offsetHeight * zoom - stage.clientHeight) / 2)
  return {
    x: Math.max(-limitX, Math.min(limitX, pan.x)),
    y: Math.max(-limitY, Math.min(limitY, pan.y)),
  }
}

// Offset of a viewport point from the stage center, the origin the pan is measured from.
function stageOffset(clientX, clientY) {
  const bounds = mapStage.value.getBoundingClientRect()
  return { x: clientX - (bounds.left + bounds.width / 2), y: clientY - (bounds.top + bounds.height / 2) }
}

// Zoom so the map point under `anchor` (a stage-center offset) stays under it.
function zoomMapAround(nextZoom, anchor, fromZoom = mapZoom.value, fromPan = mapPan.value, toAnchor = anchor) {
  const zoom = clampMapZoom(nextZoom)
  const ratio = zoom / fromZoom
  mapPan.value = clampMapPan({
    x: toAnchor.x - ratio * (anchor.x - fromPan.x),
    y: toAnchor.y - ratio * (anchor.y - fromPan.y),
  }, zoom)
  mapZoom.value = zoom
}

function changeMapZoom(delta) {
  if (!mapStage.value) {
    mapZoom.value = clampMapZoom(mapZoom.value + delta)
    return
  }
  zoomMapAround(mapZoom.value + delta, { x: 0, y: 0 })
}

function resetMapView() {
  mapZoom.value = 1
  mapPan.value = { x: 0, y: 0 }
}

function suppressNextMapClick() {
  suppressMapClick = true
  window.clearTimeout(suppressMapClickTimer)
  suppressMapClickTimer = window.setTimeout(() => { suppressMapClick = false }, 0)
}

function pinchGeometry() {
  const [first, second] = [...mapPointers.values()]
  return {
    distance: Math.hypot(first.x - second.x, first.y - second.y),
    center: stageOffset((first.x + second.x) / 2, (first.y + second.y) / 2),
  }
}

function startPointerPan(pointerId, point, moved = false) {
  mapPointer = {
    pointerId,
    startX: point.x,
    startY: point.y,
    panX: mapPan.value.x,
    panY: mapPan.value.y,
    moved,
  }
}

function onMapPointerDown(event) {
  if ((event.pointerType === 'mouse' && event.button !== 0)
      || event.target.closest?.('.map-zoom-controls, .map-icon-marker')) return
  mapPointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  try {
    event.currentTarget.setPointerCapture(event.pointerId)
  } catch {
    // The pointer may already be gone; panning still works without capture.
  }
  if (mapPointers.size === 1) {
    startPointerPan(event.pointerId, { x: event.clientX, y: event.clientY })
  } else if (mapPointers.size === 2) {
    const { distance, center } = pinchGeometry()
    mapPinch = { distance, center, zoom: mapZoom.value, pan: { ...mapPan.value } }
    mapPointer = null
  }
}

function onMapPointerMove(event) {
  if (!mapPointers.has(event.pointerId)) return
  mapPointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  if (mapPinch && mapPointers.size >= 2) {
    const { distance, center } = pinchGeometry()
    if (mapPinch.distance > 0) {
      zoomMapAround(mapPinch.zoom * (distance / mapPinch.distance), mapPinch.center,
        mapPinch.zoom, mapPinch.pan, center)
    }
    return
  }
  if (!mapPointer || mapPointer.pointerId !== event.pointerId) return
  const deltaX = event.clientX - mapPointer.startX
  const deltaY = event.clientY - mapPointer.startY
  if (!mapPointer.moved && Math.hypot(deltaX, deltaY) > 6) mapPointer.moved = true
  if (mapPointer.moved) {
    mapPan.value = clampMapPan({ x: mapPointer.panX + deltaX, y: mapPointer.panY + deltaY })
  }
}

function onMapPointerUp(event) {
  if (!mapPointers.has(event.pointerId)) return
  mapPointers.delete(event.pointerId)
  if (mapPinch) {
    // A pinch never selects a destination, including when one finger stays down.
    suppressNextMapClick()
    if (mapPointers.size < 2) mapPinch = null
    if (mapPointers.size === 1) {
      const [[pointerId, point]] = [...mapPointers.entries()]
      startPointerPan(pointerId, point, true)
    } else {
      mapPointer = null
    }
    return
  }
  if (!mapPointer || mapPointer.pointerId !== event.pointerId) return
  if (mapPointer.moved) suppressNextMapClick()
  mapPointer = null
}

function onMapWheel(event) {
  if (!mapStage.value) return
  zoomMapAround(mapZoom.value + (event.deltaY < 0 ? 0.25 : -0.25),
    stageOffset(event.clientX, event.clientY))
}

// Panning is done only with the transform. An overflow-hidden stage can still be scrolled
// (drag selection, focusing an off-screen marker), which would shift the map past the
// clamped edges and offset it from the pan, so the stage's own scroll is pinned at 0.
function resetMapStageScroll(event) {
  event.currentTarget.scrollLeft = 0
  event.currentTarget.scrollTop = 0
}

function onWindowResize() {
  mapPan.value = clampMapPan(mapPan.value)
}

function toggleMapMarker(marker) {
  if (movementMode.value) {
    destination.value = { x: marker.x, y: marker.y }
    void updateMovementPreview(destination.value)
  }
  selectedMarkerId.value = selectedMarkerId.value === marker.key ? '' : marker.key
}

function canAttackMarker(marker) {
  return isMyTurn.value && !ownParty.value?.attacked
    && contactTargets.value.some(target => target.id === marker.id)
}

async function attack(targetId) {
  if (!ownParty.value) return
  await submitAction('attack', { characterId: ownParty.value.id, targetId })
}

async function endTurn() {
  if (!ownParty.value) return
  await submitAction('end-turn', { characterId: ownParty.value.id })
}

async function destroyRing() {
  if (!ownParty.value) return
  await submitAction('destroy-ring', { characterId: ownParty.value.id })
}

async function copyJoinLink() {
  try {
    await navigator.clipboard.writeText(`${window.location.origin}/?game=${game.value.code}`)
    notice.value = 'Join link copied.'
  } catch (copyError) {
    error.value = `Could not copy the join link: ${copyError.message}`
  }
}

async function openSeat(gameCode, seatId) {
  const response = await apiRequest(`/api/games/${encodeURIComponent(gameCode)}`, {
    headers: gameHeaders(seatId),
  })
  code.value = gameCode
  game.value = response
  rememberGameSession(response)
  currentView.value = response.status === 'campaign' ? defaultCampaignView(response) : 'lobby'
  beginPolling()
}

async function resumeSavedSession(session) {
  joinCode.value = session.code
  error.value = ''
  isSubmitting.value = true
  try {
    await openSeat(session.code, session.seatId)
  } catch (loadError) {
    error.value = loadError.message
  } finally {
    isSubmitting.value = false
  }
}

function backHome() {
  stopPolling()
  game.value = null
  code.value = ''
  currentSeat.value = ''
  currentView.value = 'home'
  error.value = ''
  notice.value = ''
  const url = new URL(window.location.href)
  url.searchParams.delete('game')
  url.searchParams.delete('seat')
  url.searchParams.delete('view')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

function partyClass(party) {
  return party.team === 'Free Peoples' ? 'free' : 'dark'
}

function ownerClass(team) {
  return team === 'Free Peoples' ? 'free' : 'dark'
}

function mappedPlace(name) {
  return mapPlaces.value.find(place => place.name === name)
}

async function showGameFromUrl() {
  const params = new URLSearchParams(window.location.search)
  const queryCode = params.get('game')
  if (!queryCode) return
  const normalizedCode = queryCode.toUpperCase()
  joinCode.value = normalizedCode
  currentView.value = 'home'
  let seatId = params.get('seat') || ''
  try {
    seatId ||= sessionStorage.getItem(tabSeatKey(normalizedCode)) || ''
  } catch {
    // No session storage; fall back to the address bar only.
  }
  if (!seatId) return
  try {
    await openSeat(normalizedCode, seatId)
  } catch (loadError) {
    error.value = loadError.message
  }
}

function hostPartyPlace(party) {
  return mappedPlace(party.start)
}

onMounted(async () => {
  refreshSavedSessions()
  window.addEventListener('storage', refreshSavedSessions)
  window.addEventListener('resize', onWindowResize)
  void migrateLegacySeatTokens().then(showGameFromUrl)
  try {
    await loadMapPlaces()
  } catch (loadError) {
    error.value = loadError.message
  }
})

onBeforeUnmount(() => {
  stopPolling()
  window.clearTimeout(suppressMapClickTimer)
  window.removeEventListener('storage', refreshSavedSessions)
  window.removeEventListener('resize', onWindowResize)
})
</script>

<template>
  <main class="game-app">
    <header class="game-topbar">
      <a class="game-brand" href="#" aria-label="War Games home" @click.prevent="backHome">
        <span class="game-brand-mark">B</span>
        <span><strong>WARGAMES</strong><small>FIELD CAMPAIGN</small></span>
      </a>
      <div class="game-topbar-title">
        {{ currentView === 'host' ? 'HOST SETUP' : currentView === 'seat' ? 'JOIN CAMPAIGN' : currentView === 'lobby' ? 'GAME LOBBY' : currentView === 'character' ? `${ownParty?.name || 'CHARACTER'} · ${isMyTurn ? 'YOUR TURN' : 'WAITING'}` : currentView === 'campaign' ? 'CAMPAIGN' : 'JOIN GAME' }}
      </div>
      <div class="game-topbar-actions">
        <span v-if="game" class="game-status"><i></i>{{ game.status === 'lobby' ? 'WAITING' : `ROUND ${game.round}` }}</span>
        <nav v-if="game?.status === 'campaign' && !isHost" class="game-view-switcher" aria-label="Campaign views">
          <button type="button" :class="{ selected: currentView === 'character' }" @click="currentView = 'character'">My character</button>
          <button type="button" :class="{ selected: currentView === 'campaign' }" @click="currentView = 'campaign'">Shared map</button>
        </nav>
        <button class="map-editor-link" type="button" @click="emit('open-map-editor')">Map editor</button>
      </div>
    </header>

    <div v-if="error" class="game-message error-message" role="alert">
      {{ error }}
      <button type="button" aria-label="Dismiss error" @click="error = ''">×</button>
    </div>
    <div v-if="notice" class="game-message notice-message" role="status">
      {{ notice }}
      <button type="button" aria-label="Dismiss message" @click="notice = ''">×</button>
    </div>
    <div v-if="game?.winner" class="victory-banner" role="status">
      {{ game.winner }} win{{ game.winner === 'Free Peoples' ? '' : 's' }} the campaign.
    </div>

    <section v-if="currentView === 'home'" class="welcome-screen">
      <div class="welcome-card">
        <div class="ring-emblem"><span></span></div>
        <p class="eyebrow">MIDDLE-EARTH STRATEGY</p>
        <h1>War of the Ring</h1>
        <p class="welcome-subtitle">Join your campaign</p>
        <form class="join-form" @submit.prevent="findGame">
          <label for="game-code">GAME CODE</label>
          <input id="game-code" v-model="joinCode" maxlength="6" autocomplete="one-time-code" placeholder="Enter code" />
          <button class="gold-button" type="submit" :disabled="isSubmitting || !joinCode.trim()">Join game</button>
        </form>
        <section v-if="savedSessions.length" class="saved-sessions">
          <p class="eyebrow">RESUME SAVED GAME</p>
          <div v-for="session in savedSessions" :key="`${session.code}-${session.seatId}`" class="saved-session-row">
            <button class="saved-session-button" type="button" :disabled="isSubmitting" @click="resumeSavedSession(session)">
              <strong>{{ session.name }}</strong><small>Campaign {{ session.code }}</small>
            </button>
            <button class="saved-session-forget" type="button" :disabled="isSubmitting"
              :aria-label="`Forget ${session.name} in campaign ${session.code} on this device`"
              @click="forgetSeatOnDevice(session)">Forget</button>
          </div>
        </section>
        <div class="welcome-divider"><span>or</span></div>
        <button class="text-button" type="button" @click="currentView = 'host'">Host a game</button>
        <p class="welcome-footnote">Enter the code shared by your host. Saved seats stay available on this device.</p>
      </div>
    </section>

    <section v-else-if="currentView === 'host'" class="host-layout">
      <aside class="host-sidebar">
        <p class="eyebrow">CAMPAIGN SETUP</p>
        <h1>War of the Ring</h1>
        <p class="muted-copy">Map, roster, and starting positions</p>
        <label class="setup-label" for="host-name">HOST NAME</label>
        <input id="host-name" v-model="hostName" maxlength="30" placeholder="Your name" />
        <label class="setup-label" for="map-scale">MAP SCALE</label>
        <div class="scale-input">
          <input id="map-scale" v-model.number="mapWidthMiles" type="number" min="300" max="3000" step="50" />
          <span>miles across</span>
        </div>
        <p class="scale-help">Distance is scaled from the map's 1,774 × 887 coordinate space. This width converts map travel to miles and movement points.</p>
        <div class="roster-group free-group">
          <h2>FREE PEOPLES</h2>
          <div v-for="party in roster.filter(item => item.team === 'Free Peoples')" :key="party.id" class="roster-row">
            <strong>{{ party.name }}</strong><span>{{ party.army }} · {{ party.units }}/{{ party.units }} · {{ party.start }}</span>
          </div>
        </div>
        <div class="roster-group dark-group">
          <h2>SAURON'S FORCES</h2>
          <div v-for="party in roster.filter(item => item.team !== 'Free Peoples')" :key="party.id" class="roster-row">
            <strong>{{ party.name }}</strong><span>{{ party.army }} · {{ party.units }}/{{ party.units }} · {{ party.start }}</span>
          </div>
        </div>
        <p class="host-summary">6 parties · 6 strongholds · 6 army units per team</p>
        <button class="gold-button create-game-button" type="button" :disabled="isSubmitting || !hostName.trim() || !mapPlaces.length" @click="hostGame">
          {{ isSubmitting ? 'Creating lobby…' : 'Create lobby' }}
        </button>
      </aside>
      <section class="setup-map map-stage">
        <div class="game-map-world">
          <img src="/map-assets/middle-earth/map.png" alt="Map of Middle-earth" />
          <span v-for="place in mapPlaces.filter(item => item.tier === 0)" :key="`region-${place.id}`"
            class="region-label" :style="{ left: `${place.x}%`, top: `${place.y}%` }">{{ place.name }}</span>
          <span v-for="marker in hostMapMarkers" :key="marker.key" class="map-icon-marker"
            :class="[`map-icon-${marker.kind}`, { selected: selectedMarkerId === marker.key }]" :style="marker.style">
            <button v-if="marker.kind === 'party'" type="button" class="host-party-pin"
              :class="ownerClass(marker.party.team)" :aria-expanded="selectedMarkerId === marker.key"
              :aria-label="`Show ${marker.party.name} information`" @click.stop="toggleMapMarker(marker)">
              {{ marker.party.initial }}
            </button>
            <button v-else type="button" class="objective-pin" :class="ownerClass(marker.city.owner)"
              :aria-expanded="selectedMarkerId === marker.key"
              :aria-label="`Show ${marker.city.objectiveName} information`" @click.stop="toggleMapMarker(marker)">
              <span class="sr-only">{{ marker.city.objectiveName }}</span>
            </button>
            <div v-if="selectedMarkerId === marker.key" class="map-marker-info">
              <template v-if="marker.kind === 'party'">
                <strong>{{ marker.party.name }}</strong>
                <span>{{ marker.party.army }} · {{ marker.party.units }}/{{ marker.party.units }} units</span>
              </template>
              <template v-else>
                <strong>{{ marker.city.objectiveName }}</strong>
                <span>Controlled by {{ marker.city.owner }}</span>
              </template>
            </div>
          </span>
        </div>
      </section>
    </section>

    <section v-else-if="currentView === 'seat'" class="seat-screen">
      <div class="seat-card">
        <p class="eyebrow">GAME {{ code }}</p>
        <h1>Choose your character</h1>
        <p class="muted-copy">Team and army are fixed to the campaign roster. Your character's location is hidden until the game begins.</p>
        <label class="setup-label" for="player-name">YOUR NAME</label>
        <input id="player-name" v-model="playerName" maxlength="30" placeholder="Player name" />
        <div class="seat-columns">
          <section class="seat-list free-group">
            <h2>FREE PEOPLES</h2>
            <button v-for="seat in freeSeats" :key="seat.id" type="button" class="seat-option"
              :class="{ chosen: selectedSeat === seat.id, unavailable: seat.connected }"
              :disabled="seat.connected" @click="selectedSeat = seat.id">
              <span class="seat-avatar">{{ seat.name[0] }}</span><span class="seat-details"><strong>{{ seat.name }}</strong><small>{{ seat.army }} · {{ seat.initialUnits }} units</small></span>
              <span class="seat-status">{{ seat.connected ? seat.playerName || 'Taken' : 'Open seat' }}</span>
            </button>
          </section>
          <section class="seat-list dark-group">
            <h2>SAURON'S FORCES</h2>
            <button v-for="seat in sauronSeats" :key="seat.id" type="button" class="seat-option"
              :class="{ chosen: selectedSeat === seat.id, unavailable: seat.connected }"
              :disabled="seat.connected" @click="selectedSeat = seat.id">
              <span class="seat-avatar">{{ seat.name[0] }}</span><span class="seat-details"><strong>{{ seat.name }}</strong><small>{{ seat.army }} · {{ seat.initialUnits }} units</small></span>
              <span class="seat-status">{{ seat.connected ? seat.playerName || 'Taken' : 'Open seat' }}</span>
            </button>
          </section>
        </div>
        <button class="gold-button" type="button" :disabled="isSubmitting || !playerName.trim() || !selectedSeatData" @click="joinSeat">Join as {{ selectedSeatData?.name || 'a character' }}</button>
      </div>
    </section>

    <section v-else-if="currentView === 'lobby' && game" class="lobby-screen">
      <h1>Join the campaign</h1>
      <div class="invite-panel">
        <div><label>JOIN LINK</label><strong>{{ joinLink }}</strong><button type="button" @click="copyJoinLink">Copy</button></div>
        <div><label>GAME CODE</label><strong class="invite-code">{{ game.code }}</strong><small>Join on your phone</small></div>
      </div>
      <div class="lobby-teams">
        <section class="lobby-team free-group">
          <h2>FREE PEOPLES</h2>
          <article v-for="seat in freeSeats" :key="seat.id" class="lobby-seat">
            <span class="seat-avatar">{{ seat.name[0] }}</span>
            <span class="seat-details"><strong>{{ seat.name }}</strong><small>{{ seat.army }} · {{ seat.initialUnits }} units</small></span>
            <span class="seat-status"><i :class="{ connected: seat.connected }"></i>{{ seat.connected ? seat.playerName : 'Open seat' }}</span>
          </article>
        </section>
        <section class="lobby-team dark-group">
          <h2>SAURON'S FORCES</h2>
          <article v-for="seat in sauronSeats" :key="seat.id" class="lobby-seat">
            <span class="seat-avatar">{{ seat.name[0] }}</span>
            <span class="seat-details"><strong>{{ seat.name }}</strong><small>{{ seat.army }} · {{ seat.initialUnits }} units</small></span>
            <span class="seat-status"><i :class="{ connected: seat.connected }"></i>{{ seat.connected ? seat.playerName : 'Open seat' }}</span>
          </article>
        </section>
      </div>
      <p class="lobby-footnote">Six parties · 6 army units per team</p>
      <button v-if="isHost" class="gold-button start-campaign-button" type="button" :disabled="isSubmitting || !allSeatsTaken" @click="startCampaign">
        {{ isSubmitting ? 'Starting…' : '⚔　Start campaign' }}
      </button>
      <p v-if="isHost && !allSeatsTaken" class="lobby-hint">Waiting for all six players to take a seat.</p>
      <p v-else-if="!isHost" class="lobby-hint">Waiting for the host to start the campaign.</p>
    </section>

    <template v-else-if="game && ['campaign', 'character'].includes(currentView)">
      <section class="campaign-layout" :class="{ 'character-layout': currentView === 'character' }">
        <aside v-if="currentView === 'campaign'" class="campaign-sidebar">
          <p class="eyebrow">ROUND {{ game.round }}</p>
          <h1>{{ game.activeTeam || 'Campaign' }}</h1>
          <p class="active-party-label">{{ activeParty?.name || 'Turn complete' }} · {{ isMyTurn ? 'Your turn' : 'Active party' }}</p>
          <section class="sidebar-card">
            <h2>FREE PEOPLES</h2>
            <div v-for="party in game.parties.filter(item => item.team === 'Free Peoples')" :key="party.id" class="party-list-row" :class="{ active: party.id === game.activeCharacter }">
              <span>{{ party.name }}</span><small>{{ party.units === null ? 'Hidden' : `${party.units}/${party.initialUnits}` }}</small>
            </div>
          </section>
          <section class="sidebar-card dark-card">
            <h2>SAURON'S FORCES</h2>
            <div v-for="party in game.parties.filter(item => item.team !== 'Free Peoples')" :key="party.id" class="party-list-row" :class="{ active: party.id === game.activeCharacter }">
              <span>{{ party.name }}</span><small>{{ party.units === null ? 'Hidden' : `${party.units}/${party.initialUnits}` }}</small>
            </div>
          </section>
          <section class="sidebar-card objective-list">
            <h2>STRONGHOLD CONTROL</h2>
            <div v-for="city in game.objectives" :key="city.id" class="party-list-row">
              <span>{{ city.name }}</span><small :class="ownerClass(city.owner)">{{ city.owner }}</small>
            </div>
          </section>
          <div class="recent-events">
            <p class="eyebrow">RECENT EVENT</p>
            <p v-for="(event, index) in game.events.slice(-3).reverse()" :key="`${index}-${event}`">{{ event }}</p>
          </div>
        </aside>
        <section class="game-map-panel">
          <div class="campaign-mobile-heading">
            <strong>ROUND {{ game.round }}</strong>
            <span>{{ activeParty?.name || game.activeTeam }} · {{ game.activeTeam }}</span>
          </div>
          <div ref="mapStage" class="map-stage play-map-stage" :class="{ 'move-mode': movementMode }"
            @pointerdown="onMapPointerDown" @pointermove="onMapPointerMove"
            @pointerup="onMapPointerUp" @pointercancel="onMapPointerUp"
            @wheel.prevent="onMapWheel" @scroll="resetMapStageScroll" @click="selectDestination">
            <div ref="mapWorld" class="game-map-world play-map-world" :style="playMapWorldStyle">
              <img src="/map-assets/middle-earth/map.png" alt="Campaign map of Middle-earth" draggable="false" />
              <span v-for="place in mapPlaces.filter(item => item.tier === 0)" :key="place.id" class="region-label"
                :style="{ left: `${place.x}%`, top: `${place.y}%` }">{{ place.name }}</span>
              <span v-for="place in visibleDetailPlaces" :key="`detail:${place.id}`" class="detail-place-label"
                :class="{ landmark: place.tier >= 2 }"
                :style="{ left: `${place.x}%`, top: `${place.y}%` }">{{ place.name }}</span>
              <span v-for="marker in campaignMapMarkers" :key="marker.key" class="map-icon-marker"
                :class="[`map-icon-${marker.kind}`, { selected: selectedMarkerId === marker.key, active: marker.kind === 'party' && marker.party.id === game.activeCharacter, own: marker.kind === 'party' && marker.party.id === game.ownCharacterId }]"
                :style="marker.style">
                <button v-if="marker.kind === 'party'" type="button" class="campaign-party-icon"
                  :class="[partyClass(marker.party), { 'multi-initial': marker.party.id === 'saruman' }]"
                  :aria-expanded="selectedMarkerId === marker.key"
                  :aria-label="`Show ${marker.party.name} information`" @click.stop="toggleMapMarker(marker)">
                  {{ roster.find(item => item.id === marker.party.id)?.initial }}
                </button>
                <button v-else-if="marker.kind === 'ring-site'" type="button" class="ring-site-icon"
                  :aria-expanded="selectedMarkerId === marker.key"
                  aria-label="Show Mount Doom information" @click.stop="toggleMapMarker(marker)">
                  <span aria-hidden="true">⌁</span>
                </button>
                <button v-else type="button" class="stronghold-icon" :class="ownerClass(marker.city.owner)"
                  :aria-expanded="selectedMarkerId === marker.key"
                  :aria-label="`Show ${marker.city.name} information`" @click.stop="toggleMapMarker(marker)">
                  <span class="sr-only">{{ marker.city.name }}</span>
                </button>
                <div v-if="selectedMarkerId === marker.key" class="map-marker-info">
                  <template v-if="marker.kind === 'party'">
                    <strong>{{ marker.party.name }}</strong>
                    <span>{{ marker.party.army }} · {{ marker.party.units === null ? 'Hidden' : `${marker.party.units}/${marker.party.initialUnits}` }} units</span>
                    <button v-if="canAttackMarker(marker)" type="button" class="marker-battle-button" @click.stop="attack(marker.party.id)">
                      Resolve battle
                    </button>
                  </template>
                  <template v-else-if="marker.kind === 'ring-site'">
                    <strong>Mount Doom</strong>
                    <span>The Ring can be destroyed here.</span>
                  </template>
                  <template v-else>
                    <strong>{{ marker.city.name }}</strong>
                    <span>Controlled by {{ marker.city.owner }}</span>
                    <button v-if="canAttackMarker(marker)" type="button" class="marker-battle-button" @click.stop="attack(marker.city.id)">
                      Attack stronghold
                    </button>
                  </template>
                </div>
              </span>
              <span v-if="destination" class="destination-marker" :style="{ left: `${destination.x}%`, top: `${destination.y}%` }"></span>
            </div>
            <div class="map-zoom-controls" role="group" aria-label="Map zoom controls" @click.stop>
              <button type="button" aria-label="Zoom in" :disabled="mapZoom >= 4" @click="changeMapZoom(0.25)">+</button>
              <span>{{ Math.round(mapZoom * 100) }}%</span>
              <button type="button" aria-label="Zoom out" :disabled="mapZoom <= 1" @click="changeMapZoom(-0.25)">−</button>
              <button type="button" aria-label="Reset map view" @click="resetMapView">Reset</button>
            </div>
            <div v-if="movementMode" class="map-instruction">Tap map to choose destination</div>
          </div>
          <div class="map-footer">
            <span>MAP SCALE <b>{{ game.mapWidthMiles.toLocaleString() }} mi</b></span>
            <span>Opponent positions hidden when concealed</span>
            <span class="map-pan-hint">Drag to pan · Zoom to inspect smaller locations</span>
          </div>
          <section v-if="ownParty?.id === 'frodo' && game.ringHolder === 'frodo'"
            class="ring-action-strip" aria-label="Ring objective">
            <div>
              <strong>THE RING</strong>
              <span v-if="atMountDoom">
                {{ isMyTurn
                  ? 'Frodo can destroy the Ring now. Each opponent here first makes one attack on Frodo.'
                  : 'The Ring action is available on Frodo’s turn.' }}
              </span>
              <span v-else>Move Frodo to Mount Doom to unlock the Ring action.</span>
            </div>
            <button class="action-button danger-button" type="button"
              :disabled="isSubmitting || !canDestroyRing" @click="destroyRing">
              Destroy the Ring
            </button>
          </section>
        </section>
        <aside v-if="currentView === 'character'" class="character-sidebar">
          <button class="back-link" type="button" @click="currentView = 'campaign'">← Shared campaign</button>
          <template v-if="ownParty">
            <p class="eyebrow">{{ ownParty.team }}</p>
            <h1>{{ ownParty.name }}</h1>
            <p class="active-party-label">{{ ownParty.army }} · {{ ownParty.units }}/{{ ownParty.initialUnits }} units</p>
            <dl class="character-stats">
              <div><dt>POSITION</dt><dd>{{ currentPosition?.name || `${ownParty.x?.toFixed(1)}, ${ownParty.y?.toFixed(1)}` }}</dd></div>
              <div><dt>MOVEMENT</dt><dd>{{ ownParty.movementRemaining.toFixed(1) }} / {{ ownParty.movementMax }} points</dd></div>
              <div><dt>ATTACK</dt><dd>3d6 + {{ ownParty.attackBoost }}</dd></div>
              <div><dt>ARMOR CLASS</dt><dd>{{ ownParty.armorClass }}</dd></div>
              <div v-if="['frodo', 'sauron'].includes(ownParty.id)"><dt>RING</dt><dd>{{ game.ringHolder === 'frodo' ? 'With Frodo' : game.ringHolder === 'destroyed' ? 'Destroyed' : 'Captured by Sauron' }}</dd></div>
            </dl>
            <div v-if="isMyTurn" class="character-actions">
              <button class="gold-button" type="button" :disabled="!canMove || isSubmitting" @click="startMove">➜　Move</button>
              <div v-if="movementPreview" class="move-preview">
                <strong>{{ movementPreview.miles.toFixed(1) }} map miles</strong>
                <span>Cost {{ movementPreview.movementPoints.toFixed(1) }} points · {{ movementPreview.remaining.toFixed(1) }} left</span>
                <span v-for="segment in movementPreview.segments" :key="`${segment.terrain}-${segment.miles}`">
                  {{ segment.terrain }} · {{ segment.miles.toFixed(1) }} mi · {{ segment.movementPoints.toFixed(1) }} pts
                </span>
                <span v-if="movementPreview.riverCrossings">
                  {{ movementPreview.riverCrossings }} river crossing{{ movementPreview.riverCrossings === 1 ? '' : 's' }} ·
                  {{ movementPreview.riverCrossings }} extra point{{ movementPreview.riverCrossings === 1 ? '' : 's' }}
                </span>
                <button class="gold-button" type="button"
                  :disabled="movementPreview.remaining < -0.0001 || isSubmitting"
                  @click="submitAction('move', { characterId: ownParty.id, x: destination.x, y: destination.y })">
                  Confirm move
                </button>
              </div>
              <p v-else-if="movementPreviewLoading" class="move-preview-loading">Calculating terrain costs…</p>
              <section v-if="contactTargets.length" class="battle-panel" aria-label="Battle actions">
                <p class="attack-title">BATTLE · DIRECT CONTACT</p>
                <p class="battle-explainer">Roll 3d6 plus your attack bonus against the target's armor.</p>
                <button v-for="target in contactTargets" :key="target.id" class="battle-button" type="button"
                  :disabled="ownParty.attacked || isSubmitting" @click="attack(target.id)">
                  <strong>⚔ {{ target.kind === 'city' ? 'Attack stronghold' : 'Resolve battle' }}</strong>
                  <span>{{ target.label }} · 3d6 + {{ ownParty.attackBoost }} vs AC {{ target.armorClass }}</span>
                </button>
              </section>
              <p v-else class="battle-hint">To battle, move onto an enemy party or stronghold. Then resolve the attack here.</p>
              <button class="action-button" type="button" :disabled="isSubmitting" @click="endTurn">Ⅱ　End turn</button>
            </div>
            <p v-else class="turn-notice">{{ ownParty.alive ? `Waiting for ${activeParty?.name || game.activeTeam}'s turn.` : 'This character has been defeated.' }}</p>
            <p v-if="ownParty.id === 'frodo'" class="ring-warning">A successful attack on Frodo captures the Ring for Sauron.</p>
          </template>
        </aside>
      </section>
      <nav v-if="!isHost" class="player-bottom-nav" aria-label="Campaign views">
        <button type="button" :class="{ selected: currentView === 'campaign' }" @click="currentView = 'campaign'">Campaign</button>
        <button type="button" :class="{ selected: currentView === 'character' }" @click="currentView = 'character'">My character</button>
      </nav>
    </template>
  </main>
</template>

<style scoped>
.game-app {
  --ink: #e8eceb;
  --muted: #9ba6a0;
  --panel: #132127;
  --panel-raised: #192b30;
  --line: #33464a;
  --gold: #d6b66f;
  --green: #64a48d;
  --red: #a85b4b;
  height: 100dvh;
  min-height: 540px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--ink);
  background: #101b20;
  font: 13px 'DM Sans', sans-serif;
}
.game-app * { box-sizing: border-box; }
.game-topbar {
  min-height: 60px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 0 20px;
  border-bottom: 1px solid #29383d;
  background: #111d22;
  z-index: 4;
}
.game-brand { display: flex; align-items: center; gap: 10px; color: inherit; text-decoration: none; }
.game-brand-mark { width: 35px; height: 35px; display: grid; place-items: center; border: 1px solid #a68b57; color: var(--gold); font: 700 24px 'Barlow Condensed', sans-serif; }
.game-brand strong { display: block; font: 700 16px 'Barlow Condensed', sans-serif; letter-spacing: 1.5px; }
.game-brand small { display: block; margin-top: 2px; color: #8d9994; font-size: 8px; letter-spacing: 1.5px; }
.game-topbar-title { flex: 1; padding-left: 20px; border-left: 1px solid #324248; color: var(--gold); font-size: 10px; font-weight: 700; letter-spacing: 1.7px; }
.game-topbar-actions { display: flex; align-items: center; gap: 16px; }
.game-view-switcher { display: flex; gap: 4px; padding: 3px; border: 1px solid #34494a; border-radius: 6px; background: #0e1a1f; }
.game-view-switcher button { min-height: 28px; padding: 0 9px; border: 0; border-radius: 4px; background: transparent; color: #aab8ad; font: 600 10px 'DM Sans',sans-serif; cursor: pointer; }
.game-view-switcher button.selected { background: #34443c; color: #e2d096; }
.game-status { display: flex; align-items: center; gap: 8px; color: #aab8ad; font-size: 9px; font-weight: 700; letter-spacing: 1.3px; white-space: nowrap; }
.game-status i,.seat-status i { width: 8px; height: 8px; display: inline-block; border-radius: 50%; background: #687678; }
.game-status i { background: #8bb895; }
.map-editor-link { border: 0; background: transparent; color: #a3b0aa; font: inherit; font-size: 10px; cursor: pointer; }
.game-message { position: relative; z-index: 3; display: flex; justify-content: space-between; padding: 10px 16px; font-size: 12px; }
.game-message button { border: 0; background: transparent; color: inherit; font-size: 18px; cursor: pointer; }
.error-message { border-bottom: 1px solid #a75f50; background: #3c2523; color: #f0c4b8; }
.notice-message { border-bottom: 1px solid #647a62; background: #26342a; color: #d6dfbb; }
.victory-banner { padding: 11px 16px; border-bottom: 1px solid #b2944e; background: #3b3424; color: #f1df9c; font-weight: 700; text-align: center; }
.eyebrow,.setup-label,.welcome-card label,.invite-panel label { display: block; color: #aeb8a9; font-size: 9px; font-weight: 700; letter-spacing: 1.5px; }
.game-app h1 { margin: 8px 0; color: #f2efe4; font: 600 26px/1.1 'Barlow Condensed', sans-serif; }
.muted-copy { color: var(--muted); font-size: 11px; line-height: 1.55; }
.welcome-screen { flex: 1; display: grid; place-items: center; padding: 24px 16px; background: linear-gradient(#101b20b8,#101b20d9), url('/map-assets/middle-earth/map.png') center / cover; }
.welcome-card { width: min(100%, 560px); padding: 32px clamp(22px, 6vw, 78px) 25px; border: 1px solid #927743; border-radius: 17px; background: #142329ec; box-shadow: 0 24px 70px #080f13aa; text-align: center; }
.ring-emblem { width: 48px; height: 48px; display: grid; place-items: center; margin: 0 auto 10px; border: 1px solid var(--gold); border-radius: 50%; }
.ring-emblem span { width: 16px; height: 16px; border: 4px solid var(--gold); border-radius: 50%; box-shadow: 0 0 9px #d6b66f77; }
.welcome-card .eyebrow { margin: 0; color: #b7a56c; }
.welcome-card h1 { margin: 5px 0; color: #e6c982; font: 700 31px 'Barlow Condensed', sans-serif; letter-spacing: 1.1px; text-transform: uppercase; }
.welcome-subtitle { margin: 0 0 22px; color: #b6c0b9; font: 16px Georgia, serif; }
.saved-sessions { display: grid; gap: 7px; margin-top: 20px; text-align: left; }
.saved-sessions>.eyebrow { margin: 0 0 2px; color: #d6b66f; }
.saved-session-button { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 42px; padding: 8px 11px; border: 1px solid #405954; border-radius: 6px; background: #17292c; color: #e2e8de; text-align: left; cursor: pointer; }
.saved-session-button:disabled { opacity: .55; cursor: wait; }
.saved-session-button small { color: #aab8ad; font-size: 9px; }
.saved-session-row { display: grid; grid-template-columns: 1fr auto; gap: 6px; }
.saved-session-forget { min-height: 42px; padding: 0 10px; border: 1px solid #405954; border-radius: 6px; background: transparent; color: #aab8ad; font-size: 10px; cursor: pointer; }
.saved-session-forget:hover { color: #e2e8de; border-color: #6b8580; }
.saved-session-forget:disabled { opacity: .55; cursor: wait; }
.join-form { display: grid; gap: 9px; text-align: left; }
.join-form input,.host-sidebar input,.seat-card input { width: 100%; height: 48px; padding: 0 14px; border: 1px solid #496367; border-radius: 7px; outline: none; background: #0d191e; color: #e9ece5; font: inherit; font-size: 14px; }
.join-form input:focus,.host-sidebar input:focus,.seat-card input:focus { border-color: #b4995d; }
.gold-button { min-height: 45px; border: 1px solid #e4c78c; border-radius: 8px; background: linear-gradient(#e9c980,#aa793c); color: #1d211e; font: 700 13px 'DM Sans',sans-serif; cursor: pointer; }
.gold-button:hover:not(:disabled) { filter: brightness(1.08); }
.gold-button:disabled { opacity: .48; cursor: not-allowed; }
.welcome-divider { display: flex; align-items: center; gap: 12px; margin: 18px 0; color: #94a49a; font-size: 10px; }
.welcome-divider::before,.welcome-divider::after { height: 1px; flex: 1; content: ''; background: #35474a; }
.text-button { border: 0; background: transparent; color: #d9b866; font: 700 12px 'DM Sans',sans-serif; cursor: pointer; }
.welcome-footnote { margin: 13px 0 0; color: #99a59e; font-size: 10px; }
.host-layout { flex: 1; min-height: 0; display: flex; }
.host-sidebar { width: 350px; flex: none; overflow-y: auto; padding: 23px 20px 16px; border-right: 1px solid #2c3b3e; background: #142127; }
.host-sidebar h1 { margin-bottom: 4px; font-size: 29px; }
.host-sidebar .muted-copy { margin: 0 0 22px; }
.setup-label { margin: 15px 0 7px; }
.host-sidebar input { height: 39px; border-radius: 5px; font-size: 12px; }
.scale-input { display: flex; align-items: center; gap: 10px; }
.scale-input input { width: 115px; }
.scale-input span,.scale-help { color: #97a69f; font-size: 10px; }
.scale-help { margin: 7px 0 15px; line-height: 1.5; }
.roster-group { margin-top: 13px; overflow: hidden; border: 1px solid #35675c; border-radius: 8px; background: #10251f; }
.roster-group h2,.lobby-team h2,.seat-list h2 { margin: 0; padding: 11px 13px; font-size: 10px; letter-spacing: 1px; }
.free-group h2,.free-group.seat-list h2 { background: #1b4037; color: #bfd6c9; }
.dark-group { border-color: #72443a; background: #2a201f; }
.dark-group h2 { background: #512e2a; color: #e2b9a2; }
.roster-row { min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 12px; }
.roster-row strong { color: #e4e6dc; font-size: 11px; }
.roster-row span { color: #9dada5; font-size: 9px; text-align: right; }
.host-summary { margin: 14px 0 10px; color: #b0bbb2; font-size: 10px; font-weight: 700; }
.create-game-button { width: 100%; }
.map-stage { position: relative; min-width: 0; overflow: hidden; background: #132128; }
.setup-map { flex: 1; display: grid; place-items: center; padding: 18px; }
.game-map-world { position: relative; width: min(100%, 1600px); aspect-ratio: 1774 / 887; }
.game-map-world>img { display: block; width: 100%; height: 100%; user-select: none; }
.map-icon-marker { position: absolute; z-index: 2; width: 0; height: 0; transform: translate(-50%,-50%); }
.map-icon-city { z-index: 1; }
.map-icon-marker.active { z-index: 4; }
.map-icon-marker.own { z-index: 3; }
.map-icon-marker.selected { z-index: 6; }
.host-party-pin,.campaign-party-icon,.objective-pin,.stronghold-icon { display: grid; place-items: center; padding: 0; cursor: pointer; }
.host-party-pin,.campaign-party-icon { width: 30px; height: 30px; border: 2px solid #e3d39e; border-radius: 50%; background: #428478; color: #fff4d0; box-shadow: 0 2px 6px #101c20; font: 700 11px 'DM Sans',sans-serif; }
.host-party-pin { width: 27px; height: 27px; }
.host-party-pin.dark,.campaign-party-icon.dark { background: #a45749; }
.campaign-party-icon.multi-initial { font-size: 9px; }
.ring-site-icon { width: 23px; height: 23px; display: grid; place-items: center; padding: 0; border: 2px solid #f3df9e; border-radius: 50%; background: #8c492f; color: #fff0b0; box-shadow: 0 0 0 3px #8c492f77,0 2px 6px #101c20; font: 700 17px Georgia,serif; cursor: pointer; }
.map-icon-ring-site { z-index: 2; }
.map-icon-marker.active .campaign-party-icon { outline: 2px solid #f3df9e; outline-offset: 3px; }
.map-icon-marker.own .campaign-party-icon { box-shadow: 0 0 0 5px #f4de7b4a,0 2px 7px #101b20; }
.objective-pin { width: 14px; height: 14px; border: 2px solid #f0dfab; border-radius: 3px; background: #4a9283; }
.objective-pin.dark,.stronghold-icon.dark { background: #a65949; }
.stronghold-icon { width: 15px; height: 15px; border: 2px solid #f3e7c1; border-radius: 3px; background: #458675; box-shadow: 0 1px 4px #101b20; }
.map-icon-marker button:focus-visible { outline: 2px solid #fff0a7; outline-offset: 3px; }
.map-marker-info { position: absolute; top: 19px; left: 0; z-index: 1; display: grid; gap: 4px; min-width: max-content; max-width: 190px; padding: 7px 9px; border: 1px solid #697b6d; border-radius: 5px; background: #101b20f2; color: #e6ebdf; box-shadow: 0 3px 12px #080f13aa; transform: translateX(-50%); pointer-events: auto; }
.map-marker-info strong { font-size: 10px; }
.map-marker-info span { color: #afbeb3; font-size: 8px; white-space: normal; }
.marker-battle-button { min-height: 28px; padding: 4px 7px; border: 1px solid #a68b57; border-radius: 4px; background: #3a3222; color: #ead9a2; font: 600 9px 'DM Sans',sans-serif; cursor: pointer; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; clip-path: inset(50%); }
.seat-screen { flex: 1; min-height: 0; overflow-y: auto; display: grid; place-items: center; padding: 20px; }
.seat-card { width: min(100%, 940px); padding: clamp(20px,4vw,36px); border: 1px solid #445755; border-radius: 13px; background: #142329; }
.seat-card>.eyebrow,.seat-card>h1,.seat-card>.muted-copy { text-align: center; }
.seat-card>.eyebrow { color: #d6b66f; }
.seat-card>.muted-copy { max-width: 520px; margin: 0 auto 20px; }
.seat-card input { max-width: 330px; height: 42px; }
.seat-columns { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; margin: 20px 0; }
.seat-list { overflow: hidden; border: 1px solid #35675c; border-radius: 8px; background: #10251f; }
.seat-option,.lobby-seat { width: 100%; min-height: 62px; display: flex; align-items: center; gap: 11px; padding: 9px 12px; border: 1px solid #326b5d; border-radius: 7px; background: #17292c; color: inherit; text-align: left; }
.seat-option { border-color: transparent; border-radius: 0; border-top-color: #254c43; cursor: pointer; }
.seat-option.chosen { background: #23453b; box-shadow: inset 0 0 0 1px #d4b366; }
.seat-option.unavailable { opacity: .55; cursor: not-allowed; }
.seat-avatar { width: 34px; height: 34px; flex: none; display: grid; place-items: center; border: 1px solid #ba9d5a; border-radius: 50%; background: #33776f; color: #f4e3b5; font-weight: 700; }
.dark-group .seat-avatar,.lobby-team.dark-group .seat-avatar { background: #9d5548; }
.seat-details { min-width: 0; display: grid; gap: 5px; }
.seat-details strong { overflow: hidden; color: #e2e8de; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.seat-details small { color: #9aaba3; font-size: 9px; }
.seat-status { display: flex; align-items: center; gap: 7px; margin-left: auto; color: #aab7b0; font-size: 9px; text-align: right; }
.seat-status i.connected { background: #8fc69f; }
.seat-card>.gold-button { display: block; width: min(100%,400px); margin: 0 auto; }
.lobby-screen { flex: 1; min-height: 0; overflow-y: auto; padding: 16px clamp(14px,4vw,48px) 22px; text-align: center; background: linear-gradient(#0f1b20a8,#0f1b20db), url('/map-assets/middle-earth/map.png') center / cover; }
.lobby-screen>h1 { margin: 4px 0 10px; text-align: center; font: 600 18px Georgia,serif; }
.invite-panel { min-height: 85px; display: grid; grid-template-columns: 1fr 1fr; margin: 0 auto 18px; border: 1px solid #3c5353; border-radius: 10px; background: #122126ec; text-align: left; }
.invite-panel>div { position: relative; display: grid; align-content: center; gap: 6px; padding: 12px 20px; }
.invite-panel>div+div { border-left: 1px solid #35494a; }
.invite-panel strong { color: #dfe5dc; font-size: 13px; }
.invite-panel .invite-code { color: #e2c36e; font-size: 24px; letter-spacing: 1px; }
.invite-panel button { position: absolute; right: 12px; padding: 7px 13px; border: 1px solid #4a6262; border-radius: 6px; background: #1b2a2e; color: #e5e9e2; cursor: pointer; }
.invite-panel small { position: absolute; right: 14px; top: 50%; color: #bac4bd; font-size: 10px; transform: translateY(-50%); }
.lobby-teams { display: grid; grid-template-columns: 1fr 1fr; gap: 22px; text-align: left; }
.lobby-team { min-height: 290px; overflow: hidden; border: 1px solid #35675c; border-radius: 10px; background: #10251fe8; }
.lobby-team.dark-group { border-color: #72443a; background: #2a201fe8; }
.lobby-team h2 { padding: 13px 16px; }
.lobby-seat { min-height: 65px; width: calc(100% - 24px); margin: 9px 12px; padding: 8px 11px; }
.lobby-footnote,.lobby-hint { color: #b6c2b9; font-size: 10px; }
.start-campaign-button { width: min(100%,360px); min-height: 45px; margin-top: 3px; }
.lobby-hint { margin: 8px 0; }
.campaign-layout { flex: 1; min-height: 0; display: grid; grid-template-columns: 250px minmax(0,1fr); }
.campaign-sidebar { display: flex; flex-direction: column; gap: 12px; overflow-y: auto; padding: 19px 13px 12px; border-right: 1px solid #304145; background: #132127; }
.campaign-sidebar h1 { margin: 1px 0 0; font-size: 28px; }
.active-party-label { margin: 0 0 2px; color: #afc0b4; font-size: 10px; }
.sidebar-card { overflow: hidden; border: 1px solid #35675c; border-radius: 8px; background: #11251f; }
.sidebar-card h2 { margin: 0; padding: 8px 10px; background: #1b4037; color: #c3d8cd; font-size: 9px; letter-spacing: 1px; }
.sidebar-card.dark-card { border-color: #72443a; background: #2a201f; }
.sidebar-card.dark-card h2 { background: #512e2a; color: #e2b9a2; }
.party-list-row { min-height: 30px; display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 5px 9px; color: #d1d9d2; font-size: 10px; }
.party-list-row.active { background: #3a4938; }
.party-list-row small { max-width: 65%; color: #9db0a4; font-size: 8px; text-align: right; }
.party-list-row small.free { color: #74b49f; }
.party-list-row small.dark { color: #d09076; }
.objective-list { border-color: #5a5d48; }
.objective-list h2 { background: #393b2f; color: #d1c997; }
.recent-events { margin-top: auto; color: #aebbb2; font-size: 9px; line-height: 1.4; }
.recent-events .eyebrow { margin-bottom: 6px; }
.recent-events p:not(.eyebrow) { margin: 3px 0; }
.game-map-panel { min-width: 0; display: flex; flex-direction: column; background: #131f24; }
.campaign-mobile-heading { display: none; }
.play-map-stage { flex: 1; display: grid; place-items: center; touch-action: none; user-select: none; }
.play-map-stage.move-mode { cursor: crosshair; }
.play-map-world { width: min(100%,1600px); cursor: inherit; transform-origin: center; will-change: transform; }
.play-map-world img { pointer-events: none; }
.map-zoom-controls { position: absolute; z-index: 8; top: 10px; right: 10px; display: grid; grid-template-columns: 38px 55px 38px; gap: 4px; padding: 5px; border: 1px solid #536464; border-radius: 7px; background: #101b20eF; box-shadow: 0 3px 12px #080f1399; }
.map-zoom-controls button { min-height: 34px; border: 1px solid #405456; border-radius: 4px; background: #1d2c30; color: #e6e9df; font: 700 17px 'DM Sans',sans-serif; cursor: pointer; }
.map-zoom-controls button:disabled { opacity: .45; cursor: not-allowed; }
.map-zoom-controls span { display: grid; place-items: center; color: #dbc783; font-size: 9px; font-weight: 700; }
.map-zoom-controls button:last-child { grid-column: 1 / -1; min-height: 27px; font-size: 9px; }
.region-label { position: absolute; z-index: 0; transform: translate(-50%,-50%); color: #fff4d9; font: 700 clamp(10px,1.3vw,20px) 'Barlow Condensed',sans-serif; text-shadow: 0 1px 3px #111,1px 0 3px #111,-1px 0 3px #111; pointer-events: none; }
.detail-place-label { position: absolute; z-index: 1; padding: 1px 3px; border-radius: 3px; background: #101b20b8; color: #f3ead0; font: 600 clamp(7px,.65vw,10px) 'DM Sans',sans-serif; text-shadow: 0 1px 2px #101b20; transform: translate(-50%,-50%); white-space: nowrap; pointer-events: none; }
.detail-place-label.landmark { color: #d3ded5; font-size: clamp(6px,.55vw,8px); font-weight: 500; }
.destination-marker { position: absolute; z-index: 4; width: 18px; height: 18px; border: 3px solid #f2d578; border-radius: 50%; box-shadow: 0 0 0 4px #f2d57855; transform: translate(-50%,-50%); pointer-events: none; }
.map-instruction { position: absolute; top: 12px; left: 50%; padding: 8px 12px; border: 1px solid #62765c; border-radius: 6px; background: #142229ed; color: #f1e4bb; font-size: 11px; transform: translateX(-50%); }
.map-footer { display: flex; justify-content: space-between; gap: 10px; padding: 10px 14px; border-top: 1px solid #2b3b40; color: #a2b0a7; font-size: 9px; }
.map-footer b { margin-left: 5px; color: #d8c17c; }
.map-pan-hint { display: none; }
.ring-action-strip { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 13px; border-top: 1px solid #6b5140; background: #2a2420; }
.ring-action-strip>div { display: grid; gap: 3px; }
.ring-action-strip strong { color: #e5cb7e; font-size: 9px; letter-spacing: 1px; }
.ring-action-strip span { color: #c4c6b5; font-size: 9px; }
.ring-action-strip .action-button { width: auto; min-width: 180px; padding: 0 12px; }
.character-layout { grid-template-columns: minmax(0,1fr) 300px; }
.character-sidebar { overflow-y: auto; padding: 16px 15px; border-left: 1px solid #304145; background: #132127; }
.back-link { margin: 0 0 17px; padding: 5px 0; border: 0; background: transparent; color: #d6b66f; font: 600 11px 'DM Sans',sans-serif; cursor: pointer; }
.character-sidebar h1 { margin: 5px 0; }
.character-stats { margin: 15px 0; padding: 11px 12px; border: 1px solid #35544e; border-radius: 8px; background: #172a2c; }
.character-stats div { display: flex; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px solid #334649; }
.character-stats div:last-child { border: 0; }
.character-stats dt { color: #9fb0a7; font-size: 8px; font-weight: 700; letter-spacing: 1px; }
.character-stats dd { margin: 0; color: #e6e9df; font-size: 10px; text-align: right; }
.character-actions { display: grid; gap: 8px; }
.character-actions .gold-button,.action-button { width: 100%; min-height: 42px; }
.action-button { border: 1px solid #3b5558; border-radius: 7px; background: #1a2a2f; color: #e1e6de; font: 600 11px 'DM Sans',sans-serif; cursor: pointer; }
.action-button:disabled { opacity: .5; cursor: not-allowed; }
.danger-button { border-color: #9b594b; background: #3a2422; color: #f1c1ad; }
.terrain-select { display: grid; gap: 5px; color: #acb7ac; font-size: 9px; }
.terrain-select select { width: 100%; min-height: 38px; padding: 5px 8px; border: 1px solid #455b5d; border-radius: 5px; background: #17262b; color: #e5e9df; font: inherit; }
.move-preview { display: grid; gap: 5px; padding: 9px; border: 1px solid #50624f; border-radius: 6px; background: #22312b; }
.move-preview strong { color: #e0d092; font-size: 11px; }
.move-preview span { color: #aebcb1; font-size: 9px; }
.move-preview .gold-button { margin-top: 4px; }
.move-preview-loading { margin: 0; padding: 8px; border: 1px solid #50624f; border-radius: 6px; color: #c7d0c4; font-size: 9px; }
.attack-title { margin: 5px 0 0; color: #d0bd7d; font-size: 9px; font-weight: 700; letter-spacing: 1px; }
.battle-panel { display: grid; gap: 7px; padding: 9px; border: 1px solid #765742; border-radius: 7px; background: #2b2420; }
.battle-panel .attack-title { margin: 0; color: #e7c38c; }
.battle-explainer,.battle-hint { margin: 0; color: #b6c0b9; font-size: 9px; line-height: 1.4; }
.battle-hint { padding: 8px; border: 1px solid #3b5558; border-radius: 6px; background: #17262b; }
.battle-button { display: grid; gap: 3px; min-height: 42px; padding: 7px 9px; border: 1px solid #765742; border-radius: 5px; background: #3a2b24; color: #f0e2c6; text-align: left; cursor: pointer; }
.battle-button:disabled { opacity: .5; cursor: not-allowed; }
.battle-button strong { font: 700 10px 'DM Sans',sans-serif; }
.battle-button span { color: #d3bda1; font-size: 8px; }
.turn-notice,.ring-warning { padding: 10px; border: 1px solid #4b5a4a; border-radius: 6px; background: #202b25; color: #c9d2bd; font-size: 10px; line-height: 1.45; }
.ring-warning { margin-top: 15px; border-color: #7d4a3f; background: #35211f; color: #e0c3b3; }
.player-bottom-nav { display: none; }
@media (max-width: 760px) {
  .game-app { min-height: 0; }
  .game-topbar { min-height: 53px; gap: 10px; padding: 0 12px; }
  .game-brand { gap: 7px; }
  .game-brand-mark { width: 30px; height: 30px; font-size: 21px; }
  .game-brand strong { font-size: 14px; }
  .game-brand small { font-size: 7px; }
  .game-topbar-title { padding-left: 10px; font-size: 8px; letter-spacing: 1px; }
  .game-topbar-actions { gap: 7px; }
  .game-view-switcher { display: none; }
  .map-editor-link { font-size: 9px; }
  .game-status { gap: 5px; font-size: 8px; }
  .welcome-card { padding: 25px 22px 20px; }
  .host-layout { flex-direction: column; overflow-y: auto; }
  .host-sidebar { width: 100%; flex: none; overflow: visible; padding: 17px 15px; border-right: 0; }
  .host-sidebar .roster-group { display: inline-block; width: calc(50% - 5px); vertical-align: top; }
  .host-sidebar .dark-group { margin-left: 6px; }
  .roster-row { align-items: flex-start; flex-direction: column; gap: 2px; }
  .roster-row span { text-align: left; }
  .setup-map { min-height: 260px; flex: none; padding: 8px; }
  .seat-screen { display: block; padding: 12px; }
  .seat-card { padding: 18px 13px; }
  .seat-columns { grid-template-columns: 1fr; gap: 10px; margin: 14px 0; }
  .seat-status { max-width: 82px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .lobby-screen { padding: 12px 10px 20px; }
  .invite-panel { min-height: 74px; margin-bottom: 12px; }
  .invite-panel>div { padding: 9px; }
  .invite-panel strong { overflow-wrap: anywhere; font-size: 10px; }
  .invite-panel .invite-code { font-size: 19px; }
  .invite-panel button { right: 6px; bottom: 6px; padding: 4px 8px; font-size: 9px; }
  .invite-panel small { position: static; transform: none; font-size: 8px; }
  .lobby-teams { grid-template-columns: 1fr; gap: 10px; }
  .lobby-team { min-height: 0; }
  .lobby-team h2 { padding: 9px 12px; }
  .lobby-seat { min-height: 52px; margin: 5px 7px; width: calc(100% - 14px); gap: 7px; padding: 6px 8px; }
  .lobby-seat .seat-avatar { width: 29px; height: 29px; }
  .lobby-seat .seat-status { max-width: 85px; }
  .campaign-layout,.campaign-layout.character-layout { display: flex; flex: 1; flex-direction: column; min-height: 0; }
  .campaign-sidebar { display: none; }
  .campaign-mobile-heading { min-height: 39px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 12px; color: #e2d096; font-size: 10px; }
  .campaign-mobile-heading span { overflow: hidden; color: #d2dcd3; text-overflow: ellipsis; white-space: nowrap; }
  .game-map-panel { flex: 0 0 min(48vh,390px); min-height: 240px; }
  .play-map-stage { min-height: 0; display: grid; place-items: center; overflow: hidden; overscroll-behavior: contain; touch-action: none; }
  .play-map-world { width: min(100%,1600px); max-width: none; }
  .region-label { font-size: 12px; }
  .campaign-party-icon { width: 26px; height: 26px; font-size: 10px; }
  .stronghold-icon { width: 14px; height: 14px; }
  .map-marker-info { max-width: 150px; padding: 6px 7px; }
  .map-marker-info strong { font-size: 9px; }
  .battle-panel,.battle-hint { grid-column: 1 / -1; }
  .map-footer { padding: 7px 10px; font-size: 8px; }
  .map-footer span:nth-child(2) { display: none; }
  .map-pan-hint { display: inline; }
  .character-sidebar { flex: 1; overflow-y: auto; padding: 10px 14px 14px; border: 0; }
  .character-sidebar .back-link { display: none; }
  .character-sidebar>.eyebrow { margin: 0; }
  .character-sidebar h1 { margin: 3px 0; font-size: 24px; }
  .character-stats { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; margin: 9px 0; padding: 6px 9px; }
  .character-stats div { min-height: 37px; flex-direction: column; justify-content: center; gap: 3px; padding: 4px 0; }
  .character-stats dd { text-align: left; }
  .character-actions { grid-template-columns: 1fr 1fr; gap: 7px; }
  .character-actions .gold-button,.action-button { min-height: 42px; font-size: 10px; }
  .character-actions .move-preview,.character-actions .attack-title { grid-column: 1 / -1; }
  .move-preview .gold-button { grid-column: auto; }
  .ring-action-strip { align-items: stretch; flex-direction: column; gap: 8px; padding: 8px 10px; }
  .ring-action-strip .action-button { width: 100%; min-width: 0; }
  .ring-warning,.turn-notice { margin: 8px 0; padding: 8px; font-size: 9px; }
  .player-bottom-nav { min-height: 53px; display: flex; flex: none; border-top: 1px solid #314247; background: #111e23; padding-bottom: env(safe-area-inset-bottom); }
  .player-bottom-nav button { flex: 1; border: 0; background: transparent; color: #a8b7ae; font: 600 11px 'DM Sans',sans-serif; }
  .player-bottom-nav button.selected { color: #e0c47a; box-shadow: inset 0 2px #c7a660; }
}
@media (max-width: 390px) {
  .game-topbar { gap: 6px; padding: 0 8px; }
  .game-topbar-title { padding-left: 7px; }
  .map-editor-link { max-width: 42px; overflow: hidden; }
  .host-sidebar .roster-group { width: 100%; }
  .host-sidebar .dark-group { margin-left: 0; }
}
</style>
