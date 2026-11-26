<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Gameplay from './Gameplay.vue'

const ART_MAP = { width: 1579, height: 797 }
const TILE_SIZE = 256
const TILE_COLUMNS = [59, 60, 61, 62, 63]
const TILE_ROWS = [113, 114, 115, 116, 117]
const TILE_MAP = {
  width: TILE_SIZE * TILE_COLUMNS.length,
  height: TILE_SIZE * TILE_ROWS.length,
}
const MIDDLE_EARTH_MAP = { width: 1774, height: 887 }
const TERRAIN_TYPES = [
  { value: 'mountain', label: 'Mountains', name: 'Mountain', color: '#bd8b69' },
  { value: 'river', label: 'Rivers', name: 'River', color: '#58a9c7' },
  { value: 'forest', label: 'Forests', name: 'Forest', color: '#77a66b' },
  { value: 'volcanic', label: 'Volcanic', name: 'Volcanic', color: '#c95f45' },
  { value: 'other', label: 'Other', name: 'Terrain', color: '#d1b56c' },
]
const appView = ref(new URLSearchParams(window.location.search).get('view') === 'map' ? 'map' : 'game')
const mapEditorAuthorized = ref(false)
const mapEditorToken = ref('')
const mapEditorTokenInput = ref('')
const mapEditorAuthError = ref('')
const isRestoringMapEditorAccess = ref(false)
const MAP_EDITOR_TOKEN_KEY = 'wargames-map-editor-token'
const campaign = ref(null)
const campaigns = ref([])
const selectedCampaignSlug = ref('book-of-mormon')
const campaignPlaces = ref([])
const referenceRoutes = ref([])
const referenceEvents = ref([])
const apiError = ref('')
const isLoading = ref(false)
const selectedLocation = ref('')
const placing = ref(false)
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const selectedArmy = ref(null)
const mapMode = ref('tiles')
const armies = ref([])
const cesTiles = ref([])
const middleEarthImage = ref(null)
const terrainRegions = ref([])
const terrainType = ref('mountain')
const terrainName = ref('')
const terrainBrushSize = ref(3)
const paintingTerrain = ref(false)
const erasingTerrain = ref(false)
const activeTerrainStroke = ref(null)
const showReferenceEvents = ref(false)
const showCharacterPaths = ref(false)
const selectedEventId = ref(null)
const scaleRevision = ref(0)
const viewport = ref(null)
const canvas = ref(null)
const world = ref(null)
const viewportWidth = ref(0)
const viewportHeight = ref(0)
const baseScale = ref(1)
const draggingMap = ref(false)
const zooming = ref(false)

let pointerStart = null
let movingArmyId = null
let movingArmyStart = null
let zoomTimer = null
let campaignLoadId = 0
let terrainLoadId = 0
let mapEditorInitialized = false

function initializeMapEditor() {
  if (mapEditorInitialized) return
  mapEditorInitialized = true
  measureMap()
  viewport.value.addEventListener('wheel', handleWheel, { passive: false })
  window.addEventListener('resize', measureMap)
  window.addEventListener('keydown', onKeydown)
  void loadCampaignData()
  void loadTerrainRegions()
  if (mapMode.value === 'tiles') loadCesTiles()
}

function openMapEditor() {
  appView.value = 'map'
  void restoreMapEditorAccess()
}

function stopMapEditor() {
  if (!mapEditorInitialized) return
  viewport.value?.removeEventListener('wheel', handleWheel)
  window.removeEventListener('resize', measureMap)
  window.removeEventListener('keydown', onKeydown)
  mapEditorInitialized = false
}

