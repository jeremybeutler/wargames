package org.wargames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import org.wargames.ThronesRules.HouseDef;
import org.wargames.ThronesState.BattleLog;
import org.wargames.ThronesState.Castle;
import org.wargames.ThronesState.House;
import org.wargames.ThronesState.Coalition;
import org.wargames.ThronesState.Invite;
import org.wargames.ThronesState.Room;
import org.wargames.model.TerrainRegion;

/**
 * The rules of The Iron Throne. Works on a {@link Room} in memory; the controller loads and
 * saves rooms and checks who is asking. Rule violations throw {@link RuleException}.
 */
final class ThronesGame {
    /** A move or action the rules do not allow; the message is shown to the player. */
    static final class RuleException extends RuntimeException {
        RuleException(String message) {
            super(message);
        }
    }

    record MovePlan(double x, double y, double miles, double movementPoints, String mode,
                    int riverCrossings, String destinationTerrain,
                    List<MovementPathCalculator.Segment> segments, int wallFrom) {}

    private final ThronesMap map;
    private final Random random;
    IntSupplier d6;
    /**
     * Play the Dead's turns at once, inside the engine. Tests do; the server sets this false and
     * plays them one paced turn at a time through BotScheduler, like any other bot.
     */
    boolean inlineAi = true;
    private final Map<Double, List<TerrainRegion>> snowCache = new HashMap<>();

    ThronesGame(ThronesMap map, Random random) {
        this.map = map;
        this.random = random;
        this.d6 = () -> random.nextInt(6) + 1;
    }

    // ------------------------------------------------------------------ lobby

    Room create(String code, String hostName, String hostTokenHash, double mapWidthMiles, int roundLimit,
                boolean computerDead, String createdAt) {
        Room room = new Room();
        room.code = code;
        room.hostName = hostName;
        room.hostTokenHash = hostTokenHash;
        room.mapWidthMiles = mapWidthMiles;
        room.roundLimit = roundLimit;
        room.computerDead = computerDead;
        room.createdAt = createdAt;
        for (HouseDef def : ThronesRules.HOUSES) {
            House house = new House();
            house.id = def.id();
            house.name = def.name();
            house.seat = def.seat();
            house.heirs = new ArrayList<>(def.heirs());
            house.leader = house.heirs.get(0);
            house.units = def.units();
            house.initialUnits = def.units();
            room.houses.add(house);
        }
        room.events.add("The lobby is open. Claim a house to play.");
        return room;
    }

    void join(Room room, String houseId, String playerName, String heir, String tokenHash) {
        if (!"lobby".equals(room.status)) throw new RuleException("This game has already started.");
        House house = house(room, houseId);
        if (ThronesRules.isDead(house.id)) throw new RuleException("The Dead are played by the server.");
        if (house.claimed) throw new RuleException(house.name + " is already taken.");
        if (heir != null && !heir.isBlank()) {
            String choice = heir.trim();
            if (!house.heirs.contains(choice)) throw new RuleException(choice + " is not of " + house.name + ".");
            house.heirs.remove(choice);
            house.heirs.add(0, choice);
            house.leader = choice;
        }
        house.claimed = true;
        house.playerName = playerName;
        house.playerTokenHash = tokenHash;
        room.events.add(playerName + " joined as " + house.leader + " of " + house.name + ".");
    }

    void start(Room room) {
        if (!"lobby".equals(room.status)) throw new RuleException("The game has already started.");
        long players = room.houses.stream().filter(house -> house.claimed && !ThronesRules.isDead(house.id)).count();
        if (players < ThronesRules.MIN_HOUSES) {
            throw new RuleException("At least " + ThronesRules.MIN_HOUSES + " houses must be claimed (by players or bots) to start.");
        }
        // The Dead are always in the game: three armies the server plays.
        for (House house : room.houses) {
            if (!ThronesRules.isDead(house.id)) continue;
            house.ai = true;
            house.bot = "dead";
            house.claimed = true;
            house.playerName = "The server";
        }
        House dead = house(room, ThronesRules.DEAD);

        for (House house : room.houses) {
            house.alive = house.claimed || house.ai;
            if (!house.alive) continue;
            ThronesMap.Place seat = map.place(house.seat);
            house.x = seat.x();
            house.y = seat.y();
            house.terrain = terrainAt(room, house.x, house.y, house.id);
            house.hidden = concealedIn(house.terrain);
        }
        for (ThronesMap.Place place : map.places()) {
            boolean deadHome = "dead".equals(place.kind());
            if (!ThronesRules.CASTLE_KINDS.contains(place.kind()) && !deadHome) continue;
            if (deadHome && !dead.alive) continue;
            Castle castle = new Castle();
            castle.id = slug(place.name());
            castle.name = place.name();
            castle.kind = place.kind();
            castle.region = place.region();
            castle.x = place.x();
            castle.y = place.y();
            castle.port = place.port();
            castle.great = ThronesRules.GREAT_SEATS.contains(place.name());
            House owner = room.houses.stream().filter(house -> house.alive && house.seat.equals(place.name()))
                .findFirst().orElse(null);
            castle.owner = owner == null ? ThronesRules.NEUTRAL : owner.id;
            castle.units = owner == null ? ThronesRules.neutralGarrison(place.name(), place.kind()) : 2;
            castle.initialUnits = castle.units;
            room.castles.add(castle);
        }

        room.status = "campaign";
        room.round = 1;
        room.turnOrderIds = room.houses.stream().filter(house -> house.alive).map(house -> house.id)
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(room.turnOrderIds, random);
        room.fateDeck = shuffledDeck();
        room.turnIndex = 0;
        room.events.add("Turn order: " + room.turnOrderIds.stream().map(id -> house(room, id).name)
            .collect(Collectors.joining(", ")) + ".");
        room.events.add("Beyond the Wall, the Dead wait for Winter. Hold every castle in the realm to win, together or alone.");
        room.events.add("Round 1 begins. It is Summer.");
        beginTurn(room);
        runComputerTurns(room);
    }

    // ------------------------------------------------------------------ movement

