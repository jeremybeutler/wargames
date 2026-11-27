package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wargames.ThronesGame.RuleException;
import org.wargames.ThronesState.Castle;
import org.wargames.ThronesState.House;
import org.wargames.ThronesState.Room;

class ThronesGameTest {
    private static final List<String> DEAD_ARMIES = List.of("dead", "deadfrost", "deadhard");
    private static ThronesMap map;

    @BeforeAll
    static void loadMap() {
        map = new ThronesMap();
    }

    private ThronesGame game;

    /** Stark, Lannister and Targaryen claimed, the server playing the Dead's three armies; Stark acts first. */
    private Room room(String... claimed) {
        game = new ThronesGame(map, new Random(7));
        Room room = game.create("TEST01", "Host", "hash", 2500, 0, true, "now");
        for (String id : claimed.length == 0 ? new String[] {"stark", "lannister", "targaryen"} : claimed) {
            game.join(room, id, "Player " + id, null, "hash-" + id);
        }
        game.start(room);
        // A fixed turn order and no Fate yet, so the tests read plainly.
        room.turnOrderIds.sort((a, b) -> order(a) - order(b));
        room.turnIndex = 0;
        return room;
    }

    private static int order(String id) {
        return List.of("stark", "lannister", "targaryen", "baratheon", "greyjoy", "tyrell", "martell",
            "dead", "deadfrost", "deadhard").indexOf(id);
    }

    private void dice(Integer... faces) {
        Deque<Integer> queue = new ArrayDeque<>(List.of(faces));
        game.d6 = () -> {
            if (queue.isEmpty()) throw new AssertionError("Rolled more dice than the test expected");
            return queue.poll();
        };
    }

    private static House house(Room room, String id) {
        return ThronesGame.house(room, id);
    }

    private static Castle castle(Room room, String name) {
        return ThronesGame.castleNamed(room, name);
    }

    private static void placeAt(House house, String placeName) {
        ThronesMap.Place place = map.place(placeName);
        house.x = place.x();
        house.y = place.y();
        house.terrain = "plains";
    }

    private void turnOf(Room room, String id) {
        room.turnIndex = game.turnOrder(room).indexOf(house(room, id));
        game.beginTurn(room);
    }

    private void joinForces(Room room, String from, String to) {
        game.invite(room, house(room, from), to, false);
        game.respond(room, house(room, to), from, true);
    }

    // ------------------------------------------------------------------ setup

    @Test
    void startingNeedsTwoHousesAndTheDeadAreAlwaysThreeArmies() {
        game = new ThronesGame(map, new Random(1));
        Room lobby = game.create("TEST02", "Host", "hash", 2500, 0, true, "now");
        game.join(lobby, "stark", "A", null, "a");
        assertThrows(RuleException.class, () -> game.start(lobby));
        assertThrows(RuleException.class, () -> game.join(lobby, "dead", "B", null, "b"));

        Room room = room();
        assertEquals("campaign", room.status);
        for (String id : DEAD_ARMIES) {
            assertTrue(house(room, id).ai, id);
            assertTrue(house(room, id).alive, id);
            assertEquals("dead", house(room, id).bot, id);
        }
        assertFalse(house(room, "tyrell").alive);
        assertEquals("neutral", castle(room, "Highgarden").owner);
        assertEquals(2, castle(room, "Highgarden").units);      // a great seat
        assertEquals(1, castle(room, "The Twins").units);       // a minor castle
        assertEquals(2, castle(room, "King's Landing").units);
        assertEquals(3, castle(room, "Castle Black").units);
        assertEquals("stark", castle(room, "Winterfell").owner);
        assertEquals("targaryen", castle(room, "Pentos").owner);
    }

    @Test
    void aPlayerMayChooseWhichHeirLeadsFirst() {
        game = new ThronesGame(map, new Random(1));
        Room lobby = game.create("TEST03", "Host", "hash", 2500, 0, true, "now");
        game.join(lobby, "stark", "A", "Arya Stark", "a");
        House stark = house(lobby, "stark");
        assertEquals("Arya Stark", stark.leader);
        assertEquals("Ned Stark", stark.heirs.get(1));
        assertThrows(RuleException.class, () -> game.join(lobby, "lannister", "B", "Jon Snow", "b"));
    }

    // ------------------------------------------------------------------ gold

