package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ChatServiceTest {
    private final ChatService chat = new ChatService(null);
    private final ChatService.Speaker frodo = new ChatService.Speaker("frodo", "Ann · Frodo", "Free Peoples");
    private final ChatService.Speaker sauron = new ChatService.Speaker("sauron", "Ben · Sauron", "Sauron's Forces");

    @Test
    void theHostChoosesOffTextVoiceOrBoth() {
        assertEquals("text", ChatService.mode(null));
        assertEquals("both", ChatService.mode(" Both "));
        assertThrows(ResponseStatusException.class, () -> ChatService.mode("smoke signals"));
        assertTrue(ChatService.allowsText(null));   // games saved before chat existed
        assertTrue(ChatService.allowsText("both"));
        assertFalse(ChatService.allowsText("voice"));
        assertTrue(ChatService.allowsVoice("voice"));
        assertFalse(ChatService.allowsVoice("text"));
        assertThrows(ResponseStatusException.class, () -> chat.messages("ABC123", "voice", null));
        assertThrows(ResponseStatusException.class, () -> chat.joinVoice("ABC123", "text", frodo, false));
    }

    @Test
    void voiceSignalsReachOnlyTheirRecipientOnce() {
        chat.joinVoice("ABC123", "voice", frodo, false);
        ChatService.VoiceView joined = chat.joinVoice("ABC123", "voice", sauron, true);
        assertEquals(List.of("frodo", "sauron"), joined.participants().stream().map(ChatService.Participant::seat).toList());
        assertTrue(joined.participants().get(1).muted());

        chat.signal("ABC123", "voice", "frodo", "sauron", "offer", "{\"sdp\":\"v=0\"}");
        assertTrue(chat.pollVoice("ABC123", "voice", "frodo", 0).signals().isEmpty());
        ChatService.VoiceView received = chat.pollVoice("ABC123", "voice", "sauron", 0);
        assertEquals(1, received.signals().size());
        assertEquals("offer", received.signals().get(0).kind());
        assertEquals("frodo", received.signals().get(0).from());

        // Acknowledged by polling past its id: not delivered again.
        long last = received.lastSignal();
        assertTrue(chat.pollVoice("ABC123", "voice", "sauron", last).signals().isEmpty());
        assertTrue(chat.pollVoice("ABC123", "voice", "sauron", 0).signals().isEmpty());
    }

    @Test
    void onlyMembersCanSignalAndLeavingClearsTheirMail() {
        assertThrows(ResponseStatusException.class,
            () -> chat.signal("DEF456", "both", "frodo", "sauron", "offer", "x"));
        chat.joinVoice("DEF456", "both", frodo, false);
        chat.joinVoice("DEF456", "both", sauron, false);
        assertThrows(ResponseStatusException.class,
            () -> chat.signal("DEF456", "both", "frodo", "sauron", "smoke", "x"));
        chat.signal("DEF456", "both", "frodo", "sauron", "ice", "candidate");
        chat.leaveVoice("DEF456", "sauron");
        assertEquals(List.of("frodo"), chat.pollVoice("DEF456", "both", "frodo", 0).participants().stream()
            .map(ChatService.Participant::seat).toList());
        // A signal to someone who has left is dropped, not queued.
        chat.signal("DEF456", "both", "frodo", "sauron", "ice", "candidate");
        assertTrue(chat.pollVoice("DEF456", "both", "sauron", 0).signals().isEmpty());
    }
}