    MovePlan plan(Room room, House mover, double requestedX, double requestedY) {
        if (requestedX < 0 || requestedX > 100 || requestedY < 0 || requestedY > 100) {
            throw new RuleException("The destination must be on the map.");
        }
        double[] target = snap(room, requestedX, requestedY);
        double x = target[0];
        double y = target[1];
        if (!map.isLand(x, y)) throw new RuleException("Armies must end their move on land.");
        double water = map.waterFraction(mover.x, mover.y, x, y);
        boolean flying = !mover.dragons.isEmpty();
        MovementPathCalculator.Result land = MovementPathCalculator.calculate(mover.x, mover.y, x, y,
            room.mapWidthMiles, Double.NaN, Double.NaN, terrain(room), rateOverrides(mover));
        String destination = land.destinationTerrain();

        if (flying) {
            double points = land.miles() / ThronesRules.FLYING_MILES_PER_POINT;
            return new MovePlan(x, y, land.miles(), points, "fly", 0, destination,
                List.of(new MovementPathCalculator.Segment("flight", land.miles(), points)), 0);
        }
        int wallFrom = checkWall(room, mover, x, y);
        if (water <= 0) {
            return new MovePlan(x, y, land.miles(), land.movementPoints(), "land", land.riverCrossings(),
                destination, land.segments(), wallFrom);
        }
        if (fateActive(room, "storm")) throw new RuleException("A storm on the Narrow Sea: no one can sail this round.");
        if (ThronesRules.isDead(mover.id)) throw new RuleException("The Dead cannot cross water.");
        if ("greyjoy".equals(mover.id)) {
            if (!map.nearWater(mover.x, mover.y, 2.5) || !map.nearWater(x, y, 2.5)) {
                throw new RuleException("The Iron Fleet sails from one coast to another: start and end near the sea.");
            }
            double seaMiles = land.miles() * water;
            double points = land.movementPoints() * (1 - water) + seaMiles / ThronesRules.SAILING_MILES_PER_POINT;
            return new MovePlan(x, y, land.miles(), points, "sail", 0, destination,
                List.of(new MovementPathCalculator.Segment("sea", seaMiles,
                    seaMiles / ThronesRules.SAILING_MILES_PER_POINT)), wallFrom);
        }
        Castle from = castleAt(room, mover.x, mover.y);
        Castle to = castleAt(room, x, y);
        if (from == null || !from.port || to == null || !to.port) {
            throw new RuleException("That route crosses water. Sail from a port to a port.");
        }
        if (mover.moved || mover.movementRemaining < mover.movementMax - 0.0001) {
            throw new RuleException("Sailing takes your whole turn's movement; start it before moving.");
        }
        return new MovePlan(x, y, land.miles(), mover.movementMax, "sail", 0, destination,
            List.of(new MovementPathCalculator.Segment("sea", land.miles(), mover.movementMax)), wallFrom);
    }

    /**
     * The Wall can only be crossed by stepping onto it at a castle you (or an ally) hold, or once
     * the Dead have breached it. Returns the side the party comes from when it steps onto the Wall.
     */
    private int checkWall(Room room, House mover, double x, double y) {
        int from = map.wallSide(mover.x, mover.y);
        int to = map.wallSide(x, y);
        if (to == 0) return from == 0 ? mover.wallFrom : from;
        if (room.wallBreached) return 0;
        if (from != 0 && from != to) {
            throw new RuleException("The Wall blocks the way. Step onto a castle on the Wall you hold, then cross.");
        }
        if (from == 0 && mover.wallFrom != 0 && to != mover.wallFrom) {
            Castle here = castleAt(room, mover.x, mover.y);
            if (here == null || !friendly(room, mover.id, here.owner)) {
                throw new RuleException("You must hold this castle on the Wall to pass through it.");
            }
        }
        return 0;
    }

    void move(Room room, House mover, double x, double y) {
        requireTurn(room, mover);
        if (mover.moved) throw new RuleException("Your army has already moved this turn.");
        if (mover.attacked) throw new RuleException("An army cannot move after attacking.");
        MovePlan plan = plan(room, mover, x, y);
        if (plan.movementPoints() > mover.movementRemaining + 0.0001) {
            throw new RuleException(String.format(Locale.ROOT,
                "That move costs %.1f movement points; only %.1f remain.", plan.movementPoints(), mover.movementRemaining));
        }
        mover.x = plan.x();
        mover.y = plan.y();
        mover.wallFrom = plan.wallFrom();
        mover.moved = true;
        mover.movementRemaining = Math.max(0, mover.movementRemaining - plan.movementPoints());
        mover.terrain = plan.destinationTerrain();
        mover.hidden = mover.visibleUntilRound < room.round && concealedIn(mover.terrain);
        House met = room.houses.stream().filter(other -> other.alive && !other.id.equals(mover.id)
            && !allied(room, mover.id, other.id) && same(mover.x, mover.y, other.x, other.y)).findFirst().orElse(null);
        if (met != null) {
            reveal(room, mover);
            reveal(room, met);
            room.events.add(mover.leader + " met " + met.leader + "'s army.");
        } else if (!mover.hidden) {
            Castle castle = castleAt(room, mover.x, mover.y);
            String verb = switch (plan.mode()) {
                case "fly" -> "flew";
                case "sail" -> "sailed";
                default -> "marched";
            };
            room.events.add(String.format(Locale.ROOT, "%s %s %.0f miles%s.", mover.leader, verb, plan.miles(),
                castle == null ? "" : " to " + castle.name));
        }
    }

    // ------------------------------------------------------------------ battle

    void attack(Room room, House attacker, String targetId) {
        requireTurn(room, attacker);
        if (attacker.attacksLeft <= 0) throw new RuleException("Your army has already attacked this turn.");
        List<House> enemies = enemiesAt(room, attacker);
        House targetHouse = room.houses.stream().filter(house -> house.id.equals(targetId)).findFirst().orElse(null);
        if (targetHouse != null) {
            if (allied(room, attacker.id, targetHouse.id)) {
                throw new RuleException("You have a pact with " + targetHouse.name + ". Break it first.");
            }
            if (!enemies.contains(targetHouse)) throw new RuleException("That army is not here.");
            spendAttack(attacker);
            fight(room, attacker, targetHouse);
            return;
        }
        Castle castle = room.castles.stream().filter(item -> item.id.equals(targetId)).findFirst()
            .orElseThrow(() -> new RuleException("Choose an army or castle to attack."));
        if (!same(attacker.x, attacker.y, castle.x, castle.y)) throw new RuleException("Move onto " + castle.name + " to attack it.");
        if (castle.owner.equals(attacker.id)) throw new RuleException("You already hold " + castle.name + ".");
        if (allied(room, attacker.id, castle.owner)) {
            throw new RuleException(house(room, castle.owner).name + " is your ally. Break the pact first.");
        }
        if (!enemies.isEmpty()) throw new RuleException("Defeat the army defending " + castle.name + " first.");
        if (ThronesRules.isDead(attacker.id) && !winter(room)) {
            throw new RuleException("The Dead cannot attack until Winter comes.");
        }
        spendAttack(attacker);
        siege(room, attacker, castle);
    }

    private static void spendAttack(House attacker) {
        attacker.attacksLeft -= 1;
        attacker.attacked = true;
    }

