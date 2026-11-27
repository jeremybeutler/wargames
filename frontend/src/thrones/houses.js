// Presentation data for The Iron Throne's houses: colours, sigil shapes and rule text.
// The rules themselves live on the server (ThronesGame.java); this text explains them.

export const HOUSES = {
  stark: {
    color: '#a9b8c2', ink: '#1d2a33', words: 'Winter Is Coming',
    passive: 'Winter Is Coming: snow costs no extra movement; +1 on every defense die in the North.',
    power: 'The North Remembers: 3 bannermen join your army at a Northern castle you hold.',
  },
  lannister: {
    color: '#b3322b', ink: '#f3d27a', words: 'Hear Me Roar!',
    passive: 'Hear Me Roar: +2 gold every turn. Buy a neutral castle by standing in it (2 gold per garrison unit).',
    power: "Wildfire: at King's Landing, every enemy army there loses 2 units, and an enemy garrison loses 2.",
  },
  targaryen: {
    color: '#2b1a1d', ink: '#e0473a', words: 'Fire and Blood',
    passive: 'Fire and Blood: three dragon eggs hatch in round 4. Each dragon adds 2 dice; with dragons you fly over land and sea.',
    power: 'Dothraki Screamers: attack twice this turn with +1 on every die.',
  },
  baratheon: {
    color: '#e2b33a', ink: '#231d12', words: 'Ours Is the Fury',
    passive: "Dragonglass: whoever holds Dragonstone (and their allies) can slay the Night King. +1 per die against the Dead.",
    power: "Shadow Assassin: kill any rival leader on the map; their next heir takes over. Never a house's last heir.",
  },
  greyjoy: {
    color: '#2f3b40', ink: '#d9b44a', words: 'We Do Not Sow',
    passive: 'What Is Dead May Never Die: sail from any coast to any coast at 30 miles a point. +1 per die raiding a coastal castle.',
    power: 'Reaving: take the coastal castle you stand in, if its garrison is 1 or less, without a battle.',
  },
  tyrell: {
    color: '#3f7a3a', ink: '#f0d26a', words: 'Growing Strong',
    passive: 'Growing Strong: levies cost 1 gold instead of 2.',
    power: 'Royal Wedding: offer a pact that cannot be broken for 5 rounds; your partner gains 2 units when it is accepted.',
  },
  martell: {
    color: '#d97a2b', ink: '#5a1414', words: 'Unbowed, Unbent, Unbroken',
    passive: 'Unbowed: desert costs no extra movement; +1 on every defense die in Dorne.',
    power: "The Viper's Duel: challenge a rival leader standing with you. Each rolls 2 dice; the loser's leader dies (yours too, if you lose).",
  },
  dead: {
    color: '#8fd3ee', ink: '#0f2733', words: 'The Long Night',
    passive: 'Army of the Dead: every battle you win against an army raises another unit. Snow costs no extra movement. You cannot attack until Winter.',
    power: 'The Long Night (Winter only): double movement for one turn.',
  },
}

export const NEUTRAL_COLOR = '#7c7a72'

export function houseColor(id) {
  return HOUSES[id]?.color || NEUTRAL_COLOR
}

// Simple original sigil shapes, drawn in a 24 x 24 box.
export const SIGILS = {
  stark: 'M12 2v20M3.3 7l17.4 10M3.3 17L20.7 7M12 5l-2-2M12 5l2-2M12 19l-2 2M12 19l2 2',
  lannister: 'M4 18h16l-1-9-4 4-3-7-3 7-4-4z',
  targaryen: 'M12 3c3 4 6 6 6 11a6 6 0 0 1-12 0c0-3 2-5 3-6 0 2 1 3 2 3 0-3 0-5 1-8z',
  baratheon: 'M12 21v-9M12 12L6 5M12 12l6-7M9 8.5L6 9M15 8.5l3 .5M7.5 6.8L5 4M16.5 6.8L19 4',
  greyjoy: 'M12 4a4 4 0 0 1 4 4v3H8V8a4 4 0 0 1 4-4zM8 11c-2 3-4 4-5 8M10 11c-1 4-1 6-2 9M14 11c1 4 1 6 2 9M16 11c2 3 4 4 5 8',
  tyrell: 'M12 6a3 3 0 1 1 0 6 3 3 0 1 1 0-6zM12 3c3 0 5 2 5 4M12 3c-3 0-5 2-5 4M7 13c1 3 3 4 5 4s4-1 5-4M12 17v5',
  martell: 'M12 8a4 4 0 1 1 0 8 4 4 0 1 1 0-8zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2M4 20L20 4',
  dead: 'M12 2l2 6 6-2-4 5 5 3-6 1 1 7-4-5-4 5 1-7-6-1 5-3-4-5 6 2z',
}

export const PLACE_KINDS_WITH_LABELS = ['capital', 'seat', 'city']
