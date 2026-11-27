package org.wargames;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** The saved state of a game of The Iron Throne, stored as JSON in game_sessions. */
final class ThronesState {
    private ThronesState() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Room {
        public String code;
        /** lobby, campaign or over. */
        public String status = "lobby";
        public String hostTokenHash;
        public String hostName;
        public String createdAt;
        public double mapWidthMiles;
        public int roundLimit;
        /** When nobody claims the Dead, the server plays them. */
        public boolean computerDead;
        /** Table talk the host chose: off, text, voice or both (null on older saves: text). */
        public String chatMode;
        public int round;
        public int turnIndex;
        public List<String> turnOrderIds = new ArrayList<>();
        public List<House> houses = new ArrayList<>();
        public List<Castle> castles = new ArrayList<>();
        public List<Coalition> coalitions = new ArrayList<>();
        public List<Invite> invites = new ArrayList<>();
        /** Set once Winter first falls, so everyone sees the Long Night begin. */
        public boolean longNight;
        public int coalitionCount;
        public List<String> events = new ArrayList<>();
        /** The round in which Winter begins; Fate can bring it sooner. */
        public int winterRound = ThronesRules.DEFAULT_WINTER_ROUND;
        /** Map y % above which the ground is snow. Starts at the Wall and creeps south. */
        public double snowLine = ThronesRules.SNOW_LINE_START;
        public List<String> fateDeck = new ArrayList<>();
        public String fate;
        public int fateRound;
        public boolean wallBreached;
        public boolean iceDragon;
        public boolean dragonsHatched;
        public List<String> winners = new ArrayList<>();
        public String winnerText;
        public BattleLog lastBattle;
        public int battleCount;
        /** Bumped whenever a Fate card is drawn, so clients can show each new card once. */
        public int fateCount;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class House {
        public String id;
        public String name;
        public String seat;
        public List<String> heirs = new ArrayList<>();
        public int heirIndex;
        public String leader;
        public int units;
        public int initialUnits;
        public double x;
        public double y;
        public int gold;
        public String playerName;
        public String playerTokenHash;
        public boolean claimed;
        /** Played by the server (the Dead only). */
        public boolean ai;
        /** "easy" or "hard" when a bot plays this house; it competes like a player. */
        public String bot;
        public boolean alive;
        public boolean hidden;
        public int visibleUntilRound = -1;
        public String terrain = "plains";
        public boolean moved;
        public boolean attacked;
        public int attacksLeft = 1;
        public double movementMax = ThronesRules.MOVEMENT_POINTS;
        public double movementRemaining = ThronesRules.MOVEMENT_POINTS;
        public int recruitedThisTurn;
        public boolean powerUsed;
        public boolean screamers;
        /** Set when this house leaves a coalition: attacks on its former allies this turn get the Red Wedding bonus. */
        public List<String> redWeddingTargets = new ArrayList<>();
        /** After leaving a coalition, the house can't join or invite anyone until after this round. */
        public int oathbreakerUntil;
        /** A Royal Wedding binds the house to its coalition until after this round. */
        public int lockedUntil;
        /** Fought the Dead during the Long Night while in a coalition: a sworn bond (see ThronesGame.sworn). */
        public boolean forged;
        public List<String> dragons = new ArrayList<>();
        /** The side of the Wall the party came from when it stepped onto it: -1 north, 1 south. */
        public int wallFrom;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Castle {
        public String id;
        public String name;
        public String kind;
        public String region;
        public double x;
        public double y;
        /** A house id, or "neutral". */
        public String owner;
        public int units;
        public int initialUnits;
        public boolean great;
        public boolean port;
    }

    /** Houses sworn together: they can't attack each other, see each other's armies and win together. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Coalition {
        public String id;
        public List<String> members = new ArrayList<>();
        public int sinceRound;

        public Coalition() {}

        Coalition(String id, int sinceRound) {
            this.id = id;
            this.sinceRound = sinceRound;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Invite {
        public String from;
        public String to;
        public boolean royal;

        public Invite() {}

        Invite(String from, String to, boolean royal) {
            this.from = from;
            this.to = to;
            this.royal = royal;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BattleLog {
        public int id;
        public int round;
        public String attackerId;
        public String attacker;
        public int attackerUnits;
        public boolean attackerFell;
        public String defenderId;
        public String defender;
        public Integer defenderUnits;
        public boolean defenderFell;
        public List<Integer> attackerDice = new ArrayList<>();
        public List<Integer> defenderDice = new ArrayList<>();
        public int attackerTotal;
        public int defenderTotal;
        public int attackerModifier;
        public int defenderModifier;
        public boolean attackerWon;
        /** For sieges: whether the castle fell; null for battles between armies. */
        public Boolean captured;
        public String note;
    }
}
