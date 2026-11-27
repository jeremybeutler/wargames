package org.wargames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wargames.ThronesGame.MovePlan;
import org.wargames.ThronesGame.RuleException;
import org.wargames.ThronesState.Castle;
import org.wargames.ThronesState.House;
import org.wargames.ThronesState.Room;

/**
 * Plays one Iron Throne turn for a bot house: raise levies at a castle it holds, fight what
 * stands on its square, march on the best target, fight again on arrival, then end the turn.
 * Bots only see armies a player could see. They never invite anyone, but accept an invitation to
 * join forces when it helps them (see {@link #acceptsInvitation}). The server plays the Dead's
 * armies through this class too, with {@link ThronesDeadAi}.
 *
 * <p>Hard bots value King's Landing and the great seats, judge each fight by simulating the
 * dice and only take good odds. Easy bots go for whatever is nearest and take bad fights.
 */
final class ThronesBot {
    private static final Logger LOG = LoggerFactory.getLogger(ThronesBot.class);
    static final String EASY = "easy";
    static final String HARD = "hard";

    private final ThronesGame game;
    private final ThronesMap map;
    private final Room room;
    private final House bot;
    private final Random random;
    private final boolean hard;

    private ThronesBot(ThronesGame game, ThronesMap map, Room room, House bot, Random random) {
        this.game = game;
        this.map = map;
        this.room = room;
        this.bot = bot;
        this.random = random;
        this.hard = HARD.equals(bot.bot);
    }

    static void playTurn(ThronesGame game, ThronesMap map, Room room, House bot, Random random) {
        if (ThronesRules.isDead(bot.id)) {
            new ThronesDeadAi(game, map).play(room, bot);
            game.afterAction(room, bot.id);
            if (room.winners.isEmpty() && "campaign".equals(room.status) && bot.alive && game.current(room) == bot) {
                game.endTurn(room, bot);
            }
            return;
        }
        new ThronesBot(game, map, room, bot, random).play();
    }

    /**
     * Whether a bot accepts {@code from}'s invitation to join forces: when it is outnumbered by
     * enemies near it, or when the Dead are on the march or close by. Easy bots also say yes on a
     * whim half the time.
     */
    static boolean acceptsInvitation(ThronesGame game, Room room, House bot, House from, Random random) {
        if (!bot.alive || !from.alive) return false;
        if (game.winter(room)) return true;
        boolean deadNear = room.houses.stream().anyMatch(other -> other.alive && ThronesRules.isDead(other.id)
            && ThronesMap.distance(other.x, other.y, bot.x, bot.y) < 20);
        if (deadNear) return true;
        int threat = room.houses.stream().filter(other -> other.alive && other != bot && other != from
                && !game.allied(room, bot.id, other.id) && !ThronesRules.isDead(other.id)
                && ThronesMap.distance(other.x, other.y, bot.x, bot.y) < 15)
            .mapToInt(other -> other.units).sum();
        if (threat > bot.units) return true;
        return EASY.equals(bot.bot) && random.nextBoolean();
    }

    private void play() {
        try {
            spend();
            if (attackHere()) {
                finish();
                return;
            }
            double[] goal = goal();
            if (goal != null && myTurn()) march(goal[0], goal[1]);
            if (myTurn()) spend();
            if (myTurn() && bot.attacksLeft > 0) attackHere();
        } catch (RuleException refused) {
            // A move the rules refuse just ends the bot's turn; the game must never stall.
            LOG.info("Bot {} stopped its turn early: {}", bot.name, refused.getMessage());
        }
        finish();
    }

    private boolean myTurn() {
        return room.winners.isEmpty() && "campaign".equals(room.status) && bot.alive && game.current(room) == bot;
    }

    private void finish() {
        if (myTurn()) game.endTurn(room, bot);
    }

    private void act(Runnable action) {
        action.run();
        game.afterAction(room, bot.id);
    }

    // ------------------------------------------------------------------ gold

    private void spend() {
        if (!myTurn()) return;
        Castle here = ThronesGame.castleAt(room, bot.x, bot.y);
        if (here == null) return;
        if (!here.owner.equals(bot.id) || ThronesRules.isDead(bot.id)) return;
        int cost = game.recruitCost(room, bot);
        int want = ThronesRules.MAX_RECRUITS_PER_TURN - bot.recruitedThisTurn;
        if (!hard) want = random.nextBoolean() ? Math.min(1, want) : 0;
        int afford = Math.min(want, bot.gold / cost);
        if (afford > 0) tryAct(() -> game.recruit(room, bot, afford));
    }

    private void tryAct(Runnable action) {
        try {
            act(action);
        } catch (RuleException refused) {
            // Not this turn.
        }
    }

    // ------------------------------------------------------------------ fighting

