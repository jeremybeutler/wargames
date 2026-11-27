package org.wargames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wargames.GameSessionController.Objective;
import org.wargames.GameSessionController.Party;
import org.wargames.GameSessionController.Room;

/**
 * Plays one War of the Ring turn for a bot party. Bots only know what a player on their team
 * would: enemy parties hidden in the mountains are invisible to them.
 *
 * <ul>
 *   <li><b>Ring-bearer:</b> heads for Mount Doom and destroys the Ring there (a hard bot waits a
 *       turn if the interceptors on the mountain would likely kill it).</li>
 *   <li><b>Free Peoples:</b> clear enemies near Mount Doom and the Ring-bearer, and escort it.</li>
 *   <li><b>Sauron's Forces:</b> a hard team keeps one party on Mount Doom; the rest hunt the
 *       Ring-bearer when it shows itself and take the weakest strongholds.</li>
 * </ul>
 *
 * Easy bots pick the nearest target, wander part of the way and take bad fights. Hard bots
 * weigh each fight by simulating the dice and only take good odds.
 */
final class WarBot {
    private static final Logger LOG = LoggerFactory.getLogger(WarBot.class);
    /** What a bot can do; the controller supplies the real rules. */
    interface Actions {
        /** Movement points to reach (x, y), or infinity if the move is not allowed. */
        double cost(Room room, Party party, double x, double y);
        void move(Room room, Party party, double x, double y);
        void attack(Room room, Party party, String targetId);
        void destroyRing(Room room, Party ringBearer);
        void endTurn(Room room);
    }

    static final String EASY = "easy";
    static final String HARD = "hard";
    private static final String FREE = "Free Peoples";

    private final Room room;
    private final Party bot;
    private final Actions actions;
    private final Random random;
    private final boolean hard;

    private WarBot(Room room, Party bot, Actions actions, Random random) {
        this.room = room;
        this.bot = bot;
        this.actions = actions;
        this.random = random;
        this.hard = HARD.equals(bot.bot);
    }

    static void playTurn(Room room, Party bot, Actions actions, Random random) {
        new WarBot(room, bot, actions, random).play();
    }

    private void play() {
        try {
            if (destroyRingIfReady()) return;
            if (attackHereIfWorthIt()) {
                finish();
                return;
            }
            if (hard && isRingBearer()) {
                sneakTowardMountDoom();
            } else {
                double[] goal = goal();
                if (goal != null && stillMyTurn() && !bot.moved) moveToward(goal[0], goal[1]);
            }
            if (!stillMyTurn()) return;
            if (destroyRingIfReady()) return;
            if (stillMyTurn() && !bot.attacked) attackHereIfWorthIt();
        } catch (RuntimeException refused) {
            // A move or attack the rules refuse just ends the bot's turn; the game must never stall.
            LOG.info("Bot {} stopped its turn early: {}", bot.name, refused.getMessage());
        }
        finish();
    }

    private void finish() {
        if (stillMyTurn()) actions.endTurn(room);
    }

    private boolean stillMyTurn() {
        if (room.winner != null || !bot.alive) return false;
        List<Party> order = GameSessionController.turnOrder(room);
        return room.turnIndex < order.size() && order.get(room.turnIndex) == bot;
    }

    // ------------------------------------------------------------------ the Ring

    private boolean isRingBearer() {
        return "frodo".equals(bot.id) && "frodo".equals(room.ringHolder);
    }

    private boolean destroyRingIfReady() {
        if (!isRingBearer() || !GameSessionController.atMountDoom(room, bot) || !stillMyTurn()) return false;
        if (hard && room.round < 30) {
            // Each interceptor on the mountain fights the Ring-bearer once; wait if that looks fatal.
            double survive = 1;
            int units = bot.units;
            for (Party enemy : enemies()) {
                if (!GameSessionController.atMountDoom(room, enemy)) continue;
                // The interceptor attacks; the Ring-bearer defends at -1 on the mountain.
                double lose = winOdds(Battle.dice(enemy), enemy.attackBoost, Math.max(1, units), -1);
                survive *= units > 0 ? 1 : 1 - lose;
                if (lose > 0.5) units -= 1;
            }
            if (survive < 0.5) return false;
        }
        actions.destroyRing(room, bot);
        return true;
    }

    // ------------------------------------------------------------------ fighting

    private List<Party> enemies() {
        return room.parties.stream().filter(party -> party.alive && !party.team.equals(bot.team)).toList();
    }

