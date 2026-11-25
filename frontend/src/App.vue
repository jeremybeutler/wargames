<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import defaultCampaignData from './data/campaign-seed.json'
import { localDatabase, validateLegacyExport } from './data/encryptedDatabase.js'
import { DEFAULT_RULESET, applyArmyProposal, parseRuleset } from './multiplayer/gameProtocol.js'

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
  { value: 'plains', label: 'Plains', name: 'Plains', color: '#d5bf6a' },
  { value: 'other', label: 'Other', name: 'Terrain', color: '#d1b56c' },
]
const campaign = ref(null)
const campaigns = ref([])
const selectedCampaignSlug = ref('book-of-mormon')
const campaignPlaces = ref([])
const referenceRoutes = ref([])
const referenceEvents = ref([])
const dataError = ref('')
const isLoading = ref(false)
const databaseUnlocked = ref(false)
const storageMode = ref('checking')
const storagePassphrase = ref('')
const storagePassphraseConfirmation = ref('')
const storageError = ref('')
const databaseNotice = ref('')
const legacyExportFile = ref(null)
const backupImportFile = ref(null)
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
const multiplayerName = ref('')
const meteredPublishableKey = ref('')
const invitationCode = ref('')
const joinCode = ref('')
const multiplayerRole = ref(null)
const multiplayerStatus = ref('disconnected')
const multiplayerError = ref('')
const multiplayerNotice = ref('')
const pendingJoinRequests = ref([])
const pendingProposals = ref([])
const gameRuleset = ref({ ...DEFAULT_RULESET })
const rulesetDraft = ref(JSON.stringify(DEFAULT_RULESET, null, 2))
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
let gameNetwork = null
let roomWriteQueue = Promise.resolve()

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

function reportDataError(error) {
  dataError.value = error instanceof Error ? error.message : 'The local data operation failed'
}

function reportMultiplayerError(error) {
  multiplayerError.value = error instanceof Error ? error.message : 'The multiplayer request failed'
}

function loadMeteredPublishableKey() {
  const configuredKey = import.meta.env.VITE_METERED_PUBLISHABLE_KEY?.trim() || ''
  try {
    meteredPublishableKey.value =
      configuredKey || localStorage.getItem('bom-wargames-metered-publishable-key') || ''
  } catch (error) {
    meteredPublishableKey.value = configuredKey
    reportMultiplayerError(new Error(`Could not load the saved Metered key: ${error.message}`))
  }
}

function saveMeteredPublishableKey() {
  const key = meteredPublishableKey.value.trim()
  if (!/^pk_(live|test)_[A-Za-z0-9_-]+$/.test(key)) {
    throw new Error('Enter a valid Metered publishable key (pk_live_...)')
  }
  try {
    localStorage.setItem('bom-wargames-metered-publishable-key', key)
  } catch (error) {
    throw new Error(`Could not save the Metered key in this browser: ${error.message}`)
  }
  meteredPublishableKey.value = key
}

async function readSelectedJsonFile(file, maximumBytes = 25 * 1024 * 1024) {
  if (!file) return null
  if (file.size > maximumBytes) throw new Error('The selected file is too large')
  try {
    return JSON.parse(await file.text())
  } catch {
    throw new Error('The selected file is not valid JSON')
  }
}

async function inspectLocalDatabase() {
  try {
    storageMode.value = await localDatabase.hasDatabase() ? 'unlock' : 'setup'
  } catch (error) {
    storageMode.value = 'error'
    storageError.value = error instanceof Error ? error.message : 'Could not open IndexedDB'
  }
}

async function ensureSeedData() {
  const campaigns = await localDatabase.readCollection('campaigns')
  if (campaigns.length === 0) {
    await localDatabase.initializeCollections(defaultCampaignData)
  }
}

async function unlockLocalDatabase() {
  storageError.value = ''
  try {
    if (storageMode.value === 'setup') {
      if (storagePassphrase.value.length < 12) {
        throw new Error('Use a passphrase with at least 12 characters')
      }
      if (storagePassphrase.value !== storagePassphraseConfirmation.value) {
        throw new Error('The passphrases do not match')
      }
      const legacyExport = legacyExportFile.value
        ? validateLegacyExport(await readSelectedJsonFile(legacyExportFile.value))
        : null
      await localDatabase.create(
        storagePassphrase.value,
        legacyExport || defaultCampaignData,
      )
    } else {
      await localDatabase.unlock(storagePassphrase.value)
      await ensureSeedData()
    }

    databaseUnlocked.value = true
    storagePassphrase.value = ''
    storagePassphraseConfirmation.value = ''
    await Promise.all([loadCampaignData(), loadTerrainRegions()])
  } catch (error) {
    storageError.value = error instanceof Error ? error.message : 'Could not unlock local data'
  }
}