    List<House> enemiesAt(Room room, House house) {
        return room.houses.stream().filter(other -> other.alive && !other.id.equals(house.id)
            && !allied(room, house.id, other.id) && same(house.x, house.y, other.x, other.y)).toList();
    }

    void fight(Room room, House attacker, House defender) {
        List<House> helpers = room.houses.stream().filter(other -> other.alive && other != defender
            && other != attacker && allied(room, defender.id, other.id) && same(other.x, other.y, defender.x, defender.y))
            .toList();
        int defenseDice = dice(room, defender) + helpers.stream().mapToInt(helper -> helper.units).sum();
        int attackModifier = attackModifier(room, attacker, defender.id, null);
        int defenseModifier = defenseModifier(room, defender, attacker);
        Battle.Result result = Battle.roll(dice(room, attacker), attackModifier, defenseDice, defenseModifier, d6);
        reveal(room, attacker);
        reveal(room, defender);
        if (ThronesRules.isDead(defender.id)) forge(room, attacker);
        if (ThronesRules.isDead(attacker.id)) {
            forge(room, defender);
            helpers.forEach(helper -> forge(room, helper));
        }
        House winner = result.attackerWon() ? attacker : defender;
        House loser = result.attackerWon() ? defender : attacker;
        BattleLog log = log(room, attacker, defender.id, defender.leader + " (" + defender.name + ")", result,
            attackModifier, defenseModifier);
        String note = describe(room, attacker.leader, defender.leader, result);
        if (!helpers.isEmpty()) {
            note += " " + helpers.stream().map(helper -> helper.name).collect(Collectors.joining(", "))
                + " fought beside " + defender.name + ".";
        }
        room.events.add(note);
        boolean moonDoor = loser == attacker && atPlace(defender.x, defender.y, ThronesRules.THE_EYRIE);
        loseUnits(room, loser, moonDoor ? 2 : 1, winner);
        if (moonDoor && attacker.alive) room.events.add("The Moon Door: " + attacker.leader + " lost 2 units at the Eyrie.");
        armyOfTheDead(room, winner);
        log.attackerUnits = attacker.units;
        log.attackerFell = !attacker.alive;
        log.defenderUnits = defender.units;
        log.defenderFell = !defender.alive;
        log.note = note;
        room.lastBattle = log;
    }

    void siege(Room room, House attacker, Castle castle) {
        int attackModifier = attackModifier(room, attacker, castle.owner, castle);
        int defenseModifier = castleDefenseModifier(room, castle);
        Battle.Result result = Battle.roll(dice(room, attacker), attackModifier, Math.max(1, castle.units),
            defenseModifier, d6);
        reveal(room, attacker);
        if (ThronesRules.isDead(castle.owner)) forge(room, attacker);
        BattleLog log = log(room, attacker, castle.id, castle.name, result, attackModifier, defenseModifier);
        String outcome;
        if (result.attackerWon()) {
            if (castle.units == 0) {
                String previous = castle.owner;
                castle.owner = attacker.id;
                log.captured = true;
                outcome = attacker.name + " captured " + castle.name + "!";
                if (ThronesRules.WALL_CASTLES.contains(castle.name) && ThronesRules.isDead(attacker.id)) {
                    room.wallBreached = true;
                    outcome += " The Wall is breached.";
                }
                if (!ThronesRules.NEUTRAL.equals(previous) && !ThronesRules.isDead(previous)) {
                    House loserHouse = house(room, previous);
                    if (loserHouse.alive && loserHouse.seat.equals(castle.name)) {
                        outcome += " " + loserHouse.name + " has lost its seat.";
                    }
                }
            } else {
                int burned = attacker.dragons.isEmpty() ? 1 : 2;
                castle.units = Math.max(0, castle.units - burned);
                log.captured = false;
                outcome = burned == 2 ? "Dragonfire burned 2 of " + castle.name + "'s garrison."
                    : castle.name + "'s garrison lost 1 unit.";
            }
        } else {
            log.captured = false;
            boolean moonDoor = ThronesRules.THE_EYRIE.equals(castle.name);
            outcome = castle.name + " held." + (moonDoor ? " The Moon Door: the attackers lose 2 units." : "");
            House owner = ThronesRules.NEUTRAL.equals(castle.owner) ? null : house(room, castle.owner);
            loseUnits(room, attacker, moonDoor ? 2 : 1, owner);
        }
        String note = describe(room, attacker.leader, castle.name, result) + " " + outcome;
        room.events.add(note);
        log.attackerUnits = attacker.units;
        log.attackerFell = !attacker.alive;
        log.defenderUnits = castle.units;
        log.note = note;
        room.lastBattle = log;
    }

    private String describe(Room room, String attacker, String defender, Battle.Result result) {
        return String.format(Locale.ROOT, "%s attacked %s: %s vs. %s.", attacker, defender,
            dice(result.attackerDice(), result.attackerTotal()), dice(result.defenderDice(), result.defenderTotal()));
    }

    private static String dice(List<Integer> dice, int total) {
        return dice.stream().map(String::valueOf).collect(Collectors.joining("-"))
            + (dice.size() > 1 ? " (" + total + ")" : "");
    }

    private BattleLog log(Room room, House attacker, String defenderId, String defenderName, Battle.Result result,
                          int attackModifier, int defenseModifier) {
        BattleLog log = new BattleLog();
        log.id = ++room.battleCount;
        log.round = room.round;
        log.attackerId = attacker.id;
        log.attacker = attacker.leader + " (" + attacker.name + ")";
        log.defenderId = defenderId;
        log.defender = defenderName;
        log.attackerDice = new ArrayList<>(result.attackerDice());
        log.defenderDice = new ArrayList<>(result.defenderDice());
        log.attackerTotal = result.attackerTotal();
        log.defenderTotal = result.defenderTotal();
        log.attackerModifier = attackModifier;
        log.defenderModifier = defenseModifier;
        log.attackerWon = result.attackerWon();
        return log;
    }

    /** One die per unit (a lone leader rolls one), plus two per dragon. */
    int dice(Room room, House house) {
        int dice = Math.max(1, house.units) + 2 * house.dragons.size();
        if (ThronesRules.DEAD.equals(house.id) && room.iceDragon) dice += 2;
        return dice;
    }

    private int attackModifier(Room room, House attacker, String targetOwner, Castle castle) {
        int modifier = 0;
        if (attacker.screamers) modifier += 1;
        if (targetOwner != null && attacker.redWeddingTargets.contains(targetOwner)) modifier += 2;
        if ("baratheon".equals(attacker.id) && ThronesRules.isDead(targetOwner)) modifier += 1;
        if (sworn(room, attacker)) modifier += ThronesRules.SWORN_BONUS;
        if ("greyjoy".equals(attacker.id) && castle != null && (castle.port || map.nearWater(castle.x, castle.y, 2.0))) {
            modifier += 1;
        }
        return modifier;
    }