    @Test
    void incomeCountsCastlesWithBonusesForSeatsAndTheCapital() {
        Room room = room();
        House stark = house(room, "stark");
        assertEquals(2, game.income(room, stark));                 // Winterfell, its own seat
        castle(room, "King's Landing").owner = "stark";
        castle(room, "The Twins").owner = "stark";
        assertEquals(2 + 3 + 1, game.income(room, stark));
        assertEquals(2 + 2, game.income(room, house(room, "lannister")));  // Casterly Rock + Hear Me Roar
        assertEquals(0, game.income(room, house(room, "dead")));
        stark.oathbreakerUntil = room.round + 1;
        assertEquals(0, game.income(room, stark));
    }

    @Test
    void leviesCostGoldAtAHeldCastleAndAreCappedEachTurn() {
        Room room = room();
        House stark = house(room, "stark");
        stark.gold = 10;
        game.recruit(room, stark, 2);
        assertEquals(5, stark.units);
        assertEquals(6, stark.gold);
        assertThrows(RuleException.class, () -> game.recruit(room, stark, 1));
        placeAt(stark, "The Twins");
        turnOf(room, "stark");
        assertThrows(RuleException.class, () -> game.recruit(room, stark, 1));
    }

    @Test
    void tyrellLeviesCostOneGold() {
        Room room = room("stark", "tyrell", "lannister");
        House tyrell = house(room, "tyrell");
        assertEquals(1, game.recruitCost(room, tyrell));
        assertEquals(2, game.recruitCost(room, house(room, "stark")));
    }

    // ------------------------------------------------------------------ battle and succession

    @Test
    void anArmyWithNoUnitsLeftFallsAndTheNextHeirRisesAtTheSeat() {
        Room room = room();
        House stark = house(room, "stark");
        House lannister = house(room, "lannister");
        placeAt(stark, "The Twins");
        placeAt(lannister, "The Twins");
        stark.units = 0;
        dice(6, 6, 6, 1);   // Lannister attacks with 3 dice and wins
        turnOf(room, "lannister");
        game.attack(room, lannister, "stark");
        assertTrue(stark.alive);
        assertEquals("Robb Stark", stark.leader);
        assertEquals(1, stark.units);
        assertEquals(map.place("Winterfell").x(), stark.x, 1e-9);
    }

    @Test
    void theLastHeirFallingEndsTheHouse() {
        Room room = room();
        House targaryen = house(room, "targaryen");
        targaryen.heirIndex = 1;
        targaryen.leader = "Viserys Targaryen";
        targaryen.units = 0;
        game.loseUnits(room, targaryen, 1, house(room, "stark"));
        assertFalse(targaryen.alive);
        assertEquals("neutral", castle(room, "Pentos").owner);
    }

    @Test
    void capturingACastleNeedsItsGarrisonBeatenFirst() {
        Room room = room();
        House stark = house(room, "stark");
        Castle twins = castle(room, "The Twins");
        placeAt(stark, "The Twins");
        dice(6, 6, 6, 1);
        game.attack(room, stark, twins.id);
        assertEquals(0, twins.units);
        assertEquals("neutral", twins.owner);
        turnOf(room, "stark");
        dice(6, 6, 6, 1);
        game.attack(room, stark, twins.id);
        assertEquals("stark", twins.owner);
    }

    @Test
    void attackersWhoLoseAtTheEyrieFallThroughTheMoonDoor() {
        Room room = room();
        House stark = house(room, "stark");
        placeAt(stark, "The Eyrie");
        dice(1, 1, 1, 6, 6);
        game.attack(room, stark, castle(room, "The Eyrie").id);
        assertEquals(1, stark.units);
    }

    // ------------------------------------------------------------------ coalitions

    @Test
    void invitationsFormCoalitionsThatMerge() {
        Room room = room("stark", "lannister", "targaryen", "baratheon");
        joinForces(room, "stark", "lannister");
        joinForces(room, "targaryen", "baratheon");
        assertEquals(2, room.coalitions.size());
        assertTrue(game.allied(room, "stark", "lannister"));
        assertFalse(game.allied(room, "stark", "targaryen"));

        joinForces(room, "lannister", "baratheon");   // two coalitions become one
        assertEquals(1, room.coalitions.size());
        assertEquals(Set.of("stark", "lannister", "targaryen", "baratheon"), Set.copyOf(room.coalitions.get(0).members));
        assertTrue(room.invites.isEmpty());
        assertThrows(RuleException.class, () -> game.invite(room, house(room, "stark"), "dead", false));
    }

