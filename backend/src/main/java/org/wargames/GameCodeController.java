package org.wargames;

import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.wargames.model.GameSession;
import org.wargames.repository.GameSessionRepository;

/** Tells the home screen which game a code belongs to, so one Join box serves every campaign. */
@RestController
public class GameCodeController {
    private final GameSessionRepository games;

    public GameCodeController(GameSessionRepository games) {
        this.games = games;
    }

    @GetMapping("/api/game-codes/{code}")
    @Transactional(readOnly = true)
    public Map<String, String> lookup(@PathVariable String code) {
        GameSession session = games.findById(code.toUpperCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game code not found."));
        String type = GameSession.IRON_THRONE.equals(session.getGameType()) ? GameSession.IRON_THRONE : "war-of-the-ring";
        return Map.of("code", session.getCode(), "type", type);
    }
}