function openGameplay() {
  stopMapEditor()
  appView.value = 'game'
  const url = new URL(window.location.href)
  url.searchParams.delete('view')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

function lockMapEditor() {
  stopMapEditor()
  sessionStorage.removeItem(MAP_EDITOR_TOKEN_KEY)
  mapEditorAuthorized.value = false
  mapEditorToken.value = ''
  mapEditorTokenInput.value = ''
  appView.value = 'game'
  openGameplay()
}

async function authorizeMapEditor() {
  mapEditorAuthError.value = ''
  const token = mapEditorTokenInput.value.trim()
  if (!token) {
    mapEditorAuthError.value = 'Enter the administrator token.'
    return
  }
  try {
    await apiRequest('/api/map-editor/access', {
      method: 'POST',
      headers: { 'X-Map-Editor-Token': token },
    })
    mapEditorToken.value = token
    sessionStorage.setItem(MAP_EDITOR_TOKEN_KEY, token)
    mapEditorAuthorized.value = true
    mapEditorTokenInput.value = ''
    await nextTick()
    initializeMapEditor()
  } catch (error) {
    mapEditorAuthError.value = error.message
  }
}

async function restoreMapEditorAccess() {
  stopMapEditor()
  mapEditorAuthorized.value = false
  mapEditorAuthError.value = ''
  const token = sessionStorage.getItem(MAP_EDITOR_TOKEN_KEY)
  if (!token) return
  isRestoringMapEditorAccess.value = true
  try {
    await apiRequest('/api/map-editor/access', {
      method: 'POST',
      headers: { 'X-Map-Editor-Token': token },
    })
    mapEditorToken.value = token
    mapEditorAuthorized.value = true
    await nextTick()
    initializeMapEditor()
  } catch (error) {
    sessionStorage.removeItem(MAP_EDITOR_TOKEN_KEY)
    mapEditorToken.value = ''
    mapEditorAuthError.value = error.message
  } finally {
    isRestoringMapEditorAccess.value = false
  }
}

function terrainMapKeyForMode(mode) {
  return mode === 'middle-earth' ? 'middle-earth' : 'ces'
}

function terrainColor(type) {
  return TERRAIN_TYPES.find(item => item.value === type)?.color || '#d1b56c'
}

function terrainLabel(type) {
  return TERRAIN_TYPES.find(item => item.value === type)?.label || 'Other'
}

function terrainNamePrefix(type) {
  return TERRAIN_TYPES.find(item => item.value === type)?.name || 'Terrain'
}

function terrainPath(points) {
  if (!points.length) return ''
  const coordinates = points.map(point => ({
    x: point.x * mapSize.value.width / 100,
    y: point.y * mapSize.value.height / 100,
  }))
  const path = coordinates.map((point, index) =>
    `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ')
  if (coordinates.length > 1) return path
  const point = coordinates[0]
  return `${path} L ${point.x} ${point.y}`
}

function terrainStrokeWidth(brushSize) {
  return mapSize.value.height * brushSize / 100
}

function terrainDistanceToStroke(point, region) {
  const scalePoint = item => ({
    x: item.x * mapSize.value.width / 100,
    y: item.y * mapSize.value.height / 100,
  })
  const target = scalePoint(point)
  const points = region.points.map(scalePoint)
  let minimum = Infinity

  for (let index = 0; index < points.length; index += 1) {
    const start = points[index]
    const end = points[Math.min(index + 1, points.length - 1)]
    const dx = end.x - start.x
    const dy = end.y - start.y
    const lengthSquared = dx * dx + dy * dy
    const projection = lengthSquared === 0
      ? 0
      : clamp(((target.x - start.x) * dx + (target.y - start.y) * dy) / lengthSquared, 0, 1)
    const closestX = start.x + projection * dx
    const closestY = start.y + projection * dy
    minimum = Math.min(minimum, Math.hypot(target.x - closestX, target.y - closestY))
  }
  return minimum
}

function campaignSlugForMode(mode) {
  if (mode === 'middle-earth') return 'middle-earth'
  return selectedCampaignSlug.value
}

async function apiRequest(url, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (mapEditorToken.value) headers['X-Map-Editor-Token'] = mapEditorToken.value
  const response = await fetch(url, { ...options, headers })
  if (response.status === 204) return null

  const contentType = response.headers.get('content-type') || ''
  const isJson = contentType.includes('/json') || contentType.includes('+json')
  const result = isJson ? await response.json() : await response.text()
  if (!response.ok) {
    const message = result && typeof result === 'object'
      ? result.message || result.error
      : result
    throw new Error(message || `Request failed (${response.status})`)
  }
  return result
}

function reportApiError(error) {
  apiError.value = error instanceof Error ? error.message : 'The API request failed'
}

function retryData() {
  apiError.value = ''
  void loadCampaignData()
  void loadTerrainRegions()
}

async function loadCampaignData(mode = mapMode.value) {
  const requestId = ++campaignLoadId
  const slug = campaignSlugForMode(mode)
  isLoading.value = true
  apiError.value = ''
  campaign.value = null
  campaignPlaces.value = []
  referenceRoutes.value = []
  referenceEvents.value = []
  armies.value = []

  try {
    const availableCampaigns = await apiRequest('/api/campaigns')
    if (requestId !== campaignLoadId) return
    campaigns.value = availableCampaigns
    const selectedCampaign = campaigns.value.find(item => item.slug === slug)
    if (!selectedCampaign) throw new Error(`Campaign "${slug}" was not found`)

    const query = `?campaignId=${selectedCampaign.id}`
    const [places, routes, events, savedArmies] = await Promise.all([
      apiRequest(`/api/places${query}`),
      apiRequest(`/api/routes${query}`),
      apiRequest(`/api/events${query}`),
      apiRequest(`/api/armies${query}`),
    ])

    if (requestId !== campaignLoadId) return
    campaign.value = selectedCampaign
    campaignPlaces.value = places
    referenceRoutes.value = routes
    referenceEvents.value = events.sort((left, right) =>
      (left.eventNumber ?? 0) - (right.eventNumber ?? 0))
    armies.value = savedArmies
  } catch (error) {
    if (requestId !== campaignLoadId) return
    reportApiError(error)
  } finally {
    if (requestId === campaignLoadId) isLoading.value = false
  }
}

async function loadTerrainRegions(mode = mapMode.value) {
  const requestId = ++terrainLoadId
  const mapKey = terrainMapKeyForMode(mode)
  terrainRegions.value = []

  try {
    const regions = await apiRequest(
      `/api/terrain-regions?mapKey=${encodeURIComponent(mapKey)}&mapLayer=${encodeURIComponent(mode)}`,
    )
    if (requestId !== terrainLoadId) return
    terrainRegions.value = regions
  } catch (error) {
    if (requestId === terrainLoadId) reportApiError(error)
  }
}

const mapPlaces = computed(() =>
  campaignPlaces.value.filter(place => place.mapLayer === mapMode.value))
const artworkPlaces = computed(() =>
  campaignPlaces.value.filter(place => place.mapLayer === 'artwork'))
const bookCampaigns = computed(() =>
  campaigns.value.filter(item => item.setting === 'Book of Mormon'))
const locationOptions = computed(() => {
  const places = mapMode.value === 'artwork' ? artworkPlaces.value : mapPlaces.value
  return places.filter((place, index, list) =>
    list.findIndex(item => item.name === place.name) === index)
})
const selectedReferenceEvent = computed(() =>
  referenceEvents.value.find(event => event.id === selectedEventId.value) || null,
)
const displayRoutes = computed(() => referenceRoutes.value.map(route => ({
  ...route,
  path: route.points.map((point, index) =>
    `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' '),
})))
const blueArmies = computed(() => armies.value.filter(army => army.side !== 'red'))
const redArmies = computed(() => armies.value.filter(army => army.side === 'red'))
const mapSize = computed(() => {
  if (mapMode.value === 'middle-earth') return MIDDLE_EARTH_MAP
  return mapMode.value === 'tiles' ? TILE_MAP : ART_MAP
})
const totalScale = computed(() => baseScale.value * zoom.value)
const zoomLabel = computed(() => `${Math.round(zoom.value * 100)}%`)
const fitOffset = computed(() => ({
  x: (viewportWidth.value - mapSize.value.width * baseScale.value) / 2,
  y: (viewportHeight.value - mapSize.value.height * baseScale.value) / 2,
}))
const worldStyle = computed(() => ({
  width: `${mapSize.value.width}px`,
  height: `${mapSize.value.height}px`,
  transformOrigin: '0 0',
  transform: `translate(${fitOffset.value.x + pan.value.x}px, ${fitOffset.value.y + pan.value.y}px) scale(${totalScale.value})`,
}))
const mapRect = computed(() => ({
  x: fitOffset.value.x + pan.value.x,
  y: fitOffset.value.y + pan.value.y,
  width: mapSize.value.width * totalScale.value,
  height: mapSize.value.height * totalScale.value,
}))