    @Test
    void aRefusedInvitationChangesNothing() {
        Room room = room();
        game.invite(room, house(room, "stark"), "lannister", false);
        assertThrows(RuleException.class, () -> game.invite(room, house(room, "stark"), "lannister", false));
        game.respond(room, house(room, "lannister"), "stark", false);
        assertTrue(room.coalitions.isEmpty());
        assertTrue(room.invites.isEmpty());
    }

    @Test
    void alliesCannotAttackEachOtherAndLeavingBringsTheRedWedding() {
        Room room = room();
        House stark = house(room, "stark");
        joinForces(room, "stark", "lannister");
        placeAt(stark, "The Twins");
        placeAt(house(room, "lannister"), "The Twins");
        assertThrows(RuleException.class, () -> game.attack(room, stark, "lannister"));

        game.leave(room, stark);
        assertTrue(room.coalitions.isEmpty());
        assertTrue(stark.oathbreakerUntil > room.round);
        dice(1, 1, 1, 3, 3, 3);   // 3 dice +2 each = 9 against 9: a tie goes to the defender
        game.attack(room, stark, "lannister");
        assertEquals(2, room.lastBattle.attackerModifier);
        assertEquals(9, room.lastBattle.attackerTotal);
        assertEquals(9, room.lastBattle.defenderTotal);
        assertFalse(room.lastBattle.attackerWon);
        assertThrows(RuleException.class, () -> game.invite(room, stark, "targaryen", false));
        assertThrows(RuleException.class, () -> game.invite(room, house(room, "targaryen"), "stark", false));
    }

    @Test
    void aRoyalWeddingCannotBeLeftForFiveRounds() {
        Room room = room("stark", "tyrell", "lannister");
        House tyrell = house(room, "tyrell");
        House stark = house(room, "stark");
        int units = stark.units;
        game.invite(room, tyrell, "stark", true);
        game.respond(room, stark, "tyrell", true);
        assertTrue(game.allied(room, "tyrell", "stark"));
        assertEquals(units + 2, stark.units);
        assertTrue(tyrell.powerUsed);
        turnOf(room, "stark");
        assertThrows(RuleException.class, () -> game.leave(room, stark));
        room.round += 6;
        game.leave(room, stark);
        assertFalse(game.allied(room, "tyrell", "stark"));
    }

    @Test
    void alliedArmiesOnTheSameSpotDefendTogether() {
        Room room = room();
        House lannister = house(room, "lannister");
        joinForces(room, "stark", "targaryen");
        placeAt(house(room, "stark"), "The Twins");
        placeAt(house(room, "targaryen"), "The Twins");
        placeAt(lannister, "The Twins");
        turnOf(room, "lannister");
        dice(6, 6, 6, 1, 1, 1, 1, 1);   // Lannister 3 dice; Stark 3 + Targaryen 2
        game.attack(room, lannister, "stark");
        assertEquals(5, room.lastBattle.defenderDice.size());
    }

    @Test
    void fightingTheDeadTogetherInTheLongNightForgesASwornBond() {
        Room room = room();
        House stark = house(room, "stark");
        House lannister = house(room, "lannister");
        House frost = house(room, "deadfrost");
        joinForces(room, "stark", "lannister");
        room.round = room.winterRound;
        frost.units = 20;

        placeAt(frost, "Moat Cailin");
        placeAt(stark, "Moat Cailin");
        dice(1, 1, 1, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6);
        game.attack(room, stark, "deadfrost");
        assertTrue(stark.forged);
        assertFalse(game.sworn(room, stark));          // a bond needs two

        turnOf(room, "lannister");
        placeAt(lannister, "Moat Cailin");
        frost.units = 20;   // winning raised another wight; reset so the dice count stays fixed
        dice(1, 1, 1, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6);
        game.attack(room, lannister, "deadfrost");
        assertTrue(game.sworn(room, stark));
        assertTrue(game.sworn(room, lannister));
        assertTrue(room.events.stream().anyMatch(event -> event.startsWith("Forged in winter")));

        // The bond lasts after the Long Night, but leaving the coalition breaks it.
        turnOf(room, "lannister");
        game.leave(room, lannister);
        assertFalse(lannister.forged);
        assertFalse(game.sworn(room, stark));
    }

