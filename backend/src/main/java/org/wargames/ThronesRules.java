package org.wargames;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The fixed data of The Iron Throne: houses, heirs, garrisons, great seats and the Fate deck. */
final class ThronesRules {
    private ThronesRules() {}

    static final double MOVEMENT_POINTS = 12;
    static final int DEFAULT_WINTER_ROUND = 8;
    /** Snow starts just south of the Wall (y %) and creeps south from Autumn on. */
    static final double SNOW_LINE_START = 16.5;
    static final double SNOW_LINE_MAX = 56;
    static final double SNOW_STEP_AUTUMN = 3;
    static final double SNOW_STEP_WINTER = 4;
    static final int DRAGON_HATCH_ROUND = 4;
    static final int CROWN_ROUNDS_TO_WIN = 3;
    static final int GREAT_SEATS_TO_WIN = 7;
    static final int SEATS_FOR_NIGHT_KING_SLAYER = 3;
    static final int DEAD_CASTLES_TO_WIN = 5;
    static final int MAX_RECRUITS_PER_TURN = 2;
    static final int FORTIFY_COST = 2;
    static final int SELLSWORD_COST = 4;
    static final int SELLSWORD_UNITS = 2;
    static final int LOAN = 6;
    static final int LOAN_REPAYMENT = 8;
    static final int LOAN_ROUNDS = 4;
    static final int OATHBREAKER_ROUNDS = 3;
    static final int ROYAL_WEDDING_ROUNDS = 5;
    static final double FLYING_MILES_PER_POINT = 20;
    static final double SAILING_MILES_PER_POINT = 30;
    /** How close (% of map width) a click must be to a castle to land on it. */
    static final double SNAP_REACH = 0.9;

    static final String NEUTRAL = "neutral";
    static final String DEAD = "dead";
    static final String NIGHT_KING = "Night King";
    static final String KINGS_LANDING = "King's Landing";
    static final String DRAGONSTONE = "Dragonstone";
    static final String BRAAVOS = "Braavos";
    static final String PENTOS = "Pentos";
    static final String DEAD_HOME = "The Lands of Always Winter";
    static final String CASTLE_BLACK = "Castle Black";
    static final String CASTERLY_ROCK = "Casterly Rock";
    static final String THE_EYRIE = "The Eyrie";

    record HouseDef(String id, String name, String seat, int units, List<String> heirs, String power) {}

    static final List<HouseDef> HOUSES = List.of(
        new HouseDef("stark", "House Stark", "Winterfell", 3,
            List.of("Ned Stark", "Robb Stark", "Jon Snow", "Sansa Stark", "Arya Stark", "Bran Stark"),
            "The North Remembers"),
        new HouseDef("lannister", "House Lannister", CASTERLY_ROCK, 3,
            List.of("Tywin Lannister", "Cersei Lannister", "Jaime Lannister", "Tyrion Lannister"),
            "Wildfire"),
        new HouseDef("targaryen", "House Targaryen", PENTOS, 2,
            List.of("Daenerys Targaryen", "Viserys Targaryen"),
            "Dothraki Screamers"),
        new HouseDef("baratheon", "House Baratheon", DRAGONSTONE, 2,
            List.of("Stannis Baratheon", "Shireen Baratheon", "Davos Seaworth"),
            "Shadow Assassin"),
        new HouseDef("greyjoy", "House Greyjoy", "Pyke", 2,
            List.of("Balon Greyjoy", "Euron Greyjoy", "Yara Greyjoy", "Theon Greyjoy"),
            "Reaving"),
        new HouseDef("tyrell", "House Tyrell", "Highgarden", 2,
            List.of("Olenna Tyrell", "Mace Tyrell", "Margaery Tyrell", "Loras Tyrell"),
            "Royal Wedding"),
        new HouseDef("martell", "House Martell", "Sunspear", 2,
            List.of("Doran Martell", "Oberyn Martell", "Ellaria Sand"),
            "The Viper's Duel"),
        new HouseDef(DEAD, "The Dead", DEAD_HOME, 3, List.of(NIGHT_KING), "The Long Night")
    );