function chooseLegacyExport(event) {
  legacyExportFile.value = event.target.files?.[0] || null
}

function chooseBackupImport(event) {
  backupImportFile.value = event.target.files?.[0] || null
}

async function restoreEncryptedBackup() {
  storageError.value = ''
  try {
    const backup = await readSelectedJsonFile(backupImportFile.value)
    if (!backup) throw new Error('Choose an encrypted backup file first')
    await localDatabase.restoreEncryptedBackup(backup)
    storageMode.value = 'unlock'
    backupImportFile.value = null
  } catch (error) {
    storageError.value = error instanceof Error ? error.message : 'Could not restore the encrypted backup'
  }
}

function currentGameState() {
  return {
    campaignSlug: campaignSlugForMode(mapMode.value),
    mapMode: mapMode.value,
    armies: armies.value.map(army => ({ ...army })),
    ruleset: { ...gameRuleset.value },
  }
}

function stopMapTools() {
  placing.value = false
  paintingTerrain.value = false
  erasingTerrain.value = false
  activeTerrainStroke.value = null
  selectedArmy.value = null
}

function applyMultiplayerState(state) {
  if (!state
    || state.campaignSlug !== campaignSlugForMode(mapMode.value)
    || state.mapMode !== mapMode.value
    || !Array.isArray(state.armies)
    || state.armies.some(army => !army
      || typeof army !== 'object'
      || army.id === undefined
      || !Number.isFinite(army.x)
      || !Number.isFinite(army.y)
      || army.x < 0
      || army.x > 100
      || army.y < 0
      || army.y > 100)) {
    reportMultiplayerError(new Error('The host sent an invalid game snapshot'))
    return
  }
  try {
    gameRuleset.value = parseRuleset(state.ruleset)
    rulesetDraft.value = JSON.stringify(gameRuleset.value, null, 2)
    armies.value = state.armies.map(army => ({ ...army }))
    selectedArmy.value = null
    multiplayerNotice.value = 'Game state synchronized with the host.'
    if (multiplayerRole.value === 'guest' && gameNetwork?.roomId) {
      void saveRoomSnapshot(state, `room:${gameNetwork.roomId}`).catch(reportDataError)
    }
  } catch (error) {
    reportMultiplayerError(error)
  }
}

async function createGameNetwork() {
  const { GameNetwork } = await import('./multiplayer/gameNetwork.js')
  gameNetwork = new GameNetwork({
    onStatus: status => { multiplayerStatus.value = status },
    onError: reportMultiplayerError,
    onNotice: notice => { multiplayerNotice.value = notice },
    onJoinRequest: request => {
      if (!pendingJoinRequests.value.some(item => item.peerId === request.peerId)) {
        pendingJoinRequests.value = [...pendingJoinRequests.value, request]
      }
    },
    onProposal: proposal => {
      if (pendingProposals.value.some(item => item.proposalId === proposal.proposalId)) return
      pendingProposals.value = [...pendingProposals.value, proposal]
      if (!gameRuleset.value.requireHostApproval) {
        void resolveGameProposal(proposal, true)
      }
    },
    onState: applyMultiplayerState,
    onProposalResult: result => {
      multiplayerNotice.value = result.accepted
        ? 'The host approved your proposed move.'
        : `The host rejected your proposed move${result.reason ? `: ${result.reason}` : '.'}`
    },
    onRejected: () => {
      multiplayerNotice.value = 'The host did not approve your join request.'
    },
  })
  return gameNetwork
}

function saveRoomSnapshot(state, key) {
  const write = roomWriteQueue.then(async () => {
    const rooms = await localDatabase.readCollection('rooms')
    const record = { ...state, key, savedAt: new Date().toISOString() }
    await localDatabase.writeCollection('rooms', [
      ...rooms.filter(room => room.key !== key),
      record,
    ])
  })
  roomWriteQueue = write.catch(reportDataError)
  return write
}

