package org.bomwargames;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/map-editor")
public class MapEditorAccessController {
    private final MapEditorAccess access;

    public MapEditorAccessController(MapEditorAccess access) {
        this.access = access;
    }

    @PostMapping("/access")
    public ResponseEntity<Void> verifyAccess(
            @RequestHeader(name = "X-Map-Editor-Token", required = false) String token) {
        if (!access.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Map editor access is not configured. Set MAP_EDITOR_TOKEN on the backend.");
        }
        if (!access.isAuthorized(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The map editor token is invalid.");
        }
        return ResponseEntity.noContent().build();
    }
}