    private int defenseModifier(Room room, House defender, House attacker) {
        int modifier = 0;
        String region = map.regionAt(defender.x, defender.y);
        if ("stark".equals(defender.id) && ThronesRules.NORTHERN_REGIONS.contains(region)) modifier += 1;
        if ("martell".equals(defender.id) && "Dorne".equals(region)) modifier += 1;
        if ("baratheon".equals(defender.id) && ThronesRules.isDead(attacker.id)) modifier += 1;
        if (sworn(room, defender)) modifier += ThronesRules.SWORN_BONUS;
        if ("volcanic".equals(defender.terrain)) modifier -= 1;
        return modifier;
    }

    private int castleDefenseModifier(Room room, Castle castle) {
        if ("stark".equals(castle.owner) && ThronesRules.NORTHERN_REGIONS.contains(castle.region)) return 1;
        if ("martell".equals(castle.owner) && "Dorne".equals(castle.region)) return 1;
        return 0;
    }

    private void armyOfTheDead(Room room, House winner) {
        if (winner != null && winner.alive && ThronesRules.isDead(winner.id)) {
            winner.units += 1;
            room.events.add("The fallen rise: the Army of the Dead grows to " + winner.units + ".");
        }
    }

    /** Removes units; an army with none left falls (a dragon dies first; the Night King may retreat). */
    void loseUnits(Room room, House house, int count, House killer) {
        for (int index = 0; index < count && house.alive; index++) {
            if (house.units > 0) {
                house.units -= 1;
            } else {
                fall(room, house, killer);
                return;
            }
        }
    }

    private void fall(Room room, House house, House killer) {
        if (!house.dragons.isEmpty()) {
            String dragon = house.dragons.remove(house.dragons.size() - 1);
            if (killer != null && ThronesRules.isDead(killer.id)) {
                room.iceDragon = true;
                room.wallBreached = true;
                room.events.add(dragon + " has fallen, and the Night King raises it as an ice dragon. The Wall cannot hold.");
            } else {
                room.events.add(dragon + " has fallen defending " + house.leader + ".");
            }
            return;
        }
        if (ThronesRules.isDead(house.id) && !ThronesRules.DEAD.equals(house.id)) {
            eliminate(room, house, house.leader + " is destroyed.");
            return;
        }
        if (ThronesRules.DEAD.equals(house.id)) {
            if (canSlayNightKing(room, killer)) {
                slayNightKing(room, killer);
            } else {
                ThronesMap.Place home = map.place(ThronesRules.DEAD_HOME);
                house.x = home.x();
                house.y = home.y();
                house.units = 3;
                house.wallFrom = 0;
                room.events.add("The Night King cannot be slain without dragonglass, dragonfire or Valyrian steel. "
                    + "He withdraws to the Lands of Always Winter.");
            }
            return;
        }
        succession(room, house, false);
    }

    boolean canSlayNightKing(Room room, House killer) {
        if (killer == null || !killer.alive) return false;
        if (!killer.dragons.isEmpty() || ThronesRules.VALYRIAN_STEEL.contains(killer.leader)) return true;
        Castle dragonstone = castleNamed(room, ThronesRules.DRAGONSTONE);
        return dragonstone != null && friendly(room, killer.id, dragonstone.owner);
    }

    private void slayNightKing(Room room, House killer) {
        room.events.add(killer.leader + " has slain the Night King! Every army of the Dead crumbles to dust.");
        for (House host : room.houses) {
            if (ThronesRules.isDead(host.id)) eliminate(room, host, null);
        }
        for (Castle castle : room.castles) {
            if (ThronesRules.isDead(castle.owner)) castle.owner = ThronesRules.NEUTRAL;
        }
        killer.gold += 6;
        for (House house : room.houses) {
            if (house.alive) house.units += 2;
        }
        room.events.add("The realm rejoices: " + killer.name + " gains 6 gold and every living house gains 2 units.");
    }

    /** The leader dies. The next heir takes over: in place, or rising at the house's seat with 1 unit. */
    void succession(Room room, House house, boolean inPlace) {
        String fallen = house.leader;
        house.heirIndex += 1;
        if (house.heirIndex >= house.heirs.size()) {
            eliminate(room, house, fallen + " was the last of the line.");
            return;
        }
        house.leader = house.heirs.get(house.heirIndex);
        if (!inPlace) {
            Castle home = room.castles.stream().filter(castle -> castle.owner.equals(house.id)
                    && castle.name.equals(house.seat)).findFirst()
                .orElseGet(() -> room.castles.stream().filter(castle -> castle.owner.equals(house.id))
                    .min(Comparator.comparingDouble(castle -> ThronesMap.distance(castle.x, castle.y, house.x, house.y)))
                    .orElse(null));
            if (home == null) {
                eliminate(room, house, fallen + " has died, and " + house.name + " holds no castle for "
                    + house.leader + " to rise at.");
                return;
            }
            house.x = home.x;
            house.y = home.y;
            house.units = 1;
            house.wallFrom = 0;
            house.terrain = terrainAt(room, house.x, house.y, house.id);
            reveal(room, house);
            room.events.add("Valar morghulis. " + fallen + " has died; " + house.leader + " now leads " + house.name
                + " from " + home.name + ".");
        } else {
            room.events.add("Valar morghulis. " + fallen + " has died; " + house.leader + " now leads " + house.name + ".");
        }
    }

    void eliminate(Room room, House house, String reason) {
        if (!house.alive) return;
        List<House> order = turnOrder(room);
        int index = order.indexOf(house);
        house.alive = false;
        if (index >= 0 && index < room.turnIndex) room.turnIndex -= 1;
        for (Castle castle : room.castles) {
            if (castle.owner.equals(house.id)) castle.owner = ThronesRules.NEUTRAL;
        }
        for (Coalition coalition : new ArrayList<>(room.coalitions)) {
            coalition.members.remove(house.id);
            if (coalition.members.size() < 2) room.coalitions.remove(coalition);
        }
        room.invites.removeIf(invite -> invite.from.equals(house.id) || invite.to.equals(house.id));
        room.events.add((reason == null ? "" : reason + " ") + house.name + " is no more.");
    }

    // ------------------------------------------------------------------ gold and armies

    int recruitCost(Room room, House house) {
        if (fateActive(room, "levies") || "tyrell".equals(house.id)) return 1;
        return 2;
    }