    static HouseDef house(String id) {
        return HOUSES.stream().filter(house -> house.id().equals(id)).findFirst().orElse(null);
    }

    static final Set<String> GREAT_SEATS = Set.of("Winterfell", THE_EYRIE, "Riverrun", "Pyke", CASTERLY_ROCK,
        "Highgarden", "Storm's End", "Sunspear", KINGS_LANDING);
    static final Set<String> WALL_CASTLES = Set.of("The Shadow Tower", CASTLE_BLACK, "Eastwatch-by-the-Sea");
    static final Set<String> VALYRIAN_STEEL = Set.of("Jon Snow", "Jaime Lannister", "Arya Stark");
    static final Set<String> NORTHERN_REGIONS = Set.of("The North", "The Wall", "Beyond the Wall");
    static final Set<String> CASTLE_KINDS = Set.of("capital", "seat", "castle", "city", "town");
    static final List<String> DRAGON_NAMES = List.of("Drogon", "Rhaegal", "Viserion");

    private static final Map<String, Integer> GARRISONS = Map.ofEntries(
        Map.entry(KINGS_LANDING, 3), Map.entry(CASTLE_BLACK, 4), Map.entry("Eastwatch-by-the-Sea", 2),
        Map.entry("The Shadow Tower", 2), Map.entry(THE_EYRIE, 3), Map.entry("Storm's End", 3),
        Map.entry("Riverrun", 2), Map.entry("The Twins", 2), Map.entry("Harrenhal", 2),
        Map.entry("Moat Cailin", 2), Map.entry("Oldtown", 2), Map.entry(BRAAVOS, 3),
        Map.entry("Volantis", 3), Map.entry("Qarth", 3), Map.entry("Meereen", 2), Map.entry("Yunkai", 2),
        Map.entry("Astapor", 2));

    /** The starting garrison of a castle that no playing house holds. */
    static int neutralGarrison(String name, String kind) {
        Integer fixed = GARRISONS.get(name);
        if (fixed != null) return fixed;
        return switch (kind) {
            case "seat" -> 3;    // a seat whose house nobody plays
            case "city" -> 2;
            default -> 1;
        };
    }

    record Fate(String id, String title, String text) {}

    static final List<Fate> FATE_DECK = List.of(
        new Fate("winter", "Winter Is Coming", "The seasons turn faster: Winter arrives a round sooner. "
            + "If Winter is already here, the Dead gain 2 units."),
        new Fate("harvest", "Bountiful Harvest", "Every house gains 2 gold."),
        new Fate("blight", "Blight", "No house collects income this round."),
        new Fate("storm", "Storm on the Narrow Sea", "No one can sail this round."),
        new Fate("ravens", "Ravens from the Citadel", "Every hidden army is revealed this round."),
        new Fate("walkers", "The White Walkers Stir", "The Dead gain 2 units."),
        new Fate("wildlings", "Wildling Raid", "Castle Black loses 1 garrison."),
        new Fate("tourney", "Tourney at the Capital", "Whoever holds King's Landing gains 1 unit."),
        new Fate("desertion", "Desertion", "Every army of 4 or more units loses 1."),
        new Fate("sellswords", "Sellswords Flood the Market", "Levies cost 1 gold each this round."),
        new Fate("ironbank", "The Iron Bank Calls", "The richest house pays 3 gold to the Bank."),
        new Fate("comet", "The Red Comet", "Dragon eggs hatch now. If they have hatched, House Targaryen gains 1 unit."),
        new Fate("kingsmoot", "Kingsmoot", "House Greyjoy gains 2 units."),
        new Fate("plague", "Plague in Flea Bottom", "King's Landing loses 1 garrison."),
        new Fate("gold", "Gold of Casterly Rock", "Whoever holds Casterly Rock gains 3 gold.")
    );

    static Fate fate(String id) {
        return FATE_DECK.stream().filter(card -> card.id().equals(id)).findFirst().orElse(null);
    }
}
