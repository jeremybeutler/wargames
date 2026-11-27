package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.wargames.GameSessionController.Party;
import org.wargames.GameSessionController.Room;
import org.wargames.model.TerrainRegion;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GameSessionControllerTest {
    private static final double DOOM_X = 81.5;
    private static final double DOOM_Y = 67.5;

    @Test
    void destroysTheRingWhenNoOpponentIsAtMountDoom() {
        Room room = room();
        Party frodo = party(room, "frodo");
        moveTo(frodo, DOOM_X, DOOM_Y);

        GameSessionController.resolveRingDestruction(room, frodo, failIfRolled());

        assertEquals("destroyed", room.ringHolder);
        assertEquals("Free Peoples", room.winner);
    }

    @Test
    void anInterceptorWhoDefeatsFrodoCapturesTheRingForSauron() {
        Room room = room();
        Party frodo = party(room, "frodo");
        Party sauron = party(room, "sauron");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(sauron, DOOM_X, DOOM_Y);
        sauron.hidden = true;
        frodo.units = 0;   // Frodo alone: one die

        // Sauron's 3 dice (18) vs. Frodo's 6 (5 at Mount Doom): Frodo loses and falls.
        GameSessionController.resolveRingDestruction(room, frodo, dice(6, 6, 6, 6));

        assertEquals("sauron", room.ringHolder);
        assertEquals("Sauron's Forces", room.winner);
        assertFalse(frodo.alive);
        assertEquals(3, sauron.units);
        assertFalse(sauron.hidden);
        assertTrue(room.events.contains("Sauron's Forces seized the Ring."));
        assertTrue(room.events.stream().anyMatch(event -> event.contains("6-6-6 (18) vs. 5")));
    }

    @Test
    void theRingIsDestroyedWhenFrodoSurvivesEveryInterceptor() {
        Room room = room();
        Party frodo = party(room, "frodo");
        Party khamul = party(room, "khamul");
        Party saruman = party(room, "saruman");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(khamul, DOOM_X, DOOM_Y);
        moveTo(saruman, DOOM_X + 2.0, DOOM_Y);   // on the slopes, still within Mount Doom's reach
        khamul.hidden = true;
        saruman.hidden = true;
        AtomicInteger rolls = new AtomicInteger();

        // Khamûl 1 die vs. Frodo's 6 (5 at Mount Doom): Frodo wins. Saruman 1-1 (2) vs. 5: Frodo wins.
        GameSessionController.resolveRingDestruction(room, frodo, dice(rolls, 1, 6, 1, 1, 6));

        assertEquals(5, rolls.get());
        assertEquals("destroyed", room.ringHolder);
        assertEquals("Free Peoples", room.winner);
        assertEquals(1, frodo.units);
        assertEquals(0, khamul.units);
        assertEquals(1, saruman.units);
        assertFalse(saruman.hidden);
    }

    @Test
    void frodoLosingOnlyHisArmyKeepsTheRing() {
        Room room = room();
        Party frodo = party(room, "frodo");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(party(room, "khamul"), DOOM_X, DOOM_Y);

        // Khamûl 6 vs. Frodo 4 (3 at Mount Doom): Frodo loses his Hobbit unit but stands.
        GameSessionController.resolveRingDestruction(room, frodo, dice(6, 4));

        assertEquals(0, frodo.units);
        assertTrue(frodo.alive);
        assertEquals("destroyed", room.ringHolder);
    }

    @Test
    void interceptorsFightInTurnOrderAndFrodoFallingEndsTheAttempt() {
        Room room = room();
        room.turnOrderIds = new ArrayList<>(List.of("saruman", "frodo", "khamul", "theoden", "aragorn", "sauron"));
        Party frodo = party(room, "frodo");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(party(room, "khamul"), DOOM_X, DOOM_Y);
        moveTo(party(room, "saruman"), DOOM_X, DOOM_Y);
        frodo.units = 0;
        AtomicInteger rolls = new AtomicInteger();

        // Saruman (2 dice, 10) goes first; Frodo's 4 (3 at Mount Doom) loses and he falls.
        GameSessionController.resolveRingDestruction(room, frodo, dice(rolls, 5, 5, 4));

        assertEquals(3, rolls.get());
        assertEquals("sauron", room.ringHolder);
        assertTrue(room.events.stream().anyMatch(event -> event.startsWith("Saruman attacked Frodo")));
    }

    @Test
    void frodoAnywhereOnMountDoomsSlopesCanDestroyTheRing() {
        Room room = room();
        Party frodo = party(room, "frodo");
        moveTo(frodo, DOOM_X + 1.0, DOOM_Y + 0.8);
        assertTrue(GameSessionController.atMountDoom(room, frodo));
        moveTo(frodo, DOOM_X + 3.0, DOOM_Y);
        assertFalse(GameSessionController.atMountDoom(room, frodo));

        double[] snapped = GameSessionController.snapToMountDoom(room, DOOM_X - 2.0, DOOM_Y + 0.8);
        assertEquals(DOOM_X, snapped[0]);
        assertEquals(DOOM_Y, snapped[1]);
        double[] elsewhere = GameSessionController.snapToMountDoom(room, 40, 40);
        assertEquals(40, elsewhere[0]);
    }

    @Test
    void theHigherTotalWinsAndOnlyTheLoserLosesOneUnit() {
        Room room = room();
        Party aragorn = party(room, "aragorn");   // 3 units: 3 attack dice
        Party saruman = party(room, "saruman");   // 2 units: 2 defense dice

        // 6-2-1 (9) vs. 5-2 (7): Aragorn wins, Saruman loses one unit.
        Battle.Result result = GameSessionController.fight(room, aragorn, saruman, dice(6, 2, 1, 5, 2));

        assertEquals(List.of(6, 2, 1), result.attackerDice());
        assertEquals(List.of(5, 2), result.defenderDice());
        assertEquals(3, aragorn.units);
        assertEquals(1, saruman.units);
        assertEquals(9, room.lastBattle.attackerTotal);
        assertEquals(7, room.lastBattle.defenderTotal);
    }

    @Test
    void tiedTotalsGoToTheDefender() {
        Room room = room();
        Party aragorn = party(room, "aragorn");
        Party saruman = party(room, "saruman");

        // 4-3-1 (8) vs. 6-2 (8): the defender wins the tie.
        GameSessionController.fight(room, aragorn, saruman, dice(4, 3, 1, 6, 2));

        assertEquals(2, aragorn.units);
        assertEquals(2, saruman.units);
    }

    @Test
    void theExpansionAddsTheElvesAndRingwraithsWithTheirStrongholds() {
        assertEquals(6, GameSessionController.roster(6).size());
        assertEquals(6, GameSessionController.objectives(6).size());
        List<Party> expanded = GameSessionController.roster(8);
        assertEquals(8, expanded.size());
        assertEquals(4, expanded.stream().filter(party -> party.team.equals("Free Peoples")).count());
        assertTrue(expanded.stream().anyMatch(party -> party.name.equals("Witch-king")
            && party.startingPlace.equals("Minas Morgul")));
        assertTrue(GameSessionController.objectives(8).stream()
            .anyMatch(city -> city.name.equals("Lothlórien") && city.owner.equals("Free Peoples")));
    }

    @Test
    void playersChooseTheirPartysRepresentative() {
        Room room = room();
        Party frodo = party(room, "frodo");
        assertEquals(List.of("Frodo", "Sam", "Merry", "Pippin"), GameSessionController.representatives(frodo));

        GameSessionController.chooseRepresentative(frodo, "Sam");
        assertEquals("Sam", frodo.name);
        assertEquals(List.of("Frodo", "Sam", "Merry", "Pippin"), GameSessionController.representatives(frodo));
        GameSessionController.chooseRepresentative(frodo, null);
        assertEquals("Sam", frodo.name);

        Party aragorn = party(room, "aragorn");
        GameSessionController.chooseRepresentative(aragorn, "Gandalf");
        assertEquals("Gandalf", aragorn.name);

        Party sauron = party(room, "sauron");
        assertEquals(List.of("Sauron"), GameSessionController.representatives(sauron));
        assertThrows(ResponseStatusException.class,
            () -> GameSessionController.chooseRepresentative(sauron, "Éowyn"));
        assertEquals("Sauron", sauron.name);
    }

    @Test
    void eachUnitRollsOneDieAndALoneLeaderRollsOne() {
        Party sauron = party(room(), "sauron");
        assertEquals(3, Battle.dice(sauron));
        sauron.units = 7;
        assertEquals(7, Battle.dice(sauron));
        sauron.units = 0;
        assertEquals(1, Battle.dice(sauron));
        sauron.alive = false;
        assertEquals(0, Battle.dice(sauron));
    }

    @Test
    void aStrongholdsGarrisonFightsLikeAnArmyAndFallsWhenItsWallsAreBeaten() {
        Room room = room();
        Party sauron = party(room, "sauron");   // 3 units: 3 dice
        GameSessionController.Objective gondor = stronghold("gondor", "Free Peoples");
        gondor.units = 1;
        gondor.initialUnits = 1;

        // 1-1-1 (3) vs. one garrison die of 4: the stronghold holds and Sauron loses a unit.
        GameSessionController.siege(room, sauron, gondor, dice(1, 1, 1, 4));
        assertEquals("Free Peoples", gondor.owner);
        assertEquals(2, sauron.units);
        assertEquals(1, gondor.units);

        // 6-5 (11) vs. 4: the garrison loses its last unit, the walls still stand.
        GameSessionController.siege(room, sauron, gondor, dice(6, 5, 4));
        assertEquals("Free Peoples", gondor.owner);
        assertEquals(0, gondor.units);
        assertEquals(0, room.lastBattle.defenderUnits);

        // Bare walls roll one die: 6-5 (11) vs. 6 captures it.
        GameSessionController.siege(room, sauron, gondor, dice(6, 5, 6));
        assertEquals("Sauron's Forces", gondor.owner);
        assertEquals(2, sauron.units);
        assertTrue(room.lastBattle.captured);
    }

    @Test
    void anAttackerWhoFallsOnTheLastTurnStartsTheNextRound() {
        Room room = room();
        room.turnOrderIds = new ArrayList<>(List.of("frodo", "theoden", "aragorn", "saruman", "sauron", "khamul"));
        room.turnIndex = 5;
        GameSessionController.Objective shire = new GameSessionController.Objective();
        shire.id = "shire";
        shire.owner = "Free Peoples";
        room.objectives.add(shire);   // with no strongholds at all, Sauron would count as holding them all
        Party khamul = party(room, "khamul");
        khamul.units = 0;   // just the character: 1 attack die
        Party theoden = party(room, "theoden");

        GameSessionController.fight(room, khamul, theoden, dice(2, 6, 1));   // 2 vs. 7
        GameSessionController.finishAction(room);

        assertFalse(khamul.alive);
        assertEquals(2, room.round);
        assertEquals(0, room.turnIndex);
    }

    @Test
    void battleLogsCountUpAndRecordSurvivors() {
        Room room = room();
        Party aragorn = party(room, "aragorn");
        Party saruman = party(room, "saruman");

        saruman.units = 1;
        GameSessionController.fight(room, aragorn, saruman, dice(6, 5, 1, 4));   // 12 vs. 4
        assertEquals(1, room.lastBattle.id);
        assertEquals(0, room.lastBattle.defenderUnits);
        assertEquals(3, room.lastBattle.attackerUnits);

        GameSessionController.fight(room, aragorn, saruman, dice(6, 5, 4, 1));   // 15 vs. 1
        assertEquals(2, room.lastBattle.id);
        assertTrue(room.lastBattle.defenderFell);
    }

    @Test
    void frodoDoesNotAutoEndHisTurnOnArrivingAtMountDoom() {
        Room room = room();
        Party frodo = party(room, "frodo");
        moveTo(frodo, DOOM_X, DOOM_Y);
        frodo.moved = true;

        GameSessionController.advanceIfNoActions(room);

        assertEquals(0, room.turnIndex);
        assertEquals("frodo", GameSessionController.turnOrder(room).get(room.turnIndex).id);
    }

    @Test
    void frodoStillAutoEndsAfterMovingSomewhereWithNothingToDo() {
        Room room = room();
        Party frodo = party(room, "frodo");
        moveTo(frodo, 50, 50);
        frodo.moved = true;

        GameSessionController.advanceIfNoActions(room);

        assertEquals(1, room.turnIndex);
    }

    @Test
    void endingAMoveInAMountainStrokeHidesThePartyFromOpponentsAndTheHost() {
        TerrainRegion mountain = new TerrainRegion("middle-earth", "middle-earth", "Ridge", "mountain", 4,
            List.of(new TerrainRegion.RegionPoint(40, 40), new TerrainRegion.RegionPoint(40, 60)));
        MovementPathCalculator.Result move = MovementPathCalculator.calculate(
            30, 50, 40, 50, 1000, DOOM_X, DOOM_Y, List.of(mountain));
        assertEquals("mountain", move.destinationTerrain());

        Room room = room();
        Party aragorn = party(room, "aragorn");
        moveTo(aragorn, 40, 50);
        aragorn.terrain = move.destinationTerrain();
        aragorn.hidden = GameSessionController.concealedIn(aragorn.terrain, () -> false);
        assertTrue(aragorn.hidden);

        for (String viewer : List.of("host", "sauron")) {
            GameSessionController.PartyView hiddenView = partyView(room, viewer, "aragorn");
            assertNull(hiddenView.x());
            assertNull(hiddenView.y());
            assertNull(hiddenView.units());
            assertNull(hiddenView.terrain());
            assertNull(hiddenView.movementRemaining());
            assertNull(hiddenView.moved());
            assertNull(hiddenView.attacked());
        }
        GameSessionController.PartyView teammateView = partyView(room, "frodo", "aragorn");
        assertNotNull(teammateView.x());
        assertEquals("mountain", teammateView.terrain());
        assertNotNull(teammateView.movementRemaining());
    }

    @Test
    void mountainsStayHiddenAtTheStartOfEachRoundIncludingLegacyKeys() {
        Room room = room();
        party(room, "aragorn").terrain = "mountain";
        party(room, "theoden").terrain = "mountains";
        room.turnIndex = GameSessionController.turnOrder(room).size() - 1;

        GameSessionController.advanceTurn(room);

        assertEquals(2, room.round);
        assertTrue(party(room, "aragorn").hidden);
        assertTrue(party(room, "theoden").hidden);
        assertFalse(party(room, "frodo").hidden);
    }

    // ------------------------------------------------------------------ bots

    /** Straight-line movement (1 point per 1.5% of map width); attacks, the Ring and turns use the real rules. */
    private static final class BotTable implements WarBot.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public double cost(Room room, Party party, double x, double y) {
            return WarBot.mapDistance(party.x, party.y, x, y) / 1.5;
        }

        @Override
        public void move(Room room, Party party, double x, double y) {
            calls.add("move");
            party.movementRemaining -= cost(room, party, x, y);
            party.moved = true;
            moveTo(party, x, y);
        }

        @Override
        public void attack(Room room, Party party, String targetId) {
            calls.add("attack:" + targetId);
            GameSessionController.applyAttack(room, party, targetId);
        }

        @Override
        public void destroyRing(Room room, Party ringBearer) {
            calls.add("destroy");
            GameSessionController.resolveRingDestruction(room, ringBearer, () -> 6);
        }

        @Override
        public void endTurn(Room room) {
            calls.add("end");
            GameSessionController.advanceTurn(room);
        }
    }

    private static void botTurn(Room room, String id) {
        room.turnIndex = GameSessionController.turnOrder(room).indexOf(party(room, id));
    }

    @Test
    void aRingBearerBotDestroysTheRingAtMountDoom() {
        Room room = room();
        Party frodo = party(room, "frodo");
        frodo.bot = "easy";
        moveTo(frodo, DOOM_X, DOOM_Y);
        botTurn(room, "frodo");
        BotTable table = new BotTable();

        WarBot.playTurn(room, frodo, table, new java.util.Random(1));

        assertEquals(List.of("destroy"), table.calls);
        assertEquals("Free Peoples", room.winner);
    }

    @Test
    void aRingBearerBotMarchesOnMountDoomAndEndsItsTurn() {
        Room room = room();
        Party frodo = party(room, "frodo");
        frodo.bot = "hard";
        frodo.movementRemaining = 12;
        botTurn(room, "frodo");
        double before = WarBot.mapDistance(frodo.x, frodo.y, DOOM_X, DOOM_Y);
        BotTable table = new BotTable();

        WarBot.playTurn(room, frodo, table, new java.util.Random(1));

        assertEquals(List.of("move", "end"), table.calls);
        assertTrue(WarBot.mapDistance(frodo.x, frodo.y, DOOM_X, DOOM_Y) < before - 5);
        assertEquals(1, room.turnIndex);
    }

    @Test
    void aHardRingBearerBotStepsAroundEnemiesInItsPath() {
        Room room = room();
        for (Party party : room.parties) moveTo(party, 5, 90);     // everyone far away...
        Party frodo = party(room, "frodo");
        frodo.bot = "hard";
        frodo.movementRemaining = 12;
        moveTo(frodo, 50, 60);
        Party sauron = party(room, "sauron");
        moveTo(sauron, 64, 63);                                     // ...but Sauron, on the straight road east
        botTurn(room, "frodo");
        double before = WarBot.mapDistance(frodo.x, frodo.y, DOOM_X, DOOM_Y);

        WarBot.playTurn(room, frodo, new BotTable(), new java.util.Random(1));

        double straightStop = WarBot.mapDistance(50 + 18 * (DOOM_X - 50) / before, 60 + 36 * (DOOM_Y - 60) / before * 0.5,
            sauron.x, sauron.y);
        assertTrue(WarBot.mapDistance(frodo.x, frodo.y, DOOM_X, DOOM_Y) < before, "Frodo should still advance");
        assertTrue(WarBot.mapDistance(frodo.x, frodo.y, sauron.x, sauron.y) > straightStop + 2,
            "Frodo should keep clear of Sauron: " + frodo.x + "," + frodo.y);
    }

    @Test
    void aHardBotAttacksOnlyWithGoodOdds() {
        Room room = room();
        Party aragorn = party(room, "aragorn");   // 3 units: 3 dice
        Party saruman = party(room, "saruman");   // 2 units: 2 dice
        aragorn.bot = "hard";
        moveTo(saruman, aragorn.x, aragorn.y);
        botTurn(room, "aragorn");
        BotTable table = new BotTable();
        WarBot.playTurn(room, aragorn, table, new java.util.Random(1));
        assertEquals("attack:saruman", table.calls.get(0));

        Room second = room();
        Party lone = party(second, "aragorn");
        lone.bot = "hard";
        lone.units = 0;                            // a lone leader: 1 die
        Party sauron = party(second, "sauron");    // 3 dice
        moveTo(sauron, lone.x, lone.y);
        botTurn(second, "aragorn");
        BotTable cautious = new BotTable();
        WarBot.playTurn(second, lone, cautious, new java.util.Random(1));
        assertTrue(cautious.calls.stream().noneMatch(call -> call.startsWith("attack")), cautious.calls.toString());
        assertTrue(cautious.calls.contains("end"));
    }

    @Test
    void botSeatsCountAsTakenAndShowInTheLobby() {
        Room room = room();
        Party sauron = party(room, "sauron");
        sauron.playerTokenHash = null;
        assertFalse(GameSessionController.seatTaken(sauron));
        sauron.bot = "hard";
        assertTrue(GameSessionController.seatTaken(sauron));
        GameSessionController.SeatView seat = GameSessionController.view(room, SeatSessions.HOST_SEAT).seats().stream()
            .filter(view -> view.id().equals("sauron")).findFirst().orElseThrow();
        assertTrue(seat.connected());
        assertEquals("hard", seat.bot());
    }

    private static GameSessionController.PartyView partyView(Room room, String seat, String id) {
        return GameSessionController.view(room, seat).parties().stream()
            .filter(view -> view.id().equals(id)).findFirst().orElseThrow();
    }

    private static IntSupplier failIfRolled() {
        return () -> {
            throw new AssertionError("No interception roll was expected.");
        };
    }

    private static GameSessionController.Objective stronghold(String id, String owner) {
        GameSessionController.Objective objective = new GameSessionController.Objective();
        objective.id = id;
        objective.name = id;
        objective.owner = owner;
        return objective;
    }

    private static IntSupplier dice(int... faces) {
        return dice(new AtomicInteger(), faces);
    }

    private static IntSupplier dice(AtomicInteger rolls, int... faces) {
        return () -> {
            int index = rolls.getAndIncrement();
            if (index >= faces.length) throw new AssertionError("Unexpected extra die roll.");
            return faces[index];
        };
    }

    private static Room room() {
        Room room = new Room();
        room.code = "TEST01";
        room.status = "campaign";
        room.hostTokenHash = SeatSessions.hash("host-secret");
        room.round = 1;
        room.ringHolder = "frodo";
        room.mountDoomX = DOOM_X;
        room.mountDoomY = DOOM_Y;
        room.parties.add(seat(new Party("frodo", "Frodo", "Free Peoples", "Hobbits", 1, "Hobbiton", true, 14)));
        room.parties.add(seat(new Party("theoden", "Théoden", "Free Peoples", "Riders of Rohan", 2, "Edoras", false, 12)));
        room.parties.add(seat(new Party("aragorn", "Aragorn", "Free Peoples", "Fellowship", 3, "Rivendell", false, 12)));
        room.parties.add(seat(new Party("khamul", "Khamûl", "Sauron's Forces", "Easterlings", 1, "Rhûn", false, 12)));
        room.parties.add(seat(new Party("saruman", "Saruman", "Sauron's Forces", "Uruk-hai", 2, "Isengard", false, 12)));
        room.parties.add(seat(new Party("sauron", "Sauron", "Sauron's Forces", "Orcs", 3, "Barad-dûr", false, 12)));
        room.turnOrderIds = new ArrayList<>(List.of("frodo", "khamul", "theoden", "saruman", "aragorn", "sauron"));
        for (int index = 0; index < room.parties.size(); index++) {
            moveTo(room.parties.get(index), 10 + index * 5, 20);
        }
        return room;
    }

    private static Party seat(Party party) {
        party.playerTokenHash = SeatSessions.hash("secret-" + party.id);
        party.playerName = party.name;
        return party;
    }

    private static Party party(Room room, String id) {
        return room.parties.stream().filter(party -> party.id.equals(id)).findFirst().orElseThrow();
    }

    private static void moveTo(Party party, double x, double y) {
        party.x = x;
        party.y = y;
        party.terrain = Math.hypot(x - DOOM_X, y - DOOM_Y) < 0.5 ? "mount-doom" : "plains";
    }
}
