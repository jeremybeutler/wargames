package org.wargames;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Plays bot turns in the background, one turn at a time with a pause between them, so the
 * people at the table can follow each move and battle as it happens (their screens poll every
 * two seconds). A game is scheduled whenever it is saved with a bot to act next.
 *
 * <p>Bot turns and the few actions players may take out of turn (Iron Throne pacts) run under
 * a per-game lock, so neither can overwrite the other's save.
 */
@Component
public class BotScheduler {
    /**
     * Pause before a bot acts: long enough for every screen (polling every two seconds) to pick up
     * the last turn and play its movement animation, plus a little "thinking" time.
     */
    static final long TURN_DELAY_MS = 3800;
    /** Up to this much extra, chosen at random, so bots don't move with a metronome's rhythm. */
    static final long TURN_JITTER_MS = 1600;
    private static final Logger LOG = LoggerFactory.getLogger(BotScheduler.class);
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /**
     * Extra pause after a battle: screens first finish the attacker's march, then roll the dice
     * and leave the result up for a few seconds.
     */
    static final long BATTLE_DELAY_MS = 6000;

    private record Pending(long dueAt, Consumer<String> turn) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** The last battle each game was scheduled after, so only a new battle adds the extra pause. */
    private final Map<String, Integer> lastBattle = new ConcurrentHashMap<>();

    /**
     * Plays the next bot turn of {@code code} after the usual pause, unless one is already queued.
     * The pause is longer when {@code battleId} (the game's latest battle, or null) is a battle
     * this game had not been scheduled after before.
     */
    void schedule(String code, Consumer<String> turn, Integer battleId) {
        int battle = battleId == null ? -1 : battleId;
        Integer previous = lastBattle.put(code, battle);
        boolean newBattle = previous != null && previous != battle && battle != -1;
        long delay = TURN_DELAY_MS + java.util.concurrent.ThreadLocalRandom.current().nextLong(TURN_JITTER_MS + 1)
            + (newBattle ? BATTLE_DELAY_MS : 0);
        pending.putIfAbsent(code, new Pending(System.currentTimeMillis() + delay, turn));
    }

    boolean isScheduled(String code) {
        return pending.containsKey(code);
    }

    @Scheduled(fixedDelay = 400)
    void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (entry.getValue().dueAt() > now) continue;
            String code = entry.getKey();
            if (!pending.remove(code, entry.getValue())) continue;
            try {
                withLock(code, () -> {
                    entry.getValue().turn().accept(code);
                    return null;
                });
            } catch (RuntimeException failure) {
                LOG.warn("A bot turn in game {} failed", code, failure);
            }
        }
    }

    /** Runs {@code work} while holding this game's lock. The work must save before returning. */
    static <T> T withLock(String code, Supplier<T> work) {
        ReentrantLock lock = LOCKS.computeIfAbsent(code, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
