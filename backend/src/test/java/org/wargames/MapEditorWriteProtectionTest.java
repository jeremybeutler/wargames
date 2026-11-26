package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MapEditorWriteProtectionTest {
    @Test
    void requiresTheAdministratorTokenForMapWrites() throws Exception {
        MapEditorWriteProtection protection =
            new MapEditorWriteProtection(new MapEditorAccess("secret"));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/places/12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(protection.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());

        request.addHeader("X-Map-Editor-Token", "secret");
        response = new MockHttpServletResponse();
        assertTrue(protection.preHandle(request, response, new Object()));
    }

    @Test
    void permitsMapReadsButFailsClosedWhenAccessIsNotConfigured() throws Exception {
        MapEditorWriteProtection protection =
            new MapEditorWriteProtection(new MapEditorAccess(""));
        MockHttpServletRequest readRequest = new MockHttpServletRequest("GET", "/api/places");
        assertTrue(protection.preHandle(readRequest, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest writeRequest = new MockHttpServletRequest("POST", "/api/places");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(protection.preHandle(writeRequest, response, new Object()));
        assertEquals(503, response.getStatus());
    }
}