    @Test
    void aSwornBondAddsOneToEveryDie() {
        Room room = room();
        House stark = house(room, "stark");
        House lannister = house(room, "lannister");
        joinForces(room, "stark", "lannister");
        stark.forged = true;
        lannister.forged = true;
        placeAt(stark, "The Twins");
        dice(3, 3, 3, 1);
        game.attack(room, stark, castle(room, "The Twins").id);
        assertEquals(1, room.lastBattle.attackerModifier);
        assertEquals(12, room.lastBattle.attackerTotal);
    }

    @Test
    void botsAnswerInvitationsAtOnce() {
        game = new ThronesGame(map, new Random(4));
        Room room = game.create("BOTS02", "Host", "hash", 2500, 0, true, "now");
        House bot = house(room, "stark");
        bot.claimed = true;
        bot.bot = "hard";
        for (String id : List.of("lannister", "baratheon")) game.join(room, id, "Player " + id, null, "hash-" + id);
        game.start(room);
        room.round = room.winterRound;   // in the Long Night a bot always joins forces
        game.invite(room, house(room, "baratheon"), "stark", false);
        assertTrue(room.invites.isEmpty());
        assertTrue(game.allied(room, "stark", "baratheon"));
    }

    // ------------------------------------------------------------------ dragons, the Dead and the Wall

    @Test
    void dragonsHatchInRoundFourFlyAndDieBeforeTheirQueen() {
        Room room = room();
        House targaryen = house(room, "targaryen");
        room.round = 3;
        game.startRound(room);
        assertEquals(3, targaryen.dragons.size());
        assertEquals(2 + 6, game.dice(room, targaryen));

        turnOf(room, "targaryen");
        ThronesMap.Place kingsLanding = map.place("King's Landing");
        ThronesGame.MovePlan flight = game.plan(room, targaryen, kingsLanding.x(), kingsLanding.y());
        assertEquals("fly", flight.mode());

        targaryen.units = 0;
        game.loseUnits(room, targaryen, 1, house(room, "deadhard"));
        assertTrue(targaryen.alive);
        assertEquals(2, targaryen.dragons.size());
        assertTrue(room.iceDragon);
        assertTrue(room.wallBreached);
    }

    @Test
    void landArmiesSailOnlyFromPortToPortUsingTheWholeTurn() {
        Room room = room();
        House lannister = house(room, "lannister");
        placeAt(lannister, "King's Landing");
        turnOf(room, "lannister");
        ThronesMap.Place pentos = map.place("Pentos");
        ThronesGame.MovePlan voyage = game.plan(room, lannister, pentos.x(), pentos.y());
        assertEquals("sail", voyage.mode());
        assertEquals(lannister.movementMax, voyage.movementPoints(), 1e-9);

        placeAt(lannister, "Harrenhal");
        assertThrows(RuleException.class, () -> game.plan(room, lannister, pentos.x(), pentos.y()));
    }

    @Test
    void theWallCanOnlyBeCrossedThroughAHeldCastle() {
        Room room = room();
        House stark = house(room, "stark");
        ThronesMap.Place crasters = map.place("Craster's Keep");
        assertThrows(RuleException.class, () -> game.plan(room, stark, crasters.x(), crasters.y()));

        Castle castleBlack = castle(room, "Castle Black");
        stark.x = castleBlack.x;
        stark.y = castleBlack.y;
        stark.wallFrom = 1;
        assertThrows(RuleException.class, () -> game.plan(room, stark, crasters.x(), crasters.y()));
        castleBlack.owner = "stark";
        assertEquals("land", game.plan(room, stark, crasters.x(), crasters.y()).mode());
    }

    @Test
    void theDeadCannotAttackBeforeWinter() {
        Room room = room();
        House dead = house(room, "dead");
        Castle castleBlack = castle(room, "Castle Black");
        dead.x = castleBlack.x;
        dead.y = castleBlack.y;
        turnOf(room, "dead");
        assertThrows(RuleException.class, () -> game.attack(room, dead, castleBlack.id));
    }