    /** Enemy parties this bot's team can see. */
    private List<Party> visibleEnemies() {
        return enemies().stream().filter(party -> !party.hidden).toList();
    }

    private boolean attackHereIfWorthIt() {
        if (bot.attacked || !stillMyTurn()) return false;
        Party defender = enemies().stream()
            .filter(party -> GameSessionController.sameLocation(bot.x, bot.y, party.x, party.y))
            .findFirst().orElse(null);
        if (defender != null) {
            double odds = winOdds(Battle.dice(bot), bot.attackBoost, Battle.dice(defender), defenseModifier(defender));
            if (odds < threshold(defender)) return false;
            actions.attack(room, bot, defender.id);
            return true;
        }
        Objective stronghold = room.objectives.stream()
            .filter(city -> !city.owner.equals(bot.team) && GameSessionController.sameLocation(bot.x, bot.y, city.x, city.y))
            .findFirst().orElse(null);
        if (stronghold == null) return false;
        double odds = winOdds(Battle.dice(bot), bot.attackBoost, Battle.dice(stronghold), 0);
        if (odds < threshold(null) && !(stronghold.units == 0 && odds > 0.3)) return false;
        actions.attack(room, bot, stronghold.id);
        return true;
    }

    /** How good the odds must be. The Ring-bearer never picks fights; Sauron risks more for the Ring. */
    private double threshold(Party target) {
        if (isRingBearer()) return 1.1;
        if (!hard) return 0.2 + random.nextDouble() * 0.25;
        if (target != null && "frodo".equals(target.id) && "frodo".equals(room.ringHolder)) return 0.35;
        return 0.55;
    }

    private static int defenseModifier(Party defender) {
        return "volcanic".equals(defender.terrain) || "mount-doom".equals(defender.terrain) ? -1 : 0;
    }

    private double winOdds(int attackDice, int attackModifier, int defenseDice, int defenseModifier) {
        return Battle.winChance(attackDice, attackModifier, defenseDice, defenseModifier, random);
    }

    // ------------------------------------------------------------------ where to go

    private record Goal(double x, double y, double score) {}

    private double[] goal() {
        double doomX = room.mountDoomX;
        double doomY = room.mountDoomY;
        if (isRingBearer()) return new double[] {doomX, doomY};
        Party ringBearer = room.parties.stream()
            .filter(party -> "frodo".equals(party.id) && party.alive).findFirst().orElse(null);

        if (hard && !FREE.equals(bot.team) && this.bot == doomGuard()) {
            return new double[] {doomX, doomY};
        }

        List<Goal> goals = new ArrayList<>();
        for (Party enemy : visibleEnemies()) {
            double odds = winOdds(Battle.dice(bot), bot.attackBoost, Battle.dice(enemy), defenseModifier(enemy));
            double value = 1;
            boolean isBearer = "frodo".equals(enemy.id) && "frodo".equals(room.ringHolder);
            if (isBearer) value = 6;
            if (FREE.equals(bot.team) && mapDistance(enemy.x, enemy.y, doomX, doomY) < 12) value = 3;
            if (FREE.equals(bot.team) && ringBearer != null && mapDistance(enemy.x, enemy.y, ringBearer.x, ringBearer.y) < 10) {
                value = Math.max(value, 2.5);
            }
            if (hard && odds < (isBearer ? 0.35 : 0.5)) continue;
            goals.add(new Goal(enemy.x, enemy.y, value * odds));
        }
        for (Objective city : room.objectives) {
            if (city.owner.equals(bot.team)) continue;
            double odds = winOdds(Battle.dice(bot), bot.attackBoost, Battle.dice(city), 0);
            // Sauron wins by holding every stronghold; the Free Peoples take them back only when handy.
            double value = FREE.equals(bot.team) ? 0.6 : 1.6;
            if (city.units == 0) value *= 1.5;
            if (hard && odds < 0.5 && city.units > 0) continue;
            goals.add(new Goal(city.x, city.y, value * Math.max(odds, 0.15)));
        }

        Goal best;
        if (hard) {
            best = goals.stream()
                .max(Comparator.comparingDouble(goal -> goal.score() / (1 + mapDistance(bot.x, bot.y, goal.x(), goal.y()) / 12)))
                .orElse(null);
        } else {
            // Easy bots go for whatever is closest, with a little randomness.
            best = goals.stream()
                .min(Comparator.comparingDouble(goal -> mapDistance(bot.x, bot.y, goal.x(), goal.y()) * (0.7 + random.nextDouble() * 0.6)))
                .orElse(null);
        }
        if (best != null) return new double[] {best.x(), best.y()};
        // Nothing worth chasing: the Free Peoples escort the Ring-bearer, Sauron's Forces close on Mount Doom.
        if (FREE.equals(bot.team) && ringBearer != null && ringBearer != bot) return new double[] {ringBearer.x, ringBearer.y};
        return new double[] {doomX, doomY};
    }

