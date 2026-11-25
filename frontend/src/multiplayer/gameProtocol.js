export const DEFAULT_RULESET = Object.freeze({
  name: 'Default campaign rules',
  requireHostApproval: true,
  maxArmies: 50,
  maxUnitsPerArmy: 100000,
  allowArmyMovement: true,
  allowArmyRemoval: true,
})

const COORDINATE_MIN = 0
const COORDINATE_MAX = 100

export function parseRuleset(source) {
  let value
  try {
    value = typeof source === 'string' ? JSON.parse(source) : source
  } catch {
    throw new Error('Rules must be valid JSON')
  }

  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Rules must be a JSON object')
  }

  const name = typeof value.name === 'string' ? value.name.trim() : ''
  if (!name || name.length > 80) {
    throw new Error('Rules need a name between 1 and 80 characters')
  }
  if (!Number.isInteger(value.maxArmies) || value.maxArmies < 1 || value.maxArmies > 500) {
    throw new Error('maxArmies must be an integer from 1 to 500')
  }
  if (!Number.isInteger(value.maxUnitsPerArmy)
    || value.maxUnitsPerArmy < 1
    || value.maxUnitsPerArmy > 1000000) {
    throw new Error('maxUnitsPerArmy must be an integer from 1 to 1000000')
  }
  for (const key of ['requireHostApproval', 'allowArmyMovement', 'allowArmyRemoval']) {
    if (typeof value[key] !== 'boolean') {
      throw new Error(`${key} must be true or false`)
    }
  }
  return {
    name,
    requireHostApproval: value.requireHostApproval,
    maxArmies: value.maxArmies,
    maxUnitsPerArmy: value.maxUnitsPerArmy,
    allowArmyMovement: value.allowArmyMovement,
    allowArmyRemoval: value.allowArmyRemoval,
  }
}

function isCoordinate(value) {
  return Number.isFinite(value) && value >= COORDINATE_MIN && value <= COORDINATE_MAX
}

export function applyArmyProposal(state, proposal, createId = () => crypto.randomUUID()) {
  if (!state || !Array.isArray(state.armies) || !state.ruleset) {
    return { error: 'The current game state is invalid' }
  }
  if (!proposal || !proposal.payload || typeof proposal.payload !== 'object') {
    return { error: 'The proposed action is invalid' }
  }

  const armies = state.armies.map(army => ({ ...army }))
  const payload = proposal.payload

  if (proposal.action === 'army.create') {
    if (armies.length >= state.ruleset.maxArmies) {
      return { error: 'The ruleset army limit has been reached' }
    }
    if (!isCoordinate(payload.x) || !isCoordinate(payload.y)) {
      return { error: 'Army coordinates must be between 0 and 100' }
    }
    if (typeof payload.faction !== 'string' || !payload.faction.trim() || payload.faction.length > 80
      || !['blue', 'red'].includes(payload.side)
      || !Number.isInteger(payload.unitCount)
      || payload.unitCount < 1
      || payload.unitCount > state.ruleset.maxUnitsPerArmy) {
      return { error: 'The proposed army does not satisfy the ruleset' }
    }
    const army = {
      id: createId(),
      name: typeof payload.name === 'string' && payload.name.trim()
        ? payload.name.trim().slice(0, 100)
        : `${payload.faction.trim()} Army ${armies.length + 1}`,
      faction: payload.faction.trim(),
      side: payload.side,
      x: payload.x,
      y: payload.y,
      unitCount: payload.unitCount,
      description: '',
    }
    return { state: { ...state, armies: [...armies, army] }, result: army }
  }

  const armyIndex = armies.findIndex(army => army.id === payload.id)
  if (armyIndex < 0) return { error: 'The army no longer exists' }

  if (proposal.action === 'army.move') {
    if (!state.ruleset.allowArmyMovement) {
      return { error: 'Army movement is disabled by the ruleset' }
    }
    if (!isCoordinate(payload.x) || !isCoordinate(payload.y)) {
      return { error: 'Army coordinates must be between 0 and 100' }
    }
    armies[armyIndex] = { ...armies[armyIndex], x: payload.x, y: payload.y }
    return { state: { ...state, armies }, result: armies[armyIndex] }
  }

  if (proposal.action === 'army.remove') {
    if (!state.ruleset.allowArmyRemoval) {
      return { error: 'Army removal is disabled by the ruleset' }
    }
    const [removed] = armies.splice(armyIndex, 1)
    return { state: { ...state, armies }, result: removed }
  }

  return { error: 'This action is not supported in a multiplayer game' }
}