    @Test
    void slayingTheNightKingDestroysEveryArmyOfTheDead() {
        Room room = room();
        House dead = house(room, "dead");
        House lannister = house(room, "lannister");
        dead.units = 0;
        game.loseUnits(room, dead, 1, lannister);
        assertTrue(dead.alive);                        // no Valyrian steel: he retreats
        assertEquals(3, dead.units);

        castle(room, "Castle Black").owner = "deadfrost";
        dead.units = 0;
        lannister.leader = "Jaime Lannister";
        int starkUnits = house(room, "stark").units;
        int gold = lannister.gold;
        game.loseUnits(room, dead, 1, lannister);
        for (String id : DEAD_ARMIES) assertFalse(house(room, id).alive, id);
        assertEquals("neutral", castle(room, "Castle Black").owner);
        assertEquals(gold + 6, lannister.gold);
        assertEquals(starkUnits + 2, house(room, "stark").units);
        assertTrue(room.winners.isEmpty());            // slaying him is not a win by itself
    }

    @Test
    void aWhiteWalkerHostFallsLikeAnyArmy() {
        Room room = room();
        House frost = house(room, "deadfrost");
        frost.units = 0;
        game.loseUnits(room, frost, 1, house(room, "stark"));
        assertFalse(frost.alive);
        assertTrue(house(room, "dead").alive);
    }

    @Test
    void theLongNightFeedsEveryArmyOfTheDeadAndAdvancesTheSnowLine() {
        Room room = room();
        double start = room.snowLine;
        List<Integer> before = DEAD_ARMIES.stream().map(id -> house(room, id).units).toList();
        room.round = room.winterRound - 1;
        game.startRound(room);
        assertTrue(game.winter(room));
        assertTrue(room.longNight);
        assertTrue(room.snowLine > start);
        for (int index = 0; index < DEAD_ARMIES.size(); index++) {
            assertTrue(house(room, DEAD_ARMIES.get(index)).units >= before.get(index) + 1, DEAD_ARMIES.get(index));
        }
        assertTrue(room.events.stream().anyMatch(event -> event.startsWith("The Long Night falls")));
    }

    @Test
    void starksMarchThroughSnowAsIfItWerePlains() {
        Room room = room();
        room.snowLine = 40;
        House stark = house(room, "stark");
        House lannister = house(room, "lannister");
        ThronesMap.Place target = map.place("White Harbor");
        double starkCost = game.plan(room, stark, target.x(), target.y()).movementPoints();
        lannister.x = stark.x;
        lannister.y = stark.y;
        double lannisterCost = game.plan(room, lannister, target.x(), target.y()).movementPoints();
        assertTrue(starkCost < lannisterCost, starkCost + " vs " + lannisterCost);
    }

    @Test
    void theDeadAiMarchesOnTheWallInWinter() {
        Room room = room();
        House dead = house(room, "dead");
        room.round = room.winterRound;
        turnOf(room, "dead");
        double before = ThronesMap.distance(dead.x, dead.y, castle(room, "Castle Black").x, castle(room, "Castle Black").y);
        new ThronesDeadAi(game, map).play(room, dead);
        double nearest = room.castles.stream().filter(c -> ThronesRules.WALL_CASTLES.contains(c.name))
            .mapToDouble(c -> ThronesMap.distance(dead.x, dead.y, c.x, c.y)).min().orElseThrow();
        assertTrue(nearest < before, "The Dead should close on the Wall: " + nearest + " vs " + before);
        assertTrue(dead.powerUsed);
    }

    @Test
    void theDeadsThreeArmiesSpreadToDifferentTargets() {
        Room room = room();
        room.round = room.winterRound;
        room.wallBreached = true;
        placeAt(house(room, "dead"), "Winterfell");
        placeAt(house(room, "deadfrost"), "Riverrun");
        placeAt(house(room, "deadhard"), "Harrenhal");
        ThronesDeadAi ai = new ThronesDeadAi(game, map);
        Set<String> targets = DEAD_ARMIES.stream().map(id -> {
            double[] target = ai.target(room, house(room, id));
            assertNotNull(target, id);
            return Math.round(target[0] * 10) + "," + Math.round(target[1] * 10);
        }).collect(Collectors.toSet());
        assertEquals(3, targets.size(), "Each army should pick its own target");
    }

    // ------------------------------------------------------------------ victory

    @Test
    void aCoalitionHoldingEveryCastleWinsTogether() {
        Room room = room();
        joinForces(room, "lannister", "targaryen");
        for (Castle castle : room.castles) castle.owner = "lannister";
        castle(room, "Winterfell").owner = "stark";
        game.endRound(room);
        assertTrue(room.winners.isEmpty(), "One castle short of the whole map");

        castle(room, "Winterfell").owner = "targaryen";
        game.endRound(room);
        assertEquals(Set.of("lannister", "targaryen"), Set.copyOf(room.winners));
        assertEquals("over", room.status);
    }

