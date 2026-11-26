package org.bomwargames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MapEditorAccess {
    private final String token;

    public MapEditorAccess(@Value("${map-editor.token:}") String token) {
        this.token = token == null ? "" : token;
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }

    public boolean isAuthorized(String candidate) {
        if (!isConfigured() || candidate == null || candidate.isBlank()) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }
}
