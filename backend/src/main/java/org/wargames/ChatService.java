package org.wargames;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.wargames.model.ChatMessage;
import org.wargames.repository.ChatMessageRepository;

/**
 * Table talk for both campaigns, as the host chose when creating the game: off, text, voice or
 * both. The game controllers check the seat (its cookie is scoped to their API path) and pass
 * it in here.
 *
 * <ul>
 *   <li><b>Text</b> messages are stored in their own table (the last {@value #KEEP_MESSAGES} per
 *       game), never in the game state, so a message can't collide with a move being saved.</li>
 *   <li><b>Voice</b> goes browser to browser (WebRTC). The server only relays each pair's
 *       connection offers, answers and network candidates, held briefly in memory, and keeps
 *       track of who is in the voice channel.</li>
 * </ul>
 */
@Service
public class ChatService {
    static final String OFF = "off";
    static final String TEXT = "text";
    static final String VOICE = "voice";
    static final String BOTH = "both";
    static final Set<String> MODES = Set.of(OFF, TEXT, VOICE, BOTH);

    static final int KEEP_MESSAGES = 200;
    static final int MAX_LENGTH = 300;
    /** At most one message per seat this often, to keep a stuck key from flooding the table. */
    static final long MIN_GAP_MS = 600;
    /** A voice participant who stops polling for this long has left. */
    static final long VOICE_TIMEOUT_MS = 12_000;
    /** Relayed signals older than this are dropped; WebRTC setup takes a few seconds at most. */
    static final long SIGNAL_TTL_MS = 30_000;
    static final int MAX_VOICE = 9;

    private final ChatMessageRepository messages;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<String, VoiceRoom> voiceRooms = new ConcurrentHashMap<>();

    public ChatService(ChatMessageRepository messages) {
        this.messages = messages;
    }

    /** The mode for a new game; text by default. */
    static String mode(String requested) {
        if (requested == null || requested.isBlank()) return TEXT;
        String mode = requested.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw badRequest("Chat must be off, text, voice or both.");
        return mode;
    }

    static boolean allowsText(String mode) {
        return mode == null || TEXT.equals(mode) || BOTH.equals(mode);
    }

    static boolean allowsVoice(String mode) {
        return VOICE.equals(mode) || BOTH.equals(mode);
    }

    // ------------------------------------------------------------------ text

    record MessageView(long id, String seat, String author, String side, String text, String sentAt) {}

    record Speaker(String seat, String name, String side) {}

    @Transactional(readOnly = true)
    public List<MessageView> messages(String code, String mode, Long since) {
        if (!allowsText(mode)) throw conflict("Text chat is off for this game.");
        List<ChatMessage> found = since == null || since <= 0
            ? reversed(messages.findTop100ByGameCodeOrderByIdDesc(code))
            : messages.findTop100ByGameCodeAndIdGreaterThanOrderByIdAsc(code, since);
        return found.stream().map(ChatService::view).toList();
    }