async function saveHostGameState(broadcast = true) {
  if (multiplayerRole.value !== 'host' || !gameNetwork) return
  const state = currentGameState()
  try {
    await saveRoomSnapshot(
      state,
      `host:${state.campaignSlug}:${state.mapMode}`,
    )
  } catch (error) {
    reportDataError(error)
  }
  if (broadcast) await gameNetwork.broadcastState(state).catch(reportMultiplayerError)
}

async function startMultiplayerHost() {
  multiplayerError.value = ''
  multiplayerNotice.value = ''
  if (!campaign.value) {
    reportMultiplayerError(new Error('Campaign data must finish loading before you host a game'))
    return
  }
  try {
    const key = `host:${campaignSlugForMode(mapMode.value)}:${mapMode.value}`
    const roomRecords = await localDatabase.readCollection('rooms')
    const restored = roomRecords.find(room => room.key === key)
    if (restored) {
      if (!Array.isArray(restored.armies)) throw new Error('The saved game has no army list')
      gameRuleset.value = parseRuleset(restored.ruleset)
      armies.value = restored.armies.map(army => ({ ...army }))
    } else {
      gameRuleset.value = { ...DEFAULT_RULESET }
    }
  } catch (error) {
    reportDataError(new Error(`Could not restore the saved host game: ${error.message}`))
    return
  }
  rulesetDraft.value = JSON.stringify(gameRuleset.value, null, 2)
  pendingJoinRequests.value = []
  pendingProposals.value = []
  stopMapTools()

  try {
    saveMeteredPublishableKey()
    const network = await createGameNetwork()
    multiplayerRole.value = 'host'
    invitationCode.value = await network.host(
      meteredPublishableKey.value,
      multiplayerName.value,
      campaignSlugForMode(mapMode.value),
      mapMode.value,
    )
    await saveHostGameState()
  } catch (error) {
    gameNetwork = null
    multiplayerRole.value = null
    reportMultiplayerError(error)
    void loadCampaignData()
  }
}

async function joinMultiplayerGame() {
  multiplayerError.value = ''
  multiplayerNotice.value = ''
  let invite
  try {
    invite = JSON.parse(joinCode.value)
  } catch {
    reportMultiplayerError(new Error('The invitation must be valid JSON'))
    return
  }
  if (typeof invite?.campaignSlug !== 'string'
    || !['tiles', 'artwork', 'middle-earth'].includes(invite.mapMode)) {
    reportMultiplayerError(new Error('The invitation does not include a supported campaign and map'))
    return
  }
  if ((invite.mapMode === 'middle-earth' && invite.campaignSlug !== 'middle-earth')
    || (invite.mapMode !== 'middle-earth'
      && !campaigns.value.some(item => item.slug === invite.campaignSlug))) {
    reportMultiplayerError(new Error('The invited campaign is not available in local campaign data'))
    return
  }
  if (invite.mapMode === 'middle-earth') {
    mapMode.value = 'middle-earth'
  } else {
    mapMode.value = invite.mapMode
    selectedCampaignSlug.value = invite.campaignSlug
  }
  await loadCampaignData(mapMode.value)
  if (!campaign.value || campaign.value.slug !== invite.campaignSlug) {
    reportMultiplayerError(new Error('Could not load the invited campaign from local storage'))
    return
  }
  await loadTerrainRegions(mapMode.value)
  await nextTick()
  measureMap()
  pendingJoinRequests.value = []
  pendingProposals.value = []
  stopMapTools()

  try {
    saveMeteredPublishableKey()
    const network = await createGameNetwork()
    multiplayerRole.value = 'guest'
    await network.join(joinCode.value, meteredPublishableKey.value, multiplayerName.value)
  } catch (error) {
    const failedRole = multiplayerRole.value
    gameNetwork = null
    multiplayerRole.value = null
    reportMultiplayerError(error)
    if (failedRole === 'guest') void loadCampaignData()
  }
}

async function stopMultiplayerGame() {
  const network = gameNetwork
  const previousRole = multiplayerRole.value
  gameNetwork = null
  multiplayerRole.value = null
  invitationCode.value = ''
  pendingJoinRequests.value = []
  pendingProposals.value = []
  multiplayerError.value = ''
  if (network) {
    try {
      await network.stop()
    } catch (error) {
      reportMultiplayerError(error)
    }
  }
  if (previousRole) void loadCampaignData()
}

