package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.wargames.GameSessionController.Party;
import org.wargames.GameSessionController.Room;
import org.wargames.model.TerrainRegion;
import org.junit.jupiter.api.Test;

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
    void anOpponentWhoHitsFrodoCapturesTheRingForSauron() {
        Room room = room();
        Party frodo = party(room, "frodo");
        Party sauron = party(room, "sauron");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(sauron, DOOM_X, DOOM_Y);
        sauron.hidden = true;

        // 3d6 = 10 vs. armor 10 - 1 (Mount Doom) = 9: a hit.
        GameSessionController.resolveRingDestruction(room, frodo, () -> 10);

        assertEquals("sauron", room.ringHolder);
        assertEquals("Sauron's Forces", room.winner);
        assertEquals(0, frodo.units);
        assertFalse(sauron.hidden);
        assertTrue(room.events.stream().anyMatch(event -> event.contains("captured the Ring (10 vs. 9)")));
    }

    @Test
    void theRingIsDestroyedWhenEveryInterceptorMisses() {
        Room room = room();
        Party frodo = party(room, "frodo");
        Party khamul = party(room, "khamul");
        Party saruman = party(room, "saruman");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(khamul, DOOM_X, DOOM_Y);
        moveTo(saruman, DOOM_X + 0.2, DOOM_Y);
        khamul.hidden = true;
        saruman.hidden = true;
        AtomicInteger rolls = new AtomicInteger();

        // 9 vs. armor 9 is a tie, which misses.
        GameSessionController.resolveRingDestruction(room, frodo, () -> {
            rolls.incrementAndGet();
            return 9;
        });

        assertEquals(2, rolls.get());
        assertEquals("destroyed", room.ringHolder);
        assertEquals("Free Peoples", room.winner);
        assertEquals(1, frodo.units);
        assertFalse(khamul.hidden);
        assertFalse(saruman.hidden);
        assertEquals(2, room.events.stream().filter(event -> event.contains("but missed (9 vs. 9)")).count());
    }

    @Test
    void interceptorsAttackInTurnOrderAndTheFirstHitEndsTheAttempt() {
        Room room = room();
        room.turnOrderIds = new ArrayList<>(List.of("saruman", "frodo", "khamul", "theoden", "aragorn", "sauron"));
        Party frodo = party(room, "frodo");
        moveTo(frodo, DOOM_X, DOOM_Y);
        moveTo(party(room, "khamul"), DOOM_X, DOOM_Y);
        moveTo(party(room, "saruman"), DOOM_X, DOOM_Y);
        party(room, "saruman").attackBoost = 2;
        AtomicInteger rolls = new AtomicInteger();

        GameSessionController.resolveRingDestruction(room, frodo, () -> {
            rolls.incrementAndGet();
            return 8;
        });

        assertEquals(1, rolls.get());
        assertEquals("sauron", room.ringHolder);
        assertTrue(room.events.stream().anyMatch(event -> event.startsWith("Saruman hit Frodo")));
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

    private static GameSessionController.PartyView partyView(Room room, String seat, String id) {
        return GameSessionController.view(room, seat).parties().stream()
            .filter(view -> view.id().equals(id)).findFirst().orElseThrow();
    }

    private static IntSupplier failIfRolled() {
        return () -> {
            throw new AssertionError("No interception roll was expected.");
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
