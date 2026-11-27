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
        public int round;
        public int turnIndex;
        public List<String> turnOrderIds = new ArrayList<>();
        public List<House> houses = new ArrayList<>();
        public List<Castle> castles = new ArrayList<>();
        public List<Pact> pacts = new ArrayList<>();
        public List<Proposal> proposals = new ArrayList<>();
        public List<String> events = new ArrayList<>();
        /** The round in which Winter begins; Fate can bring it sooner. */
        public int winterRound = ThronesRules.DEFAULT_WINTER_ROUND;
        /** Map y % above which the ground is snow. Starts at the Wall and creeps south. */
        public double snowLine = ThronesRules.SNOW_LINE_START;
        public List<String> fateDeck = new ArrayList<>();
        public String fate;
        public int fateRound;
        public String crownHolder;
        public int crownStreak;
        public int crownSince;
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
        public boolean fortifiedThisTurn;
        public boolean hiredThisTurn;
        public boolean powerUsed;
        public boolean screamers;
        /** Set when this house breaks a pact: attacks on that house this turn get the Red Wedding bonus. */
        public String redWeddingTarget;
        public int oathbreakerUntil;
        /** The round a loan must be repaid by; 0 when there is none. */
        public int loanDue;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pact {
        public String a;
        public String b;
        public int sinceRound;
        /** A Royal Wedding pact cannot be broken before this round. */
        public int lockedUntil;

        public Pact() {}

        Pact(String a, String b, int sinceRound, int lockedUntil) {
            this.a = a;
            this.b = b;
            this.sinceRound = sinceRound;
            this.lockedUntil = lockedUntil;
        }

        boolean joins(String first, String second) {
            return (a.equals(first) && b.equals(second)) || (a.equals(second) && b.equals(first));
        }

        String other(String id) {
            return a.equals(id) ? b : a;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Proposal {
        public String from;
        public String to;
        public boolean royal;

        public Proposal() {}

        Proposal(String from, String to, boolean royal) {
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