    /** A hard Sauron team keeps the living party nearest Mount Doom (not Sauron himself) on guard there. */
    private Party doomGuard() {
        if (!"frodo".equals(room.ringHolder)) return null;
        return room.parties.stream()
            .filter(party -> party.alive && party.team.equals(bot.team) && !"sauron".equals(party.id))
            .min(Comparator.comparingDouble(party -> mapDistance(party.x, party.y, room.mountDoomX, room.mountDoomY)))
            .orElse(null);
    }

    /** About how far (in % of map width) an enemy can march in one turn. */
    private static final double ENEMY_REACH = 20;

    /**
     * A hard Ring-bearer weighs a few headings toward Mount Doom and stops where it gains the
     * most ground while staying out of reach of the enemies it can see (staying put counts too).
     */
    private void sneakTowardMountDoom() {
        if (bot.moved || !stillMyTurn()) return;
        double doomX = room.mountDoomX;
        double doomY = room.mountDoomY;
        double here = mapDistance(bot.x, bot.y, doomX, doomY);
        double[] best = null;
        double bestScore = -danger(bot.x, bot.y);
        if (actions.cost(room, bot, doomX, doomY) <= bot.movementRemaining + 0.0001) {
            best = new double[] {doomX, doomY};
            bestScore = here - danger(doomX, doomY) + 1000; // Reaching the mountain wins outright.
        }
        for (int degrees : new int[] {0, -25, 25, -50, 50}) {
            double angle = Math.toRadians(degrees);
            // Rotate in true map proportions (y is half-scale), then back to map %.
            double dx = doomX - bot.x;
            double dy = (doomY - bot.y) * 0.5;
            double rx = dx * Math.cos(angle) - dy * Math.sin(angle);
            double ry = dx * Math.sin(angle) + dy * Math.cos(angle);
            double toX = Math.max(0, Math.min(100, bot.x + rx));
            double toY = Math.max(0, Math.min(100, bot.y + ry * 2));
            double low = 0;
            double high = 1;
            for (int step = 0; step < 10; step++) {
                double t = (low + high) / 2;
                if (actions.cost(room, bot, lerp(bot.x, toX, t), lerp(bot.y, toY, t)) <= bot.movementRemaining + 0.0001) low = t;
                else high = t;
            }
            double x = lerp(bot.x, toX, low);
            double y = lerp(bot.y, toY, low);
            double score = here - mapDistance(x, y, doomX, doomY) - danger(x, y);
            if (score > bestScore) {
                bestScore = score;
                best = new double[] {x, y};
            }
        }
        if (best != null && mapDistance(bot.x, bot.y, best[0], best[1]) >= 0.3) actions.move(room, bot, best[0], best[1]);
    }

    /** How exposed a stop is: closer and stronger visible enemies count for more. */
    private double danger(double x, double y) {
        double danger = 0;
        for (Party enemy : visibleEnemies()) {
            double distance = mapDistance(x, y, enemy.x, enemy.y);
            if (distance < ENEMY_REACH) danger += (ENEMY_REACH - distance) * 0.6 * Battle.dice(enemy);
        }
        return danger;
    }

    /** Moves to the goal if affordable, otherwise as far along the straight line as movement allows. */
    private void moveToward(double x, double y) {
        if (mapDistance(bot.x, bot.y, x, y) < 0.3) return;
        double reach = 1;
        if (actions.cost(room, bot, x, y) > bot.movementRemaining + 0.0001) {
            double low = 0;
            double high = 1;
            for (int step = 0; step < 16; step++) {
                double t = (low + high) / 2;
                if (actions.cost(room, bot, lerp(bot.x, x, t), lerp(bot.y, y, t)) <= bot.movementRemaining + 0.0001) low = t;
                else high = t;
            }
            reach = low;
        }
        if (!hard) reach *= 0.6 + random.nextDouble() * 0.4;
        double toX = lerp(bot.x, x, reach);
        double toY = lerp(bot.y, y, reach);
        if (mapDistance(bot.x, bot.y, toX, toY) < 0.3) return;
        actions.move(room, bot, toX, toY);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** Distance in % of map width (the map is half as tall as wide). */
    static double mapDistance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, (y1 - y2) * 0.5);
    }
}