    @Test
    void theLastSideStandingWinsOnceTheDeadAreGone() {
        Room room = room();
        joinForces(room, "stark", "lannister");
        game.eliminate(room, house(room, "targaryen"), null);
        game.checkLastStanding(room);
        assertTrue(room.winners.isEmpty(), "The Dead still march");

        for (String id : DEAD_ARMIES) game.eliminate(room, house(room, id), null);
        game.checkLastStanding(room);
        assertEquals(Set.of("stark", "lannister"), Set.copyOf(room.winners));
    }

    @Test
    void theDeadWinWhenEveryHouseFallsOrTheyHoldEveryCastle() {
        Room room = room();
        for (Castle castle : room.castles) castle.owner = "deadhard";
        game.endRound(room);
        assertEquals(Set.copyOf(DEAD_ARMIES), Set.copyOf(room.winners));
        assertNotNull(room.winnerText);

        Room other = room();
        for (String id : List.of("stark", "lannister", "targaryen")) game.eliminate(other, house(other, id), null);
        game.checkLastStanding(other);
        assertEquals(Set.copyOf(DEAD_ARMIES), Set.copyOf(other.winners));
    }

    @Test
    void oldFateCardsInSavedGamesAreSkipped() {
        Room room = room();
        room.fateDeck = new java.util.ArrayList<>(List.of("ironbank", "harvest"));
        room.round = 2;
        game.drawFate(room);
        assertEquals("harvest", room.fate);
        assertNull(ThronesRules.fate("ironbank"));
        assertNotEquals(0, room.fateCount);
    }

    @Test
    void aWholeGameAgainstTheDeadRunsWithoutBreakingRules() {
        Room room = room();
        for (int turn = 0; turn < 600 && "campaign".equals(room.status); turn++) {
            House active = game.current(room);
            game.endTurn(room, active);
        }
        // In Winter more than one army of the Dead marched and fought.
        long attackers = room.events.stream().filter(event -> event.contains(" attacked "))
            .map(event -> event.substring(0, event.indexOf(" attacked ")))
            .filter(name -> name.contains("Night King") || name.contains("White Walker")).distinct().count();
        assertTrue(attackers >= 2, String.join("\n", room.events));
    }

    /** Every house played by a bot (the server playing the Dead), as the host's "add bots" sets them up. */
    private Room botRoom(long seed, String... levels) {
        game = new ThronesGame(map, new Random(seed));
        Room room = game.create("BOTS01", "Host", "hash", 2500, 0, true, "now");
        String[] houses = {"stark", "lannister", "targaryen", "baratheon", "greyjoy", "tyrell", "martell"};
        for (int index = 0; index < levels.length; index++) {
            House house = house(room, houses[index]);
            house.claimed = true;
            house.bot = levels[index];
            house.playerName = "Bot";
        }
        game.start(room);
        return room;
    }

    @Test
    void aGameOfBotsPlaysOnWithoutStalling() {
        for (long seed = 1; seed <= 3; seed++) {
            Room room = botRoom(seed, "hard", "easy", "hard", "easy", "hard");
            Random random = new Random(seed);
            for (int turns = 0; turns < 400 && "campaign".equals(room.status); turns++) {
                House active = game.current(room);
                assertNotNull(active.bot, "Only bots (and the server's Dead) take turns here");
                int roundBefore = room.round;
                int indexBefore = room.turnIndex;
                ThronesBot.playTurn(game, map, room, active, random);
                // A bot's turn always hands the turn on (or ends the game).
                assertTrue(!"campaign".equals(room.status) || room.round != roundBefore || room.turnIndex != indexBefore
                    || game.current(room) != active, "A bot's turn did not end");
            }
            assertTrue(room.events.stream().anyMatch(event -> event.contains("captured")),
                "Bots should take castles: " + String.join("\n", room.events));
        }
    }

    @Test
    void theServerPlaysADeadArmysTurnThroughTheBotEntryPoint() {
        Room room = room();
        game.inlineAi = false;
        room.round = room.winterRound;
        turnOf(room, "deadfrost");
        House frost = house(room, "deadfrost");
        ThronesBot.playTurn(game, map, room, frost, new Random(3));
        assertNotEquals(frost, game.current(room));
    }
}