async function approveJoinRequest(request) {
  multiplayerError.value = ''
  try {
    await gameNetwork.approvePeer(request.peerId, currentGameState())
    pendingJoinRequests.value = pendingJoinRequests.value
      .filter(item => item.peerId !== request.peerId)
    multiplayerNotice.value = `${request.playerName} joined the game.`
  } catch (error) {
    reportMultiplayerError(error)
  }
}

async function rejectJoinRequest(request) {
  multiplayerError.value = ''
  try {
    await gameNetwork.rejectPeer(request.peerId)
    pendingJoinRequests.value = pendingJoinRequests.value
      .filter(item => item.peerId !== request.peerId)
  } catch (error) {
    reportMultiplayerError(error)
  }
}

async function resolveGameProposal(proposal, accepted) {
  if (!gameNetwork || multiplayerRole.value !== 'host') return
  multiplayerError.value = ''
  let reason = ''
  let nextState = currentGameState()
  if (accepted) {
    const result = applyArmyProposal(nextState, proposal)
    if (result.error) {
      accepted = false
      reason = result.error
    } else {
      nextState = { ...nextState, armies: result.state.armies }
      armies.value = result.state.armies
      await saveHostGameState(false)
    }
  }
  pendingProposals.value = pendingProposals.value
    .filter(item => item.proposalId !== proposal.proposalId)
  try {
    await gameNetwork.resolveProposal(
      proposal.peerId,
      proposal.proposalId,
      accepted,
      nextState,
      reason,
    )
    if (accepted) {
      return
    } else if (reason) {
      multiplayerNotice.value = `Proposal rejected: ${reason}`
    }
  } catch (error) {
    reportMultiplayerError(error)
    if (accepted) {
      await gameNetwork.broadcastState(currentGameState()).catch(reportMultiplayerError)
    }
  }
}

async function publishRuleset() {
  multiplayerError.value = ''
  try {
    gameRuleset.value = parseRuleset(rulesetDraft.value)
    rulesetDraft.value = JSON.stringify(gameRuleset.value, null, 2)
    await saveHostGameState()
  } catch (error) {
    reportMultiplayerError(error)
  }
}

async function copyInvitation() {
  try {
    await navigator.clipboard.writeText(invitationCode.value)
    multiplayerNotice.value = 'Invitation copied to the clipboard.'
  } catch (error) {
    reportMultiplayerError(error)
  }
}