    private boolean attackHere() {
        if (!myTurn() || bot.attacksLeft <= 0) return false;
        List<House> enemies = game.enemiesAt(room, bot).stream()
            .filter(enemy -> !game.allied(room, bot.id, enemy.id)).toList();
        if (!enemies.isEmpty()) {
            House target = enemies.get(0);
            if (odds(target) < threshold()) return false;
            act(() -> game.attack(room, bot, target.id));
            return true;
        }
        Castle castle = ThronesGame.castleAt(room, bot.x, bot.y);
        if (castle == null || castle.owner.equals(bot.id) || game.allied(room, bot.id, castle.owner)) return false;
        if (ThronesRules.isDead(bot.id) && !game.winter(room)) return false;
        double chance = odds(castle);
        if (chance < threshold() && !(castle.units == 0 && chance > 0.3)) return false;
        act(() -> game.attack(room, bot, castle.id));
        return true;
    }

    private double threshold() {
        return hard ? 0.55 : 0.2 + random.nextDouble() * 0.25;
    }

    private double odds(House enemy) {
        return Battle.winChance(game.dice(room, bot), 0, game.dice(room, enemy), 0, random);
    }

    private double odds(Castle castle) {
        return Battle.winChance(game.dice(room, bot), 0, Math.max(1, castle.units), 0, random);
    }

    // ------------------------------------------------------------------ where to go

    private record Goal(double x, double y, double value, double odds) {}

    private double[] goal() {
        List<Goal> goals = new ArrayList<>();
        boolean canCrossWater = !bot.dragons.isEmpty() || "greyjoy".equals(bot.id);
        for (Castle castle : room.castles) {
            if (castle.owner.equals(bot.id) || game.allied(room, bot.id, castle.owner)) continue;
            if (!canCrossWater && map.waterFraction(bot.x, bot.y, castle.x, castle.y) > 0) continue;
            double value = 1;
            if (castle.great) value = 2.5;
            if (ThronesRules.KINGS_LANDING.equals(castle.name)) value = 4;
            if (ThronesRules.NEUTRAL.equals(castle.owner)) value *= 0.9;
            goals.add(new Goal(castle.x, castle.y, value, odds(castle)));
        }
        for (House enemy : room.houses) {
            if (enemy == bot || !enemy.alive || enemy.hidden || game.allied(room, bot.id, enemy.id)) continue;
            if (!canCrossWater && map.waterFraction(bot.x, bot.y, enemy.x, enemy.y) > 0) continue;
            goals.add(new Goal(enemy.x, enemy.y, ThronesRules.isDead(enemy.id) ? 1.4 : 1.2, odds(enemy)));
        }
        Goal best;
        if (hard) {
            best = goals.stream()
                .filter(goal -> goal.odds() >= 0.5)
                .max(Comparator.comparingDouble(goal -> goal.value() * goal.odds()
                    / (1 + ThronesMap.distance(bot.x, bot.y, goal.x(), goal.y()) / 10)))
                .orElse(null);
            if (best == null && bot.units < 3) return seat();
        } else {
            best = goals.stream()
                .min(Comparator.comparingDouble(goal -> ThronesMap.distance(bot.x, bot.y, goal.x(), goal.y())
                    * (0.7 + random.nextDouble() * 0.6)))
                .orElse(null);
        }
        return best == null ? null : new double[] {best.x(), best.y()};
    }

    /** Home to the seat, to raise levies there. */
    private double[] seat() {
        Castle home = room.castles.stream().filter(castle -> castle.name.equals(bot.seat) && castle.owner.equals(bot.id))
            .findFirst().orElse(null);
        return home == null ? null : new double[] {home.x, home.y};
    }

    /** Moves as far along the straight line to the target as this turn's movement allows. */
    private void march(double x, double y) {
        if (bot.moved || bot.attacked || ThronesMap.distance(bot.x, bot.y, x, y) < 0.3) return;
        MovePlan best = affordable(x, y);
        if (best == null) {
            double low = 0;
            double high = 1;
            for (int step = 0; step < 16; step++) {
                double t = (low + high) / 2;
                MovePlan plan = affordable(bot.x + (x - bot.x) * t, bot.y + (y - bot.y) * t);
                if (plan != null) {
                    best = plan;
                    low = t;
                } else {
                    high = t;
                }
            }
        }
        if (best == null || ThronesMap.distance(bot.x, bot.y, best.x(), best.y()) < 0.3) return;
        double toX = best.x();
        double toY = best.y();
        act(() -> game.move(room, bot, toX, toY));
    }

    private MovePlan affordable(double x, double y) {
        try {
            MovePlan plan = game.plan(room, bot, x, y);
            return plan.movementPoints() <= bot.movementRemaining + 0.0001 ? plan : null;
        } catch (RuleException refused) {
            return null;
        }
    }
}