function measureMap() {
  if (!viewport.value) return
  viewportWidth.value = viewport.value.clientWidth
  viewportHeight.value = viewport.value.clientHeight
  baseScale.value = Math.min(
    viewportWidth.value / mapSize.value.width,
    viewportHeight.value / mapSize.value.height,
  )
  scaleRevision.value += 1
}

function mapPoint(event) {
  const bounds = viewport.value.getBoundingClientRect()
  return {
    x: ((event.clientX - bounds.left - mapRect.value.x) / mapRect.value.width) * 100,
    y: ((event.clientY - bounds.top - mapRect.value.y) / mapRect.value.height) * 100,
  }
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function setZoom(nextZoom, anchor = null) {
  const boundedZoom = clamp(nextZoom, 0.65, 4)
  const viewportBounds = viewport.value?.getBoundingClientRect()
  if (!anchor && viewportBounds) {
    anchor = { x: viewportBounds.width / 2, y: viewportBounds.height / 2 }
  }
  const worldBounds = world.value?.getBoundingClientRect()
  const oldScale = worldBounds && mapSize.value.width
    ? worldBounds.width / mapSize.value.width
    : totalScale.value
  const newScale = baseScale.value * boundedZoom

  if (anchor && oldScale > 0 && viewportBounds) {
    const oldOriginX = worldBounds.left - viewportBounds.left
    const oldOriginY = worldBounds.top - viewportBounds.top
    const mapXAtCursor = (anchor.x - oldOriginX) / oldScale
    const mapYAtCursor = (anchor.y - oldOriginY) / oldScale
    pan.value = {
      x: anchor.x - mapXAtCursor * newScale - fitOffset.value.x,
      y: anchor.y - mapYAtCursor * newScale - fitOffset.value.y,
    }
  }

  zoom.value = boundedZoom
  scaleRevision.value += 1
  zooming.value = true
  window.clearTimeout(zoomTimer)
  zoomTimer = window.setTimeout(() => { zooming.value = false }, 180)
}

function handleWheel(event) {
  event.preventDefault()
  event.stopPropagation()
  const bounds = viewport.value.getBoundingClientRect()
  setZoom(zoom.value * (event.deltaY < 0 ? 1.12 : 0.89), {
    x: event.clientX - bounds.left,
    y: event.clientY - bounds.top,
  })
}

function onPointerDown(event) {
  if (event.button !== 0) return
  if (event.target.closest?.('.map-tools')) return
  window.clearTimeout(zoomTimer)
  zooming.value = false
  if (placing.value) {
    const point = mapPoint(event)
    if (point.x < 0 || point.x > 100 || point.y < 0 || point.y > 100) return
    void createArmy(point)
    return
  }
  if (erasingTerrain.value) {
    const point = mapPoint(event)
    if (point.x < 0 || point.x > 100 || point.y < 0 || point.y > 100) return
    const hitTolerance = 12 / totalScale.value
    const region = [...terrainRegions.value].reverse().find(item =>
      terrainDistanceToStroke(point, item)
        <= terrainStrokeWidth(item.brushSize) / 2 + hitTolerance)
    if (region) void removeTerrainRegion(region)
    return
  }
  if (paintingTerrain.value) {
    const point = mapPoint(event)
    if (point.x < 0 || point.x > 100 || point.y < 0 || point.y > 100) return
    const type = terrainType.value
    const regionNumber = terrainRegions.value.filter(region => region.terrainType === type).length + 1
    activeTerrainStroke.value = {
      mapKey: terrainMapKeyForMode(mapMode.value),
      mapLayer: mapMode.value,
      name: terrainName.value.trim() || `${terrainNamePrefix(type)} ${regionNumber}`,
      terrainType: type,
      brushSize: Number(terrainBrushSize.value),
      points: [point],
    }
    viewport.value.setPointerCapture(event.pointerId)
    event.preventDefault()
    return
  }

  pointerStart = {
    x: event.clientX,
    y: event.clientY,
    panX: pan.value.x,
    panY: pan.value.y,
  }
  viewport.value.setPointerCapture(event.pointerId)
  draggingMap.value = true
}

function onPointerMove(event) {
  if (movingArmyId !== null) {
    const point = mapPoint(event)
    const army = armies.value.find(item => item.id === movingArmyId)
    if (army) {
      army.x = clamp(point.x, 0, 100)
      army.y = clamp(point.y, 0, 100)
    }
    return
  }
  if (activeTerrainStroke.value) {
    const points = activeTerrainStroke.value.points
    if (points.length >= 2000) return
    const point = mapPoint(event)
    const boundedPoint = { x: clamp(point.x, 0, 100), y: clamp(point.y, 0, 100) }
    const previous = points[points.length - 1]
    if (Math.hypot(boundedPoint.x - previous.x, boundedPoint.y - previous.y) >= 0.1) {
      points.push(boundedPoint)
    }
    return
  }
  if (!pointerStart) return
  pan.value = {
    x: pointerStart.panX + event.clientX - pointerStart.x,
    y: pointerStart.panY + event.clientY - pointerStart.y,
  }
}

async function onPointerUp() {
  if (activeTerrainStroke.value) {
    const stroke = activeTerrainStroke.value
    activeTerrainStroke.value = null
    await saveTerrainStroke(stroke)
    return
  }

  const armyId = movingArmyId
  const start = movingArmyStart
  pointerStart = null
  movingArmyId = null
  movingArmyStart = null
  draggingMap.value = false

  if (armyId === null || !start) return
  const army = armies.value.find(item => item.id === armyId)
  if (!army || (army.x === start.x && army.y === start.y)) return

  try {
    const savedArmy = await apiRequest(`/api/armies/${armyId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(armyRequestBody(army)),
    })
    Object.assign(army, savedArmy)
  } catch (error) {
    army.x = start.x
    army.y = start.y
    reportApiError(error)
  }
}

function onPointerCancel() {
  if (activeTerrainStroke.value) {
    activeTerrainStroke.value = null
    return
  }
  void onPointerUp()
}

async function saveTerrainStroke(stroke) {
  try {
    const region = await apiRequest('/api/terrain-regions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(stroke),
    })
    terrainRegions.value = [...terrainRegions.value, region]
    terrainName.value = ''
    apiError.value = ''
  } catch (error) {
    reportApiError(error)
  }
}

function toggleTerrainPainting() {
  if (paintingTerrain.value) {
    paintingTerrain.value = false
    activeTerrainStroke.value = null
    return
  }
  placing.value = false
  erasingTerrain.value = false
  selectedArmy.value = null
  paintingTerrain.value = true
}

function toggleTerrainErasing() {
  erasingTerrain.value = !erasingTerrain.value
  if (erasingTerrain.value) {
    placing.value = false
    paintingTerrain.value = false
    activeTerrainStroke.value = null
    selectedArmy.value = null
  }
}

function toggleArmyPlacement() {
  placing.value = !placing.value
  if (placing.value) {
    paintingTerrain.value = false
    erasingTerrain.value = false
    activeTerrainStroke.value = null
  }
  selectedArmy.value = null
}

async function removeTerrainRegion(region) {
  try {
    await apiRequest(`/api/terrain-regions/${region.id}`, { method: 'DELETE' })
    terrainRegions.value = terrainRegions.value.filter(item => item.id !== region.id)
    apiError.value = ''
  } catch (error) {
    reportApiError(error)
  }
}

function beginArmyMove(event, army) {
  event.stopPropagation()
  if (placing.value || paintingTerrain.value) return
  selectedArmy.value = army.id
  movingArmyId = army.id
  movingArmyStart = { x: army.x, y: army.y }
  viewport.value.setPointerCapture(event.pointerId)
}

function armyRequestBody(army) {
  return {
    campaignId: campaign.value.id,
    name: army.name,
    faction: army.faction,
    side: army.side,
    x: army.x,
    y: army.y,
    unitCount: army.unitCount,
    description: army.description,
  }
}

async function createArmy(point) {
  if (!campaign.value) {
    apiError.value = 'Campaign data is not loaded'
    return
  }
  const faction = mapMode.value === 'middle-earth' ? 'Free Peoples' : 'Nephites'
  try {
    const army = await apiRequest('/api/armies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        campaignId: campaign.value.id,
        name: `${faction} Army ${armies.value.length + 1}`,
        faction,
        side: 'blue',
        ...point,
        unitCount: 1,
      }),
    })
    armies.value.push(army)
    selectedArmy.value = army.id
    placing.value = false
    apiError.value = ''
  } catch (error) {
    reportApiError(error)
  }
}

async function removeSelectedArmy() {
  if (selectedArmy.value === null) return
  const armyId = selectedArmy.value
  try {
    await apiRequest(`/api/armies/${armyId}`, { method: 'DELETE' })
    armies.value = armies.value.filter(army => army.id !== armyId)
    selectedArmy.value = null
    apiError.value = ''
  } catch (error) {
    reportApiError(error)
  }
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    placing.value = false
    paintingTerrain.value = false
    erasingTerrain.value = false
    activeTerrainStroke.value = null
  }
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedArmy.value) {
    removeSelectedArmy()
  }
}

function goToLocation(name = selectedLocation.value) {
  const places = mapMode.value === 'artwork'
    ? campaignPlaces.value.filter(item => item.mapLayer === 'artwork')
    : mapPlaces.value
  const location = places.find(item => item.name === name && item.tier > 0)
    || places.find(item => item.name === name)
  if (!location) return
  const nextZoom = clamp(Math.max(zoom.value, 1.75), 0.65, 4)
  const newScale = baseScale.value * nextZoom
  pan.value = {
    x: viewportWidth.value / 2 - location.x / 100 * mapSize.value.width * newScale - fitOffset.value.x,
    y: viewportHeight.value / 2 - location.y / 100 * mapSize.value.height * newScale - fitOffset.value.y,
  }
  zoom.value = nextZoom
  scaleRevision.value += 1
}

function displayName(location) {
  return location.name
}

function tilePlaceName(place) {
  return place.name
}

function tileLabelOpacity(place) {
  if (place.tier === 0) {
    const hasDetailedLabel = mapPlaces.value.some(other => other.name === place.name && other.tier > 0)
    if (!hasDetailedLabel) return 1
    return 1 - clamp((zoom.value - 1.08) / 0.38, 0, 1)
  }
  const startZoom = place.tier === 1 ? 1.08 : 1.65
  const fadeRange = place.tier === 1 ? 0.38 : 0.5
  return clamp((zoom.value - startZoom) / fadeRange, 0, 1)
}

function tileLabelStyle(place) {
  const screenFontSize = place.tier === 0 ? 22 : place.tier === 1 ? 15 : 12
  return {
    left: `${place.x}%`,
    top: `${place.y}%`,
    opacity: tileLabelOpacity(place),
    fontSize: `${screenFontSize / totalScale.value}px`,
  }
}

function referenceMarkerStyle(event) {
  return {
    left: `${event.x}%`,
    top: `${event.y}%`,
    width: `${24 / totalScale.value}px`,
    height: `${24 / totalScale.value}px`,
    fontSize: `${12 / totalScale.value}px`,
  }
}

function toggleMapMode() {
  if (mapMode.value === 'middle-earth') {
    selectMapMode('tiles')
    return
  }
  selectMapMode(mapMode.value === 'artwork' ? 'tiles' : 'artwork')
}

function selectMapMode(mode) {
  const previousMode = mapMode.value
  if (previousMode === mode) return
  const previousCampaign = campaignSlugForMode(previousMode)
  mapMode.value = mode
  selectedLocation.value = ''
  selectedEventId.value = null
  selectedArmy.value = null
  placing.value = false
  paintingTerrain.value = false
  erasingTerrain.value = false
  activeTerrainStroke.value = null
  zoom.value = 1
  pan.value = { x: 0, y: 0 }
  if (previousCampaign !== campaignSlugForMode(mode)) {
    void loadCampaignData(mode)
  }
  nextTick(measureMap)
}

function selectCampaign(slug) {
  if (selectedCampaignSlug.value === slug || mapMode.value === 'middle-earth') return
  selectedCampaignSlug.value = slug
  selectedLocation.value = ''
  selectedArmy.value = null
  void loadCampaignData()
}

function loadMiddleEarthMap() {
  if (middleEarthImage.value) return
  const image = new Image()
  image.onload = () => {
    middleEarthImage.value = image
    scaleRevision.value += 1
  }
  image.src = '/map-assets/middle-earth/map.png'
}

async function loadCesTiles() {
  try {
    const response = await fetch('/map-assets/ces/manifest.json')
    if (!response.ok) throw new Error('CES tile manifest is not available')
    const manifest = await response.json()
    const loaded = await Promise.all((manifest.tiles || []).map(tile => new Promise(resolve => {
      const image = new Image()
      image.onload = () => resolve({ ...tile, image })
      image.onerror = () => resolve(null)
      image.src = tile.src
    })))
    cesTiles.value = loaded.filter(Boolean)
  } catch {
    cesTiles.value = []
  }
}

function drawTileCanvas() {
  if (mapMode.value !== 'tiles' && mapMode.value !== 'middle-earth') return
  if (!canvas.value) return
  const target = canvas.value
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  const renderScale = clamp(baseScale.value * zoom.value * dpr, 1, 3)
  target.width = Math.ceil(mapSize.value.width * renderScale)
  target.height = Math.ceil(mapSize.value.height * renderScale)

  const context = target.getContext('2d', { alpha: true })
  if (!context) return

  context.imageSmoothingEnabled = true
  context.imageSmoothingQuality = 'high'
  context.setTransform(renderScale, 0, 0, renderScale, 0, 0)
  context.clearRect(0, 0, mapSize.value.width, mapSize.value.height)

  if (mapMode.value === 'middle-earth') {
    if (middleEarthImage.value) {
      context.drawImage(middleEarthImage.value, 0, 0, mapSize.value.width, mapSize.value.height)
    }
    return
  }

  const orderedTiles = [...cesTiles.value].sort((a, b) => a.z - b.z)
  for (const tile of orderedTiles) {
    const tileScale = 2 ** (tile.z - 8)
    const tileExtent = TILE_SIZE / tileScale
    const left = tile.x * tileExtent - TILE_COLUMNS[0] * TILE_SIZE
    const top = tile.y * tileExtent - TILE_ROWS[0] * TILE_SIZE
    context.drawImage(tile.image, left, top, tileExtent, tileExtent)
  }
}

watch(mapMode, async mode => {
  if (mode === 'tiles') await loadCesTiles()
  if (mode === 'middle-earth') loadMiddleEarthMap()
  void loadTerrainRegions(mode)
})

watch([mapMode, cesTiles, zoom, scaleRevision], async () => {
  await nextTick()
  drawTileCanvas()
})

onMounted(() => {
  if (appView.value === 'map') void restoreMapEditorAccess()
})

onBeforeUnmount(() => {
  window.clearTimeout(zoomTimer)
  stopMapEditor()
})
</script>

<template>
  <Gameplay v-if="appView === 'game'" @open-map-editor="openMapEditor" />
  <main v-else-if="!mapEditorAuthorized" class="map-editor-gate">
    <form class="map-editor-gate-card" @submit.prevent="authorizeMapEditor">
      <p class="section-eyebrow">RESTRICTED VIEW</p>
      <h1>Map editor</h1>
      <p>Administrator access is required to view or change campaign map data.</p>
      <label for="map-editor-token">ADMINISTRATOR TOKEN</label>
      <input id="map-editor-token" v-model="mapEditorTokenInput" type="password"
        autocomplete="current-password" :disabled="isRestoringMapEditorAccess" />
      <p v-if="mapEditorAuthError" class="map-editor-auth-error" role="alert">{{ mapEditorAuthError }}</p>
      <button class="map-editor-auth-button" type="submit"
        :disabled="isRestoringMapEditorAccess || !mapEditorTokenInput.trim()">
        {{ isRestoringMapEditorAccess ? 'Checking access…' : 'Unlock map editor' }}
      </button>
      <button class="map-editor-back-button" type="button" @click="openGameplay">Return to campaign play</button>
    </form>
  </main>
  <main v-else class="app-shell">
    <header class="topbar">
      <a class="brand" href="#" aria-label="Wargames home">
        <span class="brand-mark">W</span>
        <span class="brand-copy"><strong>WARGAMES</strong><small>FIELD MAP</small></span>
      </a>
      <div class="topbar-right">
        <span class="status-dot"></span><span class="status-text">SANDBOX</span>
        <button class="map-editor-link" type="button" @click="openGameplay">Campaign play</button>
        <button class="map-editor-link" type="button" @click="lockMapEditor">Lock editor</button>
        <button class="profile" aria-label="Player profile">JB</button>
      </div>
    </header>

    <section class="map-layout">
      <aside class="sidebar">
        <div v-if="apiError" class="api-error" role="alert">
          {{ apiError }}
          <button @click="retryData">Retry</button>
        </div>
        <div v-else-if="isLoading" class="api-status">Loading campaign data…</div>
        <div class="section-eyebrow">THEATER OF OPERATIONS</div>
        <h1>{{ campaign?.name || (mapMode === 'middle-earth' ? 'Middle-earth' : 'Land of Zarahemla') }}</h1>
        <p class="intro">{{ campaign?.description || 'A strategic campaign map.' }}</p>
        <div class="divider"></div>
        <div class="field-label name-label">MAP WORLD</div>
        <div class="select-wrap">
          <select
            :value="mapMode === 'middle-earth' ? 'middle-earth' : 'tiles'"
            aria-label="Choose map world"
            @change="selectMapMode($event.target.value)"
          >
            <option value="tiles">CES Lands</option>
            <option value="middle-earth">Middle-earth</option>
          </select>
          <span aria-hidden="true">⌄</span>
        </div>
        <div class="divider"></div>
        <template v-if="mapMode !== 'middle-earth'">
          <div class="field-label name-label">CAMPAIGN VARIANT</div>
          <div class="select-wrap">
            <select
              :value="selectedCampaignSlug"
              aria-label="Choose Book of Mormon campaign"
              @change="selectCampaign($event.target.value)"
            >
              <option
              v-for="option in bookCampaigns"
              :key="option.id"
              :value="option.slug"
              :title="option.description"
              >{{ option.slug === 'book-of-mormon' ? 'Original' : option.name }}</option>
            </select>
            <span aria-hidden="true">⌄</span>
          </div>
        </template>
        <div class="divider"></div>
        <label class="field-label" for="location-select">JUMP TO LOCATION</label>
        <div class="select-wrap">
          <select id="location-select" v-model="selectedLocation" @change="goToLocation()">
            <option value="" disabled>Select a location</option>
            <option v-for="location in locationOptions" :key="location.id" :value="location.name">{{ displayName(location) }}</option>
          </select>
          <span aria-hidden="true">⌄</span>
        </div>

        <div class="divider"></div>
        <div class="section-row">
          <span class="field-label">TERRAIN REGIONS</span>
          <span class="count-pill">{{ terrainRegions.length }}</span>
        </div>
        <label class="terrain-field">
          Region name
          <input v-model="terrainName" maxlength="100" placeholder="Optional, e.g. Northern Forest" />
        </label>
        <label class="terrain-field">
          Terrain type
          <div class="select-wrap">
            <select v-model="terrainType" aria-label="Terrain type">
              <option v-for="type in TERRAIN_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
            </select>
            <span aria-hidden="true">⌄</span>
          </div>
        </label>
        <label class="terrain-field">
          <span class="terrain-field-heading">Brush size <strong>{{ terrainBrushSize }}%</strong></span>
          <input v-model.number="terrainBrushSize" type="range" min="0.5" max="15" step="0.5" aria-label="Brush size" />
        </label>
        <button
          class="place-button"
          :class="{ active: paintingTerrain }"
          :disabled="!campaign || isLoading"
          @click="toggleTerrainPainting"
        >{{ paintingTerrain ? 'Finish painting' : 'Paint terrain' }}</button>
        <p v-if="paintingTerrain" class="help-copy">Drag across the map to paint {{ terrainLabel(terrainType).toLowerCase() }}. Press Escape to stop.</p>
        <button
          class="place-button terrain-erase-button"
          :class="{ active: erasingTerrain }"
          :disabled="!campaign || isLoading"
          @click="toggleTerrainErasing"
        >{{ erasingTerrain ? 'Stop erasing' : 'Erase painted terrain' }}</button>
        <p v-if="erasingTerrain" class="help-copy">Click a painted stroke on the map to remove it. Press Escape to stop.</p>
        <div v-if="terrainRegions.length" class="terrain-list">
          <div v-for="region in terrainRegions" :key="region.id" class="terrain-row">
            <span class="terrain-swatch" :style="{ backgroundColor: terrainColor(region.terrainType) }"></span>
            <span class="terrain-copy">
              <strong>{{ region.name }}</strong>
              <small>{{ terrainLabel(region.terrainType) }}</small>
            </span>
            <button
              type="button"
              :aria-label="`Delete ${region.name}`"
              title="Delete terrain region"
              @click="removeTerrainRegion(region)"
            >×</button>
          </div>
        </div>

        <template v-if="mapMode === 'middle-earth'">
          <div class="divider"></div>
          <div class="field-label name-label">REFERENCE LAYERS</div>
          <label class="reference-toggle">
            <input v-model="showCharacterPaths" type="checkbox" />
            <span>Character paths</span>
          </label>
          <label class="reference-toggle">
            <input v-model="showReferenceEvents" type="checkbox" @change="!showReferenceEvents && (selectedEventId = null)" />
            <span>Key events</span>
          </label>
          <div v-if="showCharacterPaths" class="route-legend">
            <div v-for="route in displayRoutes" :key="route.id" class="route-legend-item">
              <span class="route-swatch" :style="{ backgroundColor: route.color }"></span>
              <span>{{ route.name }}</span>
            </div>
          </div>
          <article v-if="selectedReferenceEvent" class="reference-detail">
            <div class="reference-detail-heading">
              <span class="event-index">{{ selectedReferenceEvent.eventNumber }}</span>
              <span class="field-label">{{ selectedReferenceEvent.placeName }}</span>
            </div>
            <strong>{{ selectedReferenceEvent.title }}</strong>
            <p>{{ selectedReferenceEvent.description }}</p>
          </article>
        </template>

        <div class="divider"></div>
        <div class="section-row"><span class="field-label">ARMIES ON MAP</span><span class="count-pill">{{ armies.length }}</span></div>
        <button class="place-button" :class="{ active: placing }" :disabled="!campaign || isLoading" @click="toggleArmyPlacement">
          <span class="plus">＋</span>{{ placing ? 'Click map to place' : 'Place army' }}
        </button>
        <div v-if="selectedArmy !== null" class="selected-hint">Army selected <button @click="removeSelectedArmy">Remove</button></div>
        <p class="help-copy">Drag armies to move them. Select a piece and press <kbd>⌫</kbd> to remove.</p>

        <div class="sidebar-bottom">
          <div class="legend-line"><span class="legend-swatch blue"></span><span>{{ mapMode === 'middle-earth' ? 'Free Peoples forces' : 'Nephite forces' }}</span><b>{{ blueArmies.length }}</b></div>
          <div class="legend-line"><span class="legend-swatch red"></span><span>{{ mapMode === 'middle-earth' ? 'Sauron’s forces' : 'Lamanite forces' }}</span><b>{{ redArmies.length }}</b></div>
          <button class="layers-button" @click="toggleMapMode">
            <span>◈</span>{{ mapMode === 'middle-earth' ? 'Return to CES map' : mapMode === 'artwork' ? 'Show assembled CES tiles' : 'Show illustrated map' }}<span class="chevron">›</span>
          </button>
        </div>
      </aside>

      <section
        ref="viewport"
        class="map-viewport"
        :class="{ placing, 'painting-terrain': paintingTerrain, 'erasing-terrain': erasingTerrain, dragging: draggingMap }"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerCancel"
      >
        <div ref="world" class="map-world" :class="{ zooming }" :style="worldStyle">
          <img v-if="mapMode === 'artwork'" class="map-image" src="/map.png" alt="Illustrated map of Book of Mormon lands" draggable="false" />
          <canvas v-else ref="canvas" class="map-canvas" :aria-label="mapMode === 'middle-earth' ? 'Illustrated map of Middle-earth' : 'Assembled CES map tiles'"></canvas>

          <svg
            class="terrain-overlay"
            :viewBox="`0 0 ${mapSize.width} ${mapSize.height}`"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <path
              v-for="region in terrainRegions"
              :key="region.id"
              class="terrain-stroke"
              :d="terrainPath(region.points)"
              fill="none"
              :stroke="terrainColor(region.terrainType)"
              :stroke-width="terrainStrokeWidth(region.brushSize)"
            ></path>
            <path
              v-if="activeTerrainStroke"
              class="terrain-stroke terrain-stroke-active"
              :d="terrainPath(activeTerrainStroke.points)"
              fill="none"
              :stroke="terrainColor(activeTerrainStroke.terrainType)"
              :stroke-width="terrainStrokeWidth(activeTerrainStroke.brushSize)"
            ></path>
          </svg>

          <template v-if="mapMode === 'tiles' || mapMode === 'middle-earth'">
            <div
              v-for="place in mapPlaces"
              :key="place.id"
              class="map-place-label"
              :class="[`tier-${place.tier}`, place.kind || 'town']"
              :style="tileLabelStyle(place)"
              aria-hidden="true"
            ><span v-if="place.kind === 'town'" class="place-marker"></span>{{ tilePlaceName(place) }}</div>
          </template>

          <template v-if="mapMode === 'middle-earth'">
            <svg
              v-if="showCharacterPaths"
              class="map-reference-routes"
              viewBox="0 0 100 100"
              preserveAspectRatio="none"
              aria-hidden="true"
            >
              <defs>
                <marker
                  v-for="route in displayRoutes"
                  :id="`route-arrow-${route.id}`"
                  :key="`arrow-${route.id}`"
                  markerWidth="2.8"
                  markerHeight="2.8"
                  refX="2.5"
                  refY="1.4"
                  orient="auto"
                  markerUnits="userSpaceOnUse"
                >
                  <path d="M 0 0 L 2.8 1.4 L 0 2.8 Z" :fill="route.color"></path>
                </marker>
              </defs>
              <g v-for="route in displayRoutes" :key="route.id">
                <path class="route-outline" :d="route.path"></path>
                <path class="route-line" :d="route.path" :marker-end="`url(#route-arrow-${route.id})`" :style="{ stroke: route.color }"></path>
              </g>
            </svg>
            <button
              v-for="event in showReferenceEvents ? referenceEvents : []"
              :key="event.id"
              type="button"
              class="event-marker"
              :class="{ active: selectedEventId === event.id }"
              :style="referenceMarkerStyle(event)"
              :aria-label="`${event.title} at ${event.placeName}`"
              :title="`${event.eventNumber}. ${event.title}`"
              @pointerdown.stop
              @click.stop="selectedEventId = event.id"
            >{{ event.eventNumber }}</button>
          </template>

          <template v-if="mapMode === 'artwork'">
            <button
              v-for="location in artworkPlaces"
              :key="location.id"
              class="location-hitbox"
              :style="{ left: `${location.x}%`, top: `${location.y}%` }"
              :aria-label="`Focus ${displayName(location)}`"
              :title="displayName(location)"
              @pointerdown.stop
              @click.stop="selectedLocation = location.name; goToLocation(location.name)"
            ><span class="location-label">{{ displayName(location) }}</span></button>
          </template>

          <button
            v-for="(army, index) in armies"
            :key="army.id"
            class="army-piece"
            :class="{ selected: selectedArmy === army.id, red: army.side === 'red' }"
            :style="{ left: `${army.x}%`, top: `${army.y}%`, zIndex: selectedArmy === army.id ? 3 : 2 }"
            :aria-label="`${army.faction || 'Army'} ${index + 1}`"
            @pointerdown="beginArmyMove($event, army)"
            @click.stop="selectedArmy = army.id"
          ><span class="army-inner">✦</span></button>
        </div>

        <div class="map-vignette"></div>
        <div class="map-tools">
          <button aria-label="Zoom in" @click.stop="setZoom(zoom * 1.2)">＋</button>
          <span>{{ zoomLabel }}</span>
          <button aria-label="Zoom out" @click.stop="setZoom(zoom / 1.2)">−</button>
          <div class="tool-separator"></div>
          <button class="fit-button" aria-label="Reset map view" @click.stop="zoom = 1; pan = { x: 0, y: 0 }; scaleRevision++">⌗</button>
        </div>
        <div class="map-caption"><span class="caption-dot"></span>{{ mapMode === 'middle-earth' ? 'MIDDLE-EARTH' : mapMode === 'artwork' ? 'ZARAHEMLA THEATER' : 'CES TILE MOSAIC' }}<span class="caption-divider">/</span>{{ mapMode === 'artwork' ? 'MAP 01' : mapMode === 'middle-earth' ? 'WESTERN LANDS' : 'ZOOM 8+' }}</div>
        <div class="coordinates">{{ zoomLabel }} <span>·</span> {{ armies.length }} UNITS</div>
      </section>
    </section>
  </main>
</template>