    void recruit(Room room, House house, int count) {
        requireTurn(room, house);
        if (ThronesRules.isDead(house.id)) throw new RuleException("The Dead raise no levies; they grow by winning.");
        if (count < 1) throw new RuleException("Raise at least one unit.");
        Castle castle = castleAt(room, house.x, house.y);
        if (castle == null || !castle.owner.equals(house.id)) throw new RuleException("Raise troops at a castle you hold.");
        if (house.recruitedThisTurn + count > ThronesRules.MAX_RECRUITS_PER_TURN) {
            throw new RuleException("You can raise at most " + ThronesRules.MAX_RECRUITS_PER_TURN + " units a turn.");
        }
        int cost = recruitCost(room, house) * count;
        if (house.gold < cost) throw new RuleException("That costs " + cost + " gold; you have " + house.gold + ".");
        house.gold -= cost;
        house.units += count;
        house.recruitedThisTurn += count;
        room.events.add(String.format(Locale.ROOT, "%s raised %d unit%s at %s for %d gold.", house.name, count,
            count == 1 ? "" : "s", castle.name, cost));
    }

    int income(Room room, House house) {
        if (ThronesRules.isDead(house.id)) return 0;
        if (house.oathbreakerUntil >= room.round || fateActive(room, "blight")) return 0;
        int income = "lannister".equals(house.id) ? 2 : 0;
        for (Castle castle : room.castles) {
            if (!castle.owner.equals(house.id)) continue;
            int value = ThronesRules.KINGS_LANDING.equals(castle.name) ? 3 : castle.name.equals(house.seat) ? 2 : 1;
            if (winter(room) && ThronesRules.NORTHERN_REGIONS.contains(castle.region)) value -= 1;
            income += Math.max(0, value);
        }
        return income;
    }

    // ------------------------------------------------------------------ coalitions

    static Coalition coalitionOf(Room room, String houseId) {
        return room.coalitions.stream().filter(coalition -> coalition.members.contains(houseId)).findFirst().orElse(null);
    }

    /** This house and its living coalition partners: the side that wins or loses together. */
    List<House> side(Room room, House house) {
        Coalition coalition = coalitionOf(room, house.id);
        if (coalition == null) return List.of(house);
        return coalition.members.stream().map(id -> house(room, id)).filter(member -> member.alive).toList();
    }

    /** Invite another house to join forces; it answers when it likes (bots answer at once). */
    void invite(Room room, House from, String toId, boolean royal) {
        House to = house(room, toId);
        if (from.id.equals(to.id)) throw new RuleException("Choose another house.");
        if (ThronesRules.isDead(from.id) || ThronesRules.isDead(to.id)) throw new RuleException("No one joins forces with the Dead.");
        if (!from.alive) throw new RuleException(from.name + " is no more.");
        if (!to.alive) throw new RuleException(to.name + " is no more.");
        if (allied(room, from.id, to.id)) throw new RuleException("You are already sworn to " + to.name + ".");
        if (from.oathbreakerUntil >= room.round) {
            throw new RuleException("No one trusts an oathbreaker yet. You can make new allies after round " + from.oathbreakerUntil + ".");
        }
        if (to.oathbreakerUntil >= room.round) {
            throw new RuleException(to.name + " broke its oath; no one may join it before round " + (to.oathbreakerUntil + 1) + ".");
        }
        if (room.invites.stream().anyMatch(invite -> invite.from.equals(from.id) && invite.to.equals(to.id))) {
            throw new RuleException("You have already invited " + to.name + ".");
        }
        if (royal) {
            if (!"tyrell".equals(from.id)) throw new RuleException("Only House Tyrell arranges Royal Weddings.");
            if (from.powerUsed) throw new RuleException("House Tyrell has already used its power.");
            from.powerUsed = true;
        }
        room.invites.add(new Invite(from.id, to.id, royal));
        room.events.add(from.name + " invited " + to.name + (royal ? " to a Royal Wedding." : " to join forces."));
        if (to.bot != null) respond(room, to, from.id, ThronesBot.acceptsInvitation(this, room, to, from, random));
    }

    void respond(Room room, House to, String fromId, boolean accept) {
        Invite invite = room.invites.stream().filter(item -> item.from.equals(fromId) && item.to.equals(to.id)).findFirst()
            .orElseThrow(() -> new RuleException("There is no such invitation."));
        room.invites.remove(invite);
        House from = house(room, fromId);
        if (!accept) {
            if (invite.royal) from.powerUsed = false;
            room.events.add(to.name + " refused " + from.name + "'s invitation.");
            return;
        }
        if (!from.alive) throw new RuleException(from.name + " is no more.");
        if (to.oathbreakerUntil >= room.round) throw new RuleException("No one trusts an oathbreaker yet.");
        joinForces(room, from, to);
        room.invites.removeIf(item -> allied(room, item.from, item.to));
        String members = side(room, to).stream().map(member -> member.name).collect(Collectors.joining(", "));
        if (invite.royal) {
            int until = room.round + ThronesRules.ROYAL_WEDDING_ROUNDS;
            from.lockedUntil = Math.max(from.lockedUntil, until);
            to.lockedUntil = Math.max(to.lockedUntil, until);
            to.units += 2;
            room.events.add("A Royal Wedding! " + from.name + " and " + to.name + " are bound until round " + until
                + ", and " + to.name + " gains 2 units. The coalition: " + members + ".");
        } else {
            room.events.add(to.name + " joined forces with " + from.name + ". The coalition: " + members + ".");
        }
    }

    private void joinForces(Room room, House a, House b) {
        Coalition first = coalitionOf(room, a.id);
        Coalition second = coalitionOf(room, b.id);
        if (first == null && second == null) {
            Coalition coalition = new Coalition("c" + (++room.coalitionCount), room.round);
            coalition.members.add(a.id);
            coalition.members.add(b.id);
            room.coalitions.add(coalition);
        } else if (first == null) {
            second.members.add(a.id);
        } else if (second == null) {
            first.members.add(b.id);
        } else if (first != second) {
            first.members.addAll(second.members);
            room.coalitions.remove(second);
        }
    }

    /** Leave your coalition on your turn. Attack a former ally this turn for a Red Wedding. */
    void leave(Room room, House house) {
        requireTurn(room, house);
        Coalition coalition = coalitionOf(room, house.id);
        if (coalition == null) throw new RuleException("You are not part of a coalition.");
        if (house.lockedUntil >= room.round) {
            throw new RuleException("A Royal Wedding binds you until after round " + house.lockedUntil + ".");
        }
        coalition.members.remove(house.id);
        house.redWeddingTargets = new ArrayList<>(coalition.members);
        house.oathbreakerUntil = room.round + ThronesRules.OATHBREAKER_ROUNDS;
        house.forged = false;
        if (coalition.members.size() < 2) room.coalitions.remove(coalition);
        room.invites.removeIf(invite -> invite.from.equals(house.id) || invite.to.equals(house.id));
        room.events.add(house.name + " broke its oath and left its coalition. No one will join it before round "
            + (house.oathbreakerUntil + 1) + ".");
    }