    @Transactional
    public MessageView send(String code, String mode, Speaker speaker, String text) {
        if (!allowsText(mode)) throw conflict("Text chat is off for this game.");
        String clean = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) throw badRequest("Write a message first.");
        if (clean.length() > MAX_LENGTH) throw badRequest("Messages are limited to " + MAX_LENGTH + " characters.");
        long now = System.currentTimeMillis();
        Long previous = lastSent.put(code + "/" + speaker.seat(), now);
        if (previous != null && now - previous < MIN_GAP_MS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Slow down a little.");
        }
        ChatMessage saved = messages.save(new ChatMessage(code, speaker.seat(), trim(speaker.name(), 60),
            speaker.side() == null ? null : trim(speaker.side(), 40), clean, Instant.now()));
        prune(code);
        return view(saved);
    }

    /** Keeps the newest {@value #KEEP_MESSAGES} messages of a game (checked cheaply by count first). */
    private void prune(String code) {
        if (messages.countByGameCode(code) <= KEEP_MESSAGES + 20) return;
        List<ChatMessage> newest = messages.findTop200ByGameCodeOrderByIdDesc(code);
        messages.deleteOlder(code, newest.get(newest.size() - 1).getId());
    }

    private static MessageView view(ChatMessage message) {
        return new MessageView(message.getId(), message.getSeat(), message.getAuthor(), message.getSide(),
            message.getText(), message.getSentAt().toString());
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    private static String trim(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    // ------------------------------------------------------------------ voice

    record Participant(String seat, String name, String side, boolean muted) {}

    record Signal(long id, String from, String to, String kind, String data) {}

    record VoiceView(List<Participant> participants, List<Signal> signals, long lastSignal) {}

    private static final class Member {
        final String name;
        final String side;
        volatile boolean muted;
        volatile long seenAt;

        Member(String name, String side) {
            this.name = name;
            this.side = side;
        }
    }

    private record Queued(Signal signal, long at) {}

    private static final class VoiceRoom {
        final Map<String, Member> members = new LinkedHashMap<>();
        final List<Queued> signals = new ArrayList<>();
        long nextSignal = 1;
    }

    /** Joins (or refreshes) the voice channel and returns who is in it. */
    public VoiceView joinVoice(String code, String mode, Speaker speaker, boolean muted) {
        if (!allowsVoice(mode)) throw conflict("Voice chat is off for this game.");
        VoiceRoom room = voiceRooms.computeIfAbsent(code, ignored -> new VoiceRoom());
        synchronized (room) {
            expire(room);
            Member member = room.members.get(speaker.seat());
            if (member == null) {
                if (room.members.size() >= MAX_VOICE) throw conflict("The voice channel is full.");
                member = new Member(speaker.name(), speaker.side());
                room.members.put(speaker.seat(), member);
            }
            member.muted = muted;
            member.seenAt = System.currentTimeMillis();
            return snapshot(room, speaker.seat(), 0);
        }
    }

    public void leaveVoice(String code, String seat) {
        VoiceRoom room = voiceRooms.get(code);
        if (room == null) return;
        synchronized (room) {
            room.members.remove(seat);
            room.signals.removeIf(queued -> queued.signal().to().equals(seat) || queued.signal().from().equals(seat));
        }
    }

    /** Relays a WebRTC offer, answer or ICE candidate to another member of the channel. */
    public void signal(String code, String mode, String from, String to, String kind, String data) {
        if (!allowsVoice(mode)) throw conflict("Voice chat is off for this game.");
        if (!Set.of("offer", "answer", "ice").contains(kind)) throw badRequest("Unknown signal.");
        if (data == null || data.length() > 20_000) throw badRequest("That signal is too large.");
        VoiceRoom room = voiceRooms.get(code);
        if (room == null) throw conflict("Join the voice channel first.");
        synchronized (room) {
            if (!room.members.containsKey(from)) throw conflict("Join the voice channel first.");
            if (!room.members.containsKey(to)) return; // They left; nothing to deliver.
            room.signals.add(new Queued(new Signal(room.nextSignal++, from, to, kind, data), System.currentTimeMillis()));
        }
    }

    /** Polled by each member: who is in the channel, and the signals addressed to this seat. */
    public VoiceView pollVoice(String code, String mode, String seat, long since) {
        if (!allowsVoice(mode)) return new VoiceView(List.of(), List.of(), since);
        VoiceRoom room = voiceRooms.get(code);
        if (room == null) return new VoiceView(List.of(), List.of(), since);
        synchronized (room) {
            Member self = room.members.get(seat);
            if (self != null) self.seenAt = System.currentTimeMillis();
            expire(room);
            return snapshot(room, seat, since);
        }
    }

    private static void expire(VoiceRoom room) {
        long now = System.currentTimeMillis();
        room.members.values().removeIf(member -> now - member.seenAt > VOICE_TIMEOUT_MS);
        room.signals.removeIf(queued -> now - queued.at() > SIGNAL_TTL_MS
            || !room.members.containsKey(queued.signal().to()));
    }

    private static VoiceView snapshot(VoiceRoom room, String seat, long since) {
        List<Participant> participants = room.members.entrySet().stream()
            .map(entry -> new Participant(entry.getKey(), entry.getValue().name, entry.getValue().side, entry.getValue().muted))
            .toList();
        List<Signal> mine = room.signals.stream().map(Queued::signal)
            .filter(signal -> signal.to().equals(seat) && signal.id() > since).toList();
        // Delivered signals are not needed again.
        room.signals.removeIf(queued -> queued.signal().to().equals(seat) && queued.signal().id() <= since);
        return new VoiceView(participants, mine, room.nextSignal - 1);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
