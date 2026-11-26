package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wargames.ThronesGame.RuleException;
import org.wargames.ThronesState.Castle;
import org.wargames.ThronesState.House;
import org.wargames.ThronesState.Room;

class ThronesGameTest {
    private static ThronesMap map;

    @BeforeAll
    static void loadMap() {
        map = new ThronesMap();
    }

    private ThronesGame game;

    /** Stark, Lannister and Targaryen claimed, the server playing the Dead; Stark acts first. */
    private Room room(String... claimed) {
        game = new ThronesGame(map, new Random(7));
        Room room = game.create("TEST01", "Host", "hash", 2500, 20, true, "now");
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
        return List.of("stark", "lannister", "targaryen", "baratheon", "greyjoy", "tyrell", "martell", "dead").indexOf(id);
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

    // ------------------------------------------------------------------ setup

    @Test
    void startingNeedsThreeHousesAndUnclaimedSeatsTurnNeutral() {
        game = new ThronesGame(map, new Random(1));
        Room lobby = game.create("TEST02", "Host", "hash", 2500, 20, true, "now");
        game.join(lobby, "stark", "A", null, "a");
        game.join(lobby, "lannister", "B", null, "b");
        assertThrows(RuleException.class, () -> game.start(lobby));

        Room room = room();
        assertEquals("campaign", room.status);
        assertTrue(house(room, "dead").ai);
        assertTrue(house(room, "dead").alive);
        assertFalse(house(room, "tyrell").alive);
        assertEquals("neutral", castle(room, "Highgarden").owner);
        assertEquals(3, castle(room, "Highgarden").units);
        assertEquals("stark", castle(room, "Winterfell").owner);
        assertEquals("targaryen", castle(room, "Pentos").owner);
        assertEquals(3, castle(room, "King's Landing").units);
        assertEquals(4, castle(room, "Castle Black").units);
    }

    @Test
    void aPlayerMayChooseWhichHeirLeadsFirst() {
        game = new ThronesGame(map, new Random(1));
        Room lobby = game.create("TEST03", "Host", "hash", 2500, 20, true, "now");
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

    @Test
    void anUnpaidLoanSacksTheSeat() {
        Room room = room();
        House targaryen = house(room, "targaryen");
        placeAt(targaryen, "Braavos");
        turnOf(room, "targaryen");
        game.takeLoan(room, targaryen);
        assertEquals(room.round + 4, targaryen.loanDue);
        Castle pentos = castle(room, "Pentos");
        pentos.units = 4;
        int units = targaryen.units;
        room.round = targaryen.loanDue;
        game.startRound(room);
        assertEquals(0, targaryen.loanDue);
        assertEquals(1, pentos.units);
        assertEquals(units - 1, targaryen.units);
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
        twins.units = 1;
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
        dice(1, 1, 1, 6, 6, 6);
        game.attack(room, stark, castle(room, "The Eyrie").id);
        assertEquals(1, stark.units);
    }

    @Test
    void alliesCannotAttackEachOtherAndBetrayalBringsTheRedWedding() {
        Room room = room();
        House stark = house(room, "stark");
        House lannister = house(room, "lannister");
        game.propose(room, stark, "lannister", false);
        game.respond(room, lannister, "stark", true);
        assertTrue(game.allied(room, "stark", "lannister"));
        placeAt(stark, "The Twins");
        placeAt(lannister, "The Twins");
        assertThrows(RuleException.class, () -> game.attack(room, stark, "lannister"));

        game.breakPact(room, stark, "lannister");
        assertTrue(stark.oathbreakerUntil > room.round);
        dice(1, 1, 1, 3, 3, 3);   // 3 dice +2 each = 9 against 9: a tie goes to the defender
        game.attack(room, stark, "lannister");
        assertEquals(2, room.lastBattle.attackerModifier);
        assertEquals(9, room.lastBattle.attackerTotal);
        assertEquals(9, room.lastBattle.defenderTotal);
        assertFalse(room.lastBattle.attackerWon);
        assertThrows(RuleException.class, () -> game.propose(room, stark, "targaryen", false));
    }

    @Test
    void alliedArmiesOnTheSameSpotDefendTogether() {
        Room room = room();
        House stark = house(room, "stark");
        House lannister = house(room, "lannister");
        House targaryen = house(room, "targaryen");
        game.propose(room, stark, "targaryen", false);
        game.respond(room, targaryen, "stark", true);
        placeAt(stark, "The Twins");
        placeAt(targaryen, "The Twins");
        placeAt(lannister, "The Twins");
        turnOf(room, "lannister");
        dice(6, 6, 6, 1, 1, 1, 1, 1);   // Lannister 3 dice; Stark 3 + Targaryen 2
        game.attack(room, lannister, "stark");
        assertEquals(5, room.lastBattle.defenderDice.size());
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
        game.loseUnits(room, targaryen, 1, house(room, "dead"));
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
    void theNightKingRetreatsUnlessSlainWithValyrianSteelDragonglassOrDragons() {
        Room room = room();
        House dead = house(room, "dead");
        House lannister = house(room, "lannister");
        dead.units = 0;
        game.loseUnits(room, dead, 1, lannister);
        assertTrue(dead.alive);
        assertEquals(3, dead.units);

        dead.units = 0;
        lannister.leader = "Jaime Lannister";
        int starkUnits = house(room, "stark").units;
        int gold = lannister.gold;
        game.loseUnits(room, dead, 1, lannister);
        assertFalse(dead.alive);
        assertEquals(gold + 6, lannister.gold);   // too few great seats to win outright
        assertEquals(starkUnits + 2, house(room, "stark").units);
        assertTrue(room.winners.isEmpty());
    }

    @Test
    void winterAdvancesTheSnowLineAndFeedsTheDead() {
        Room room = room();
        House dead = house(room, "dead");
        double start = room.snowLine;
        int units = dead.units;
        room.round = room.winterRound - 1;
        game.startRound(room);
        assertTrue(game.winter(room));
        assertTrue(room.snowLine > start);
        assertTrue(dead.units >= units + 1);
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

    // ------------------------------------------------------------------ victory

    @Test
    void holdingKingsLandingForThreeRoundsWinsWithAlliesAsHands() {
        Room room = room();
        game.propose(room, house(room, "lannister"), "targaryen", false);
        game.respond(room, house(room, "targaryen"), "lannister", true);
        castle(room, "King's Landing").owner = "lannister";
        game.endRound(room);
        room.round++;
        game.endRound(room);
        room.round++;
        assertTrue(room.winners.isEmpty());
        game.endRound(room);
        assertEquals(List.of("lannister", "targaryen"), room.winners);
        assertEquals("over", room.status);
    }

    @Test
    void sevenGreatSeatsWin() {
        Room room = room();
        int count = 0;
        for (Castle castle : room.castles) {
            if (castle.great && count < 7 && !"King's Landing".equals(castle.name)) {
                castle.owner = "stark";
                count++;
            }
        }
        game.endRound(room);
        assertEquals(List.of("stark"), room.winners);
    }

    @Test
    void theDeadWinByTakingKingsLanding() {
        Room room = room();
        castle(room, "King's Landing").owner = "dead";
        game.endRound(room);
        assertEquals(List.of("dead"), room.winners);
        assertNotNull(room.winnerText);
    }

    @Test
    void theLastHouseStandingWins() {
        Room room = room();
        game.eliminate(room, house(room, "lannister"), null);
        game.eliminate(room, house(room, "targaryen"), null);
        game.checkLastStanding(room);
        assertEquals(List.of("stark"), room.winners);
    }

    @Test
    void aWholeGameAgainstTheDeadRunsToTheEndWithoutBreakingRules() {
        Room room = room();
        for (int turn = 0; turn < 400 && "campaign".equals(room.status); turn++) {
            House active = game.current(room);
            game.endTurn(room, active);
        }
        assertEquals("over", room.status);
        assertFalse(room.winners.isEmpty());
        // In Winter the Dead marched on the Wall and fought for it.
        assertTrue(room.events.stream().anyMatch(event -> event.startsWith("Night King attacked")),
            String.join("\n", room.events));
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
}