    /** Coalition partners, and the Dead's armies with each other. */
    boolean allied(Room room, String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        if (ThronesRules.isDead(a) && ThronesRules.isDead(b)) return true;
        Coalition coalition = coalitionOf(room, a);
        return coalition != null && coalition.members.contains(b);
    }

    /** The same house, or an ally. */
    boolean friendly(Room room, String house, String owner) {
        return house.equals(owner) || allied(room, house, owner);
    }

    /**
     * Sworn bonds: a house forged in winter (it fought the Dead during the Long Night while in a
     * coalition) gets +1 on every die while another forged house stands in its coalition.
     */
    boolean sworn(Room room, House house) {
        if (!house.forged) return false;
        Coalition coalition = coalitionOf(room, house.id);
        return coalition != null && coalition.members.stream()
            .anyMatch(id -> !id.equals(house.id) && livingHouse(room, id) != null && house(room, id).forged);
    }

    private void forge(Room room, House house) {
        if (house == null || house.forged || !house.alive || !winter(room) || ThronesRules.isDead(house.id)) return;
        if (coalitionOf(room, house.id) == null) return;
        house.forged = true;
        if (sworn(room, house)) {
            String names = side(room, house).stream().filter(member -> member.forged).map(member -> member.name)
                .collect(Collectors.joining(" and "));
            room.events.add("Forged in winter: " + names + " fight as one. +1 on every die for the rest of the game.");
        } else {
            room.events.add(house.name + " fought the Dead. When a coalition partner does too, their bond is forged in winter.");
        }
    }

    // ------------------------------------------------------------------ powers

    void usePower(Room room, House house, String target) {
        requireTurn(room, house);
        if (house.powerUsed) throw new RuleException(house.name + " has already used its power.");
        switch (house.id) {
            case "stark" -> {
                Castle castle = castleAt(room, house.x, house.y);
                if (castle == null || !castle.owner.equals(house.id) || !"The North".equals(castle.region)) {
                    throw new RuleException("The bannermen gather at a Northern castle you hold.");
                }
                house.units += 3;
                room.events.add("The North remembers! 3 bannermen rally to " + house.leader + " at " + castle.name + ".");
            }
            case "lannister" -> {
                if (!atPlace(house.x, house.y, ThronesRules.KINGS_LANDING)) throw new RuleException("Wildfire waits beneath King's Landing.");
                room.events.add("Wildfire! Green flame bursts beneath King's Landing.");
                for (House enemy : enemiesAt(room, house)) loseUnits(room, enemy, 2, house);
                Castle city = castleNamed(room, ThronesRules.KINGS_LANDING);
                if (city != null && !friendly(room, house.id, city.owner)) city.units = Math.max(0, city.units - 2);
            }
            case "targaryen" -> {
                if (house.attacked) throw new RuleException("Call the screamers before your first attack.");
                house.screamers = true;
                house.attacksLeft = 2;
                room.events.add("Dothraki screamers ride with " + house.leader + ": two attacks this turn, +1 per die.");
            }
            case "baratheon" -> {
                House victim = house(room, target);
                if (!victim.alive || victim.id.equals(house.id) || ThronesRules.isDead(victim.id)) {
                    throw new RuleException("Choose a living rival house.");
                }
                if (victim.heirIndex >= victim.heirs.size() - 1) {
                    throw new RuleException("Even shadows cannot end a house's last heir.");
                }
                room.events.add("A shadow with Stannis's face slips through the night...");
                succession(room, victim, true);
            }
            case "greyjoy" -> {
                Castle castle = castleAt(room, house.x, house.y);
                if (castle == null || friendly(room, house.id, castle.owner)) throw new RuleException("Stand at a castle to reave.");
                if (!(castle.port || map.nearWater(castle.x, castle.y, 2.0))) throw new RuleException("Only coastal castles can be reaved.");
                if (castle.units > 1) throw new RuleException(castle.name + " is too well defended to reave.");
                if (!enemiesAt(room, house).isEmpty()) throw new RuleException("An army defends " + castle.name + ".");
                castle.owner = house.id;
                room.events.add("What is dead may never die! The ironborn reaved " + castle.name + ".");
            }
            case "tyrell" -> throw new RuleException("Offer a Royal Wedding from the Houses panel.");
            case "martell" -> {
                House rival = house(room, target);
                if (!rival.alive || rival.id.equals(house.id) || ThronesRules.isDead(rival.id)
                    || !same(house.x, house.y, rival.x, rival.y)) {
                    throw new RuleException("Challenge a leader whose army stands with yours.");
                }
                int mine;
                int theirs;
                do {
                    mine = d6.getAsInt() + d6.getAsInt();
                    theirs = d6.getAsInt() + d6.getAsInt();
                } while (mine == theirs);
                House loser = mine > theirs ? rival : house;
                room.events.add(String.format(Locale.ROOT, "Trial by combat: %s rolls %d, %s rolls %d.",
                    house.leader, mine, rival.leader, theirs));
                house.powerUsed = true;
                succession(room, loser, true);
                return;
            }
            case ThronesRules.DEAD, "deadfrost", "deadhard" -> {
                if (!winter(room)) throw new RuleException("The Long Night falls only in Winter.");
                if (house.moved) throw new RuleException("Call the Long Night before you move.");
                house.movementRemaining = house.movementMax * 2;
                room.events.add("The Long Night falls. The Dead march twice as far.");
            }
            default -> throw new RuleException("This house has no power.");
        }
        house.powerUsed = true;
    }

    // ------------------------------------------------------------------ turns and rounds

    void endTurn(Room room, House house) {
        requireTurn(room, house);
        advanceTurn(room);
    }

    /** Call after any action: if the active house fell or was eliminated, move the turn on. */
    void afterAction(Room room, String actorId) {
        if (!room.winners.isEmpty()) return;
        checkLastStanding(room);
        if (!room.winners.isEmpty()) return;
        House actor = house(room, actorId);
        if (actor.alive) return;
        List<House> order = turnOrder(room);
        if (room.turnIndex >= order.size()) {
            room.turnIndex = order.size() - 1;
            advanceTurn(room);
        } else {
            beginTurn(room);
            runComputerTurns(room);
        }
    }

    void advanceTurn(Room room) {
        if (!room.winners.isEmpty()) return;
        List<House> order = turnOrder(room);
        if (room.turnIndex + 1 < order.size()) {
            room.turnIndex += 1;
        } else {
            endRound(room);
            if (!room.winners.isEmpty()) return;
            startRound(room);
            room.turnIndex = 0;
        }
        beginTurn(room);
        runComputerTurns(room);
    }

