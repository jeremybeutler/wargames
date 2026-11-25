import test from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_RULESET, applyArmyProposal, parseRuleset } from './gameProtocol.js'

const initialState = {
  armies: [{ id: 'army-1', x: 10, y: 20, side: 'blue', unitCount: 5 }],
  ruleset: { ...DEFAULT_RULESET },
}

test('ruleset parsing rejects invalid limits and normalizes supported fields', () => {
  const parsed = parseRuleset(JSON.stringify({ ...DEFAULT_RULESET, name: '  My rules  ' }))
  assert.equal(parsed.name, 'My rules')
  assert.throws(() => parseRuleset({ ...DEFAULT_RULESET, maxArmies: 0 }), /maxArmies/)
  assert.throws(() => parseRuleset('{'), /valid JSON/)
})

test('army move proposal returns a new state and leaves the original unchanged', () => {
  const result = applyArmyProposal(initialState, {
    action: 'army.move',
    payload: { id: 'army-1', x: 35, y: 45 },
  })

  assert.equal(result.state.armies[0].x, 35)
  assert.equal(initialState.armies[0].x, 10)
  assert.equal(applyArmyProposal(initialState, {
    action: 'army.move',
    payload: { id: 'army-1', x: 101, y: 45 },
  }).error, 'Army coordinates must be between 0 and 100')
})

test('army creation enforces the active army limit', () => {
  const state = {
    ...initialState,
    ruleset: { ...DEFAULT_RULESET, maxArmies: 1 },
  }
  const result = applyArmyProposal(state, {
    action: 'army.create',
    payload: { faction: 'Nephites', side: 'blue', x: 30, y: 40, unitCount: 1 },
  }, () => 'new-army')

  assert.equal(result.error, 'The ruleset army limit has been reached')
})

test('army removal respects the host ruleset', () => {
  const state = {
    ...initialState,
    ruleset: { ...DEFAULT_RULESET, allowArmyRemoval: false },
  }
  const result = applyArmyProposal(state, {
    action: 'army.remove',
    payload: { id: 'army-1' },
  })

  assert.equal(result.error, 'Army removal is disabled by the ruleset')
  assert.equal(state.armies.length, 1)
})
