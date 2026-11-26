package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import org.wargames.GameSessionController.Party;
import org.wargames.GameSessionController.Room;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class SeatSessionsTest {
    @Test
    void aSeatIsRecognizedOnlyWithItsOwnCookie() {
        Room room = room();

        assertEquals("frodo", GameSessionController.authenticate(room, request("frodo", "frodo", "frodo-secret")));
        assertEquals("host", GameSessionController.authenticate(room, request("host", "host", "host-secret")));
        assertNull(GameSessionController.authenticate(room, new MockHttpServletRequest()));

        assertForbidden(room, request("frodo", "frodo", "wrong-secret"));
        assertForbidden(room, request("frodo", null, null));
        // Holding the host cookie does not let a tab act as Frodo.
        assertForbidden(room, request("frodo", "host", "host-secret"));
        // Frodo's secret presented under the host's name is still rejected.
        assertForbidden(room, request("host", "host", "frodo-secret"));
        assertForbidden(room, request("../host", "host", "host-secret"));
    }

    @Test
    void cookiesAreHttpOnlyStrictAndScopedToTheGame() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        SeatSessions.issue(request, response, "ABC234", "frodo", "s3cret");

        String header = response.getHeader("Set-Cookie");
        assertTrue(header.startsWith("wg_seat_frodo=s3cret"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Path=/api/games/ABC234"));
        assertFalse(header.contains("Secure"));

        request.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        SeatSessions.issue(request, secureResponse, "ABC234", "frodo", "s3cret");
        assertTrue(secureResponse.getHeader("Set-Cookie").contains("Secure"));
    }

    @Test
    void plainTextSecretsFromOlderSavesAreHashedOnLoad() {
        Room room = room();
        room.hostTokenHash = null;
        room.hostToken = "legacy-host";
        Party frodo = room.parties.get(0);
        frodo.playerTokenHash = null;
        frodo.playerToken = "legacy-frodo";

        GameSessionController.hashLegacySecrets(room);

        assertNull(room.hostToken);
        assertNull(frodo.playerToken);
        assertTrue(SeatSessions.matches("legacy-host", room.hostTokenHash));
        assertTrue(SeatSessions.matches("legacy-frodo", frodo.playerTokenHash));
        assertNotEquals("legacy-frodo", frodo.playerTokenHash);
    }

    @Test
    void newSecretsAreLongAndUnique() {
        String first = SeatSessions.newSecret();
        assertEquals(43, first.length());
        assertNotEquals(first, SeatSessions.newSecret());
    }

    private static void assertForbidden(Room room, MockHttpServletRequest request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> GameSessionController.authenticate(room, request));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    private static MockHttpServletRequest request(String seatHeader, String cookieSeat, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SeatSessions.SEAT_HEADER, seatHeader);
        if (cookieSeat != null) request.setCookies(new Cookie("wg_seat_" + cookieSeat, secret));
        return request;
    }

    private static Room room() {
        Room room = new Room();
        room.code = "ABC234";
        room.status = "lobby";
        room.hostTokenHash = SeatSessions.hash("host-secret");
        Party frodo = new Party("frodo", "Frodo", "Free Peoples", "Hobbits", 1, "Hobbiton", true, 14);
        frodo.playerTokenHash = SeatSessions.hash("frodo-secret");
        room.parties.add(frodo);
        return room;
    }
}