    private void runComputerTurns(Room room) {
        for (int guard = 0; guard < 8 && inlineAi && room.winners.isEmpty() && "campaign".equals(room.status); guard++) {
            House active = current(room);
            if (!active.ai) return;
            new ThronesDeadAi(this, map).play(room, active);
            if (!room.winners.isEmpty()) return;
            checkLastStanding(room);
            if (!room.winners.isEmpty()) return;
            List<House> order = turnOrder(room);
            if (room.turnIndex + 1 < order.size()) {
                room.turnIndex += 1;
            } else {
                endRound(room);
                if (!room.winners.isEmpty()) return;
                startRound(room);
                room.turnIndex = 0;
            }
            beginTurn(room);
        }
    }

    void beginTurn(Room room) {
        House house = current(room);
        house.moved = false;
        house.attacked = false;
        house.attacksLeft = 1;
        house.movementRemaining = house.movementMax;
        house.recruitedThisTurn = 0;
        house.screamers = false;
        house.redWeddingTargets = new ArrayList<>();
        int income = income(room, house);
        if (income > 0) {
            house.gold += income;
            room.events.add(house.name + "'s turn: collected " + income + " gold.");
        } else if (!house.ai) {
            room.events.add(house.name + "'s turn.");
        }
    }

    void startRound(Room room) {
        room.round += 1;
        boolean wasWinter = room.round - 1 >= room.winterRound;
        StringBuilder start = new StringBuilder("Round " + room.round + " begins. ");
        if (room.round >= room.winterRound - 3) {
            room.snowLine = Math.min(ThronesRules.SNOW_LINE_MAX, room.snowLine
                + (winter(room) ? ThronesRules.SNOW_STEP_WINTER : ThronesRules.SNOW_STEP_AUTUMN));
        }
        start.append("It is ").append(season(room)).append('.');
        room.events.add(start.toString());
        if (winter(room) && !wasWinter) longNight(room);
        if (winter(room)) {
            for (House host : room.houses) {
                if (host.alive && ThronesRules.isDead(host.id)) host.units += 1;
            }
        }
        if (room.round >= ThronesRules.DRAGON_HATCH_ROUND) hatchDragons(room);
        if (room.round >= 2) drawFate(room);
        for (House house : room.houses) {
            if (house.alive && house.visibleUntilRound < room.round) house.hidden = concealedIn(house.terrain);
        }
    }

    private void hatchDragons(Room room) {
        if (room.dragonsHatched) return;
        House targaryen = room.houses.stream().filter(house -> "targaryen".equals(house.id) && house.alive)
            .findFirst().orElse(null);
        if (targaryen == null) return;
        room.dragonsHatched = true;
        targaryen.dragons = new ArrayList<>(ThronesRules.DRAGON_NAMES);
        room.events.add("Fire and blood! Three dragons hatch: Drogon, Rhaegal and Viserion. "
            + targaryen.leader + " can now fly.");
    }

    /** Winter has come: the Long Night falls on every house at once. */
    private void longNight(Room room) {
        if (room.longNight) return;
        room.longNight = true;
        room.events.add("The Long Night falls. Winter has come, and the Dead march on the Wall. "
            + "Houses that fight them side by side are forged in winter.");
    }

    void drawFate(Room room) {
        // Games saved before the deck changed may hold cards that no longer exist; skip them.
        room.fateDeck.removeIf(id -> ThronesRules.fate(id) == null);
        if (room.fateDeck.isEmpty()) room.fateDeck = shuffledDeck();
        String card = room.fateDeck.remove(0);
        room.fate = card;
        room.fateRound = room.round;
        room.fateCount += 1;
        ThronesRules.Fate fate = ThronesRules.fate(card);
        room.events.add("Fate: " + fate.title() + ". " + fate.text());
        applyFate(room, card);
    }

    void applyFate(Room room, String card) {
        House dead = livingHouse(room, ThronesRules.DEAD);
        switch (card) {
            case "winter" -> {
                if (winter(room)) {
                    if (dead != null) dead.units += 2;
                } else {
                    room.winterRound = Math.max(room.round, room.winterRound - 1);
                    if (winter(room)) {
                        room.events.add("Winter has come early.");
                        longNight(room);
                    }
                }
            }
            case "harvest" -> room.houses.stream().filter(house -> house.alive && !house.ai).forEach(house -> house.gold += 2);
            case "ravens" -> room.houses.stream().filter(house -> house.alive).forEach(house -> reveal(room, house));
            case "walkers" -> {
                if (dead != null) dead.units += 2;
            }
            case "wildlings" -> {
                Castle wall = castleNamed(room, ThronesRules.CASTLE_BLACK);
                if (wall != null && !ThronesRules.isDead(wall.owner)) wall.units = Math.max(0, wall.units - 1);
            }
            case "tourney" -> {
                Castle city = castleNamed(room, ThronesRules.KINGS_LANDING);
                House holder = city == null ? null : livingHouse(room, city.owner);
                if (holder != null) holder.units += 1;
            }
            case "desertion" -> room.houses.stream().filter(house -> house.alive && house.units >= 4)
                .forEach(house -> house.units -= 1);
            case "comet" -> {
                if (!room.dragonsHatched) hatchDragons(room);
                else {
                    House targaryen = livingHouse(room, "targaryen");
                    if (targaryen != null) targaryen.units += 1;
                }
            }
            case "kingsmoot" -> {
                House greyjoy = livingHouse(room, "greyjoy");
                if (greyjoy != null) greyjoy.units += 2;
            }
            case "plague" -> {
                Castle city = castleNamed(room, ThronesRules.KINGS_LANDING);
                if (city != null) city.units = Math.max(0, city.units - 1);
            }
            case "gold" -> {
                Castle rock = castleNamed(room, ThronesRules.CASTERLY_ROCK);
                House holder = rock == null ? null : livingHouse(room, rock.owner);
                if (holder != null) holder.gold += 3;
            }
            default -> { /* blight, storm, levies: checked while the round lasts */ }
        }
    }

    boolean fateActive(Room room, String card) {
        return card.equals(room.fate) && room.fateRound == room.round;
    }

    private List<String> shuffledDeck() {
        List<String> deck = ThronesRules.FATE_DECK.stream().map(ThronesRules.Fate::id)
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(deck, random);
        return deck;
    }

    // ------------------------------------------------------------------ victory

    void endRound(Room room) {
        checkVictory(room, true);
    }

    void checkLastStanding(Room room) {
        checkVictory(room, false);
    }