async function downloadEncryptedBackup() {
  try {
    const backup = await localDatabase.exportEncryptedBackup()
    const blob = new Blob([backup], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `bom-wargames-encrypted-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    databaseNotice.value = 'Encrypted backup downloaded. Keep your passphrase safe.'
  } catch (error) {
    reportDataError(error)
  }
}

function lockLocalDatabase() {
  if (gameNetwork) {
    dataError.value = 'Leave the multiplayer room before locking the local database'
    return
  }
  campaignLoadId += 1
  terrainLoadId += 1
  localDatabase.lock()
  databaseUnlocked.value = false
  isLoading.value = false
  campaign.value = null
  campaigns.value = []
  campaignPlaces.value = []
  referenceRoutes.value = []
  referenceEvents.value = []
  armies.value = []
  terrainRegions.value = []
  invitationCode.value = ''
  joinCode.value = ''
  pendingJoinRequests.value = []
  pendingProposals.value = []
  gameRuleset.value = { ...DEFAULT_RULESET }
  rulesetDraft.value = JSON.stringify(DEFAULT_RULESET, null, 2)
  multiplayerError.value = ''
  multiplayerNotice.value = ''
  stopMapTools()
  storageMode.value = 'unlock'
  dataError.value = ''
  databaseNotice.value = ''
}

function retryData() {
  dataError.value = ''
  void Promise.all([loadCampaignData(), loadTerrainRegions()])
}

async function loadCampaignData(mode = mapMode.value) {
  const requestId = ++campaignLoadId
  const slug = campaignSlugForMode(mode)
  isLoading.value = true
  dataError.value = ''
  campaign.value = null
  campaignPlaces.value = []
  referenceRoutes.value = []
  referenceEvents.value = []
  armies.value = []

  try {
    const [availableCampaigns, allPlaces, allRoutes, allEvents, allArmies] = await Promise.all([
      localDatabase.readCollection('campaigns'),
      localDatabase.readCollection('places'),
      localDatabase.readCollection('routes'),
      localDatabase.readCollection('events'),
      localDatabase.readCollection('armies'),
    ])
    if (requestId !== campaignLoadId) return
    campaigns.value = availableCampaigns
    const selectedCampaign = campaigns.value.find(item => item.slug === slug)
    if (!selectedCampaign) throw new Error(`Campaign "${slug}" was not found`)

    campaign.value = selectedCampaign
    campaignPlaces.value = allPlaces.filter(place => place.campaignId === selectedCampaign.id)
    referenceRoutes.value = allRoutes.filter(route => route.campaignId === selectedCampaign.id)
    referenceEvents.value = allEvents
      .filter(event => event.campaignId === selectedCampaign.id)
      .sort((left, right) =>
      (left.eventNumber ?? 0) - (right.eventNumber ?? 0))
    armies.value = allArmies.filter(army => army.campaignId === selectedCampaign.id)
  } catch (error) {
    if (requestId !== campaignLoadId) return
    reportDataError(error)
  } finally {
    if (requestId === campaignLoadId) isLoading.value = false
  }
}

async function loadTerrainRegions(mode = mapMode.value) {
  const requestId = ++terrainLoadId
  const mapKey = terrainMapKeyForMode(mode)
  terrainRegions.value = []

  try {
    const allRegions = await localDatabase.readCollection('terrainRegions')
    if (requestId !== terrainLoadId) return
    terrainRegions.value = allRegions.filter(region =>
      region.mapKey === mapKey && region.mapLayer === mode)
  } catch (error) {
    if (requestId === terrainLoadId) reportDataError(error)
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

  const destination = { x: army.x, y: army.y }
  if (multiplayerRole.value) {
    army.x = start.x
    army.y = start.y
    if (multiplayerRole.value === 'guest') {
      void gameNetwork.propose('army.move', { id: armyId, ...destination })
        .catch(reportMultiplayerError)
      return
    }

    const result = applyArmyProposal(currentGameState(), {
      action: 'army.move',
      payload: { id: armyId, ...destination },
    })
    if (result.error) {
      multiplayerNotice.value = result.error
      return
    }
    armies.value = result.state.armies
    await saveHostGameState()
    return
  }

  try {
    const savedArmies = await localDatabase.readCollection('armies')
    const index = savedArmies.findIndex(item => item.id === armyId)
    if (index < 0) throw new Error('The army no longer exists in local storage')
    savedArmies[index] = { ...army }
    await localDatabase.writeCollection('armies', savedArmies)
  } catch (error) {
    army.x = start.x
    army.y = start.y
    reportDataError(error)
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
    const regions = await localDatabase.readCollection('terrainRegions')
    const region = { ...stroke, id: crypto.randomUUID() }
    await localDatabase.writeCollection('terrainRegions', [...regions, region])
    terrainRegions.value = [...terrainRegions.value, region]
    terrainName.value = ''
    dataError.value = ''
  } catch (error) {
    reportDataError(error)
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
    const regions = await localDatabase.readCollection('terrainRegions')
    await localDatabase.writeCollection('terrainRegions', regions.filter(item => item.id !== region.id))
    terrainRegions.value = terrainRegions.value.filter(item => item.id !== region.id)
    dataError.value = ''
  } catch (error) {
    reportDataError(error)
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

async function createArmy(point) {
  if (!campaign.value) {
    dataError.value = 'Campaign data is not loaded'
    return
  }
  const faction = mapMode.value === 'middle-earth' ? 'Free Peoples' : 'Nephites'
  if (multiplayerRole.value === 'guest') {
    try {
      await gameNetwork.propose('army.create', {
        faction,
        side: 'blue',
        ...point,
        unitCount: 1,
      })
      placing.value = false
      multiplayerNotice.value = 'Army placement proposed to the host.'
    } catch (error) {
      reportMultiplayerError(error)
    }
    return
  }
  if (multiplayerRole.value === 'host') {
    const result = applyArmyProposal(currentGameState(), {
      action: 'army.create',
      payload: {
        faction,
        side: 'blue',
        ...point,
        unitCount: 1,
      },
    })
    if (result.error) {
      multiplayerNotice.value = result.error
      return
    }
    armies.value = result.state.armies
    selectedArmy.value = result.result.id
    placing.value = false
    await saveHostGameState()
    return
  }
  try {
    const savedArmies = await localDatabase.readCollection('armies')
    const army = {
      id: crypto.randomUUID(),
      campaignId: campaign.value.id,
      name: `${faction} Army ${savedArmies.filter(item => item.campaignId === campaign.value.id).length + 1}`,
      faction,
      side: 'blue',
      ...point,
      unitCount: 1,
      description: '',
    }
    await localDatabase.writeCollection('armies', [...savedArmies, army])
    armies.value.push(army)
    selectedArmy.value = army.id
    placing.value = false
    dataError.value = ''
  } catch (error) {
    reportDataError(error)
  }
}

async function removeSelectedArmy() {
  if (selectedArmy.value === null) return
  const armyId = selectedArmy.value
  if (multiplayerRole.value === 'guest') {
    try {
      await gameNetwork.propose('army.remove', { id: armyId })
      selectedArmy.value = null
      multiplayerNotice.value = 'Army removal proposed to the host.'
    } catch (error) {
      reportMultiplayerError(error)
    }
    return
  }
  if (multiplayerRole.value === 'host') {
    const result = applyArmyProposal(currentGameState(), {
      action: 'army.remove',
      payload: { id: armyId },
    })
    if (result.error) {
      multiplayerNotice.value = result.error
      return
    }
    armies.value = result.state.armies
    selectedArmy.value = null
    await saveHostGameState()
    return
  }
  try {
    const savedArmies = await localDatabase.readCollection('armies')
    await localDatabase.writeCollection('armies', savedArmies.filter(army => army.id !== armyId))
    armies.value = armies.value.filter(army => army.id !== armyId)
    selectedArmy.value = null
    dataError.value = ''
  } catch (error) {
    reportDataError(error)
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
  if (databaseUnlocked.value) void loadTerrainRegions(mode)
})

watch(databaseUnlocked, async unlocked => {
  await nextTick()
  if (unlocked) {
    viewport.value?.addEventListener('wheel', handleWheel, { passive: false })
    measureMap()
  } else {
    viewport.value?.removeEventListener('wheel', handleWheel)
  }
})

watch([mapMode, cesTiles, zoom, scaleRevision], async () => {
  await nextTick()
  drawTileCanvas()
})

onMounted(() => {
  window.addEventListener('resize', measureMap)
  window.addEventListener('keydown', onKeydown)
  loadMeteredPublishableKey()
  void inspectLocalDatabase()
  if (mapMode.value === 'tiles') loadCesTiles()
})

onBeforeUnmount(() => {
  window.clearTimeout(zoomTimer)
  if (gameNetwork) {
    const network = gameNetwork
    gameNetwork = null
    void network.stop().catch(error => console.error('Failed to stop multiplayer network', error))
  }
  localDatabase.lock()
  viewport.value?.removeEventListener('wheel', handleWheel)
  window.removeEventListener('resize', measureMap)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <main class="app-shell">
    <section v-if="!databaseUnlocked" class="storage-gate">
      <div class="storage-card">
        <div class="section-eyebrow">PRIVATE LOCAL STORAGE</div>
        <h1>{{ storageMode === 'setup' ? 'Create your local campaign database' : 'Unlock your campaign data' }}</h1>
        <p class="intro">Campaign data and your saved map edits stay in this browser. Records in IndexedDB are encrypted with AES-256-GCM; your passphrase is used to derive the key and is never stored or sent.</p>
        <div v-if="storageMode === 'checking'" class="local-data-status">Checking local storage…</div>
        <div v-else-if="storageMode === 'error'" class="storage-error" role="alert">{{ storageError }}</div>
        <form v-else @submit.prevent="unlockLocalDatabase">
          <label class="terrain-field">
            {{ storageMode === 'setup' ? 'Create a passphrase' : 'Passphrase' }}
            <input
              v-model="storagePassphrase"
              type="password"
              minlength="12"
              :autocomplete="storageMode === 'setup' ? 'new-password' : 'current-password'"
              required
              placeholder="At least 12 characters"
            />
          </label>
          <label v-if="storageMode === 'setup'" class="terrain-field">
            Confirm passphrase
            <input
              v-model="storagePassphraseConfirmation"
              type="password"
              minlength="12"
              autocomplete="new-password"
              required
            />
          </label>
          <label v-if="storageMode === 'setup'" class="terrain-field">
            Optional one-time legacy H2 export
            <input type="file" accept=".json,application/json" @change="chooseLegacyExport" />
          </label>
          <p v-if="storageMode === 'setup'" class="help-copy">Importing an H2 export encrypts it in this browser. Delete the plaintext export after import. If you skip it, the built-in campaign data is installed.</p>
          <button class="place-button storage-submit" type="submit">
            {{ storageMode === 'setup' ? 'Create encrypted database' : 'Unlock database' }}
          </button>
        </form>
        <div v-if="storageMode === 'setup'" class="backup-restore">
          <label class="terrain-field">
            Restore an encrypted backup
            <input type="file" accept=".json,application/json" @change="chooseBackupImport" />
          </label>
          <button class="place-button" type="button" :disabled="!backupImportFile" @click="restoreEncryptedBackup">
            Load backup, then unlock
          </button>
        </div>
        <p v-if="storageError" class="storage-error" role="alert">{{ storageError }}</p>
        <p class="help-copy">There is no password reset or server-side copy. Losing the passphrase means losing access to this database. Export encrypted backups regularly. Web Crypto requires HTTPS or localhost.</p>
      </div>
    </section>
    <template v-else>
      <header class="topbar">
        <a class="brand" href="#" aria-label="Wargames home">
          <span class="brand-mark">B</span>
          <span class="brand-copy"><strong>Wargames</strong><small>FIELD MAP · ALMA 52</small></span>
        </a>
        <div class="topbar-right">
          <span class="status-dot"></span><span class="status-text">LOCAL · ENCRYPTED</span>
          <button class="topbar-action" @click="downloadEncryptedBackup">Export backup</button>
          <button class="topbar-action" @click="lockLocalDatabase">Lock</button>
        </div>
      </header>

      <section class="map-layout">
      <aside class="sidebar">
        <div v-if="databaseNotice" class="local-data-status" role="status">{{ databaseNotice }}</div>
        <div v-if="dataError" class="local-data-error" role="alert">
          {{ dataError }}
          <button @click="retryData">Retry</button>
        </div>
        <div v-else-if="isLoading" class="local-data-status">Loading campaign data…</div>
        <div class="section-eyebrow">THEATER OF OPERATIONS</div>
        <h1>{{ campaign?.name || (mapMode === 'middle-earth' ? 'Middle-earth' : 'Land of Zarahemla') }}</h1>
        <p class="intro">{{ campaign?.description || 'A strategic campaign map.' }}</p>
        <div class="divider"></div>
        <div class="field-label name-label">MAP WORLD</div>
        <div class="select-wrap">
          <select
            :value="mapMode === 'middle-earth' ? 'middle-earth' : 'tiles'"
            aria-label="Choose map world"
            :disabled="!!multiplayerRole"
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
              :disabled="!!multiplayerRole"
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
          <input v-model="terrainName" maxlength="100" :disabled="!!multiplayerRole" placeholder="Optional, e.g. Northern Forest" />
        </label>
        <label class="terrain-field">
          Terrain type
          <div class="select-wrap">
            <select v-model="terrainType" aria-label="Terrain type" :disabled="!!multiplayerRole">
              <option v-for="type in TERRAIN_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
            </select>
            <span aria-hidden="true">⌄</span>
          </div>
        </label>
        <label class="terrain-field">
          <span class="terrain-field-heading">Brush size <strong>{{ terrainBrushSize }}%</strong></span>
          <input v-model.number="terrainBrushSize" type="range" min="0.5" max="15" step="0.5" aria-label="Brush size" :disabled="!!multiplayerRole" />
        </label>
        <button
          class="place-button"
          :class="{ active: paintingTerrain }"
          :disabled="!campaign || isLoading || !!multiplayerRole"
          @click="toggleTerrainPainting"
        >{{ paintingTerrain ? 'Finish painting' : 'Paint terrain' }}</button>
        <p v-if="paintingTerrain" class="help-copy">Drag across the map to paint {{ terrainLabel(terrainType).toLowerCase() }}. Press Escape to stop.</p>
        <button
          class="place-button terrain-erase-button"
          :class="{ active: erasingTerrain }"
          :disabled="!campaign || isLoading || !!multiplayerRole"
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
              :disabled="!!multiplayerRole"
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
        <p class="help-copy">
          {{ multiplayerRole === 'guest'
            ? 'Propose army placements, moves, and removals to the host for approval.'
            : 'Drag armies to move them. Select a piece and press Delete/Backspace to remove.' }}
        </p>

        <div class="divider"></div>
        <div class="section-row">
          <span class="field-label">MULTIPLAYER</span>
          <span class="multiplayer-status">{{ multiplayerStatus }}</span>
        </div>
        <p class="help-copy">The host is authoritative. Metered handles signaling; gameplay travels over encrypted WebRTC data channels, using TURN only when a direct connection cannot be made.</p>
        <label class="terrain-field">
          Player name
          <input v-model="multiplayerName" maxlength="80" :disabled="!!multiplayerRole" placeholder="Your name" />
        </label>
        <div v-if="!multiplayerRole" class="multiplayer-controls">
          <label class="terrain-field">
            Metered publishable key
            <input
              v-model="meteredPublishableKey"
              type="password"
              autocomplete="off"
              spellcheck="false"
              placeholder="pk_live_…"
            />
          </label>
          <p class="help-copy">Create a publishable key in Metered Realtime → API Keys. Enable Send, Publish, Subscribe, and Presence, and restrict its channel pattern to <code>bom-wargames-*</code>. The Realtime SDK handles TURN configuration. You can prefill this field with <code>VITE_METERED_PUBLISHABLE_KEY</code> in a local <code>.env.local</code> file. This key is browser-visible and is not included in invitations.</p>
          <button class="place-button" :disabled="!campaign || isLoading || !meteredPublishableKey.trim()" @click="startMultiplayerHost">
            Host game
          </button>
          <label class="terrain-field">
            Invitation JSON
            <textarea v-model="joinCode" rows="4" placeholder="Paste an invitation from the host"></textarea>
          </label>
          <button class="place-button" :disabled="!joinCode.trim() || !multiplayerName.trim() || !meteredPublishableKey.trim()" @click="joinMultiplayerGame">
            Join game
          </button>
        </div>
        <div v-else class="multiplayer-controls">
          <p class="multiplayer-role">{{ multiplayerRole === 'host' ? 'You are hosting this game.' : 'You are connected as a player.' }}</p>
          <div v-if="multiplayerRole === 'host'">
            <label class="terrain-field">
              Share this invitation with players
              <textarea :value="invitationCode" rows="5" readonly></textarea>
            </label>
            <button class="place-button" :disabled="!invitationCode" @click="copyInvitation">Copy invitation</button>
            <div v-if="pendingJoinRequests.length" class="multiplayer-list">
              <strong class="multiplayer-list-title">Join requests</strong>
              <div v-for="request in pendingJoinRequests" :key="request.peerId" class="multiplayer-request">
                <span>{{ request.playerName }}<small>{{ request.peerId.slice(0, 18) }}…</small></span>
                <button @click="approveJoinRequest(request)">Approve</button>
                <button @click="rejectJoinRequest(request)">Reject</button>
              </div>
            </div>
            <div v-if="pendingProposals.length" class="multiplayer-list">
              <strong class="multiplayer-list-title">Pending actions</strong>
              <div v-for="proposal in pendingProposals" :key="proposal.proposalId" class="multiplayer-request proposal-request">
                <span>{{ proposal.action }}<small>{{ proposal.peerId.slice(0, 18) }}…</small></span>
                <button @click="resolveGameProposal(proposal, true)">Approve</button>
                <button @click="resolveGameProposal(proposal, false)">Reject</button>
              </div>
            </div>
            <label class="terrain-field">
              Host-editable ruleset (JSON)
              <textarea v-model="rulesetDraft" rows="8" spellcheck="false"></textarea>
            </label>
            <button class="place-button" @click="publishRuleset">Apply ruleset</button>
          </div>
          <pre v-else class="ruleset-view">{{ JSON.stringify(gameRuleset, null, 2) }}</pre>
          <p v-if="multiplayerNotice" class="multiplayer-notice" role="status">{{ multiplayerNotice }}</p>
          <button class="place-button disconnect-button" @click="stopMultiplayerGame">Leave game</button>
        </div>
        <div v-if="multiplayerError" class="multiplayer-error" role="alert">{{ multiplayerError }}</div>

        <div class="sidebar-bottom">
          <div class="legend-line"><span class="legend-swatch blue"></span><span>{{ mapMode === 'middle-earth' ? 'Free Peoples forces' : 'Nephite forces' }}</span><b>{{ blueArmies.length }}</b></div>
          <div class="legend-line"><span class="legend-swatch red"></span><span>{{ mapMode === 'middle-earth' ? 'Sauron’s forces' : 'Lamanite forces' }}</span><b>{{ redArmies.length }}</b></div>
          <button class="layers-button" :disabled="!!multiplayerRole" @click="toggleMapMode">
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
    </template>
  </main>
</template>