    /**
     * The ways a game ends. A side (a coalition, or a house alone) wins by holding every castle at
     * the end of a round, or at once when every other house and the Dead are destroyed. The Dead win
     * if every living house falls, or if they hold every castle at the end of a round.
     */
    void checkVictory(Room room, boolean endOfRound) {
        if (!room.winners.isEmpty() || !"campaign".equals(room.status)) return;
        List<House> living = room.houses.stream().filter(house -> house.alive && !ThronesRules.isDead(house.id)).toList();
        List<String> deadIds = room.houses.stream().filter(house -> ThronesRules.isDead(house.id)).map(house -> house.id).toList();
        boolean deadAlive = room.houses.stream().anyMatch(house -> house.alive && ThronesRules.isDead(house.id));
        if (living.isEmpty()) {
            declare(room, deadIds, "Every house has fallen. The Long Night will never end: the Dead win.");
            return;
        }
        if (endOfRound && !room.castles.isEmpty() && room.castles.stream().allMatch(castle -> ThronesRules.isDead(castle.owner))) {
            declare(room, deadIds, "The Dead hold every castle in the realm. The Long Night will never end.");
            return;
        }
        for (House house : living) {
            List<House> side = side(room, house);
            List<String> ids = side.stream().map(member -> member.id).toList();
            String names = side.stream().map(member -> member.name).collect(Collectors.joining(" and "));
            boolean together = side.size() > 1;
            if (endOfRound && room.castles.stream().allMatch(castle -> ids.contains(castle.owner))) {
                declare(room, ids, names + (together ? " hold" : " holds") + " every castle in the realm and "
                    + (together ? "win together!" : "wins!"));
                return;
            }
            if (!deadAlive && living.stream().allMatch(other -> ids.contains(other.id))) {
                declare(room, ids, names + (together ? " are" : " is") + " the last standing and "
                    + (together ? "win together!" : "wins!"));
                return;
            }
        }
    }

    long castlesHeld(Room room, List<House> side) {
        return room.castles.stream().filter(castle -> side.stream().anyMatch(member -> member.id.equals(castle.owner))).count();
    }

    private void declare(Room room, List<String> winners, String text) {
        room.winners = new ArrayList<>(winners);
        room.winnerText = text;
        room.status = "over";
        room.events.add(text);
    }

    long greatSeats(Room room, String houseId) {
        return room.castles.stream().filter(castle -> castle.great && castle.owner.equals(houseId)).count();
    }

    // ------------------------------------------------------------------ helpers

    boolean winter(Room room) {
        return room.round >= room.winterRound;
    }

    String season(Room room) {
        if (winter(room)) return "Winter";
        return room.round >= room.winterRound - 3 ? "Autumn" : "Summer";
    }

    List<House> turnOrder(Room room) {
        List<House> order = new ArrayList<>();
        for (String id : room.turnOrderIds) {
            House house = house(room, id);
            if (house.alive) order.add(house);
        }
        return order;
    }

    House current(Room room) {
        return turnOrder(room).get(room.turnIndex);
    }

    void requireTurn(Room room, House house) {
        if (!"campaign".equals(room.status)) throw new RuleException("The game is not in progress.");
        if (!house.alive) throw new RuleException(house.name + " is no more.");
        if (current(room) != house) throw new RuleException("It is not your turn.");
    }

    static House house(Room room, String id) {
        return room.houses.stream().filter(house -> house.id.equals(id)).findFirst()
            .orElseThrow(() -> new RuleException("No such house."));
    }

    static House livingHouse(Room room, String id) {
        return room.houses.stream().filter(house -> house.id.equals(id) && house.alive).findFirst().orElse(null);
    }

    static Castle castleNamed(Room room, String name) {
        return room.castles.stream().filter(castle -> castle.name.equals(name)).findFirst().orElse(null);
    }

    static Castle castleAt(Room room, double x, double y) {
        return room.castles.stream().filter(castle -> same(castle.x, castle.y, x, y)).findFirst().orElse(null);
    }

    private boolean atPlace(double x, double y, String name) {
        ThronesMap.Place place = map.place(name);
        return same(x, y, place.x(), place.y());
    }

    /** Destinations close to a castle or an army land on it, so armies meet there exactly. */
    double[] snap(Room room, double x, double y) {
        double[] best = null;
        double bestDistance = ThronesRules.SNAP_REACH;
        for (Castle castle : room.castles) {
            double distance = ThronesMap.distance(x, y, castle.x, castle.y);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = new double[] {castle.x, castle.y};
            }
        }
        if (best == null) {
            for (ThronesMap.Place place : map.places()) {
                double distance = ThronesMap.distance(x, y, place.x(), place.y());
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = new double[] {place.x(), place.y()};
                }
            }
        }
        if (best == null) {
            for (House house : room.houses) {
                if (!house.alive || house.hidden) continue;
                double distance = ThronesMap.distance(x, y, house.x, house.y);
                if (distance <= bestDistance * 0.6) {
                    bestDistance = distance;
                    best = new double[] {house.x, house.y};
                }
            }
        }
        return best == null ? new double[] {x, y} : best;
    }

    static boolean same(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2) < 0.5;
    }

    static void reveal(Room room, House house) {
        house.hidden = false;
        house.visibleUntilRound = room.round;
    }

    /** Mountains and volcanic ground always hide an army; forest hides it on a coin flip. */
    boolean concealedIn(String terrain) {
        if ("mountain".equals(terrain) || "volcanic".equals(terrain)) return true;
        return "forest".equals(terrain) && random.nextBoolean();
    }

    String terrainAt(Room room, double x, double y, String houseId) {
        return MovementPathCalculator.calculate(x, y, x, y, room.mapWidthMiles, Double.NaN, Double.NaN,
            terrain(room), Map.of()).destinationTerrain();
    }

    private static Map<String, Double> rateOverrides(House mover) {
        return switch (mover.id) {
            case "stark", ThronesRules.DEAD, "deadfrost", "deadhard" -> Map.of("snow", 15.0);
            case "martell" -> Map.of("desert", 15.0);
            default -> Map.of();
        };
    }

    /** The map's terrain plus the snow that has crept south of the Wall. */
    List<TerrainRegion> terrain(Room room) {
        List<TerrainRegion> snow = snowCache.computeIfAbsent(room.snowLine, ThronesGame::snowStrokes);
        if (snow.isEmpty()) return map.terrain();
        List<TerrainRegion> all = new ArrayList<>(map.terrain());
        all.addAll(snow);
        return all;
    }

    private static List<TerrainRegion> snowStrokes(double snowLine) {
        List<TerrainRegion> strokes = new ArrayList<>();
        if (snowLine <= ThronesRules.SNOW_LINE_START) return strokes;
        for (double y = 0.8; y < snowLine; y += 1.2) {
            strokes.add(new TerrainRegion("westeros", "westeros", "Snow line", "snow", 1.6,
                List.of(new TerrainRegion.RegionPoint(0, y), new TerrainRegion.RegionPoint(100, y))));
        }
        return strokes;
    }

    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
