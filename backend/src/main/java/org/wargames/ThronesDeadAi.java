package org.wargames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.wargames.ThronesGame.MovePlan;
import org.wargames.ThronesGame.RuleException;
import org.wargames.ThronesState.Castle;
import org.wargames.ThronesState.House;
import org.wargames.ThronesState.Room;

/**
 * Plays the Dead when no one claims them. Before Winter they wait beyond the Wall. In Winter
 * they call the Long Night, march on the nearest castle or army they can reach by land (a castle
 * on the Wall until it is breached) and attack whatever stands there.
 */
final class ThronesDeadAi {
    private final ThronesGame game;
    private final ThronesMap map;

    ThronesDeadAi(ThronesGame game, ThronesMap map) {
        this.game = game;
        this.map = map;
    }

    void play(Room room, House dead) {
        if (!dead.alive) return;
        if (!game.winter(room)) {
            room.events.add("The Dead wait beyond the Wall.");
            return;
        }
        try {
            if (!dead.powerUsed) game.usePower(room, dead, null);
            if (attackHere(room, dead)) return;
            double[] target = target(room, dead);
            if (target == null) return;
            march(room, dead, target[0], target[1]);
            attackHere(room, dead);
        } catch (RuleException ignored) {
            // A move the rules refuse just ends the Dead's turn.
        }
    }

    private boolean attackHere(Room room, House dead) {
        List<House> enemies = game.enemiesAt(room, dead);
        if (!enemies.isEmpty()) {
            game.attack(room, dead, enemies.get(0).id);
            return true;
        }
        Castle castle = ThronesGame.castleAt(room, dead.x, dead.y);
        if (castle != null && !castle.owner.equals(dead.id)) {
            game.attack(room, dead, castle.id);
            return true;
        }
        return false;
    }

    /** The nearest castle or visible army the Dead can reach over land. */
    double[] target(Room room, House dead) {
        List<double[]> candidates = new ArrayList<>();
        for (Castle castle : room.castles) {
            if (castle.owner.equals(dead.id)) continue;
            if (!room.wallBreached && !ThronesRules.WALL_CASTLES.contains(castle.name)) continue;
            candidates.add(new double[] {castle.x, castle.y});
        }
        for (House house : room.houses) {
            if (!house.alive || house == dead || house.hidden) continue;
            if (!room.wallBreached && map.wallSide(house.x, house.y) >= 0) continue;
            candidates.add(new double[] {house.x, house.y});
        }
        return candidates.stream()
            .filter(point -> map.waterFraction(dead.x, dead.y, point[0], point[1]) == 0)
            .min(Comparator.comparingDouble(point -> ThronesMap.distance(dead.x, dead.y, point[0], point[1])))
            .orElse(null);
    }

    /** Moves as far along the straight line to the target as this turn's movement allows. */
    private void march(Room room, House dead, double x, double y) {
        MovePlan best = affordable(room, dead, x, y);
        if (best == null) {
            double low = 0;
            double high = 1;
            for (int step = 0; step < 18; step++) {
                double t = (low + high) / 2;
                MovePlan plan = affordable(room, dead, dead.x + (x - dead.x) * t, dead.y + (y - dead.y) * t);
                if (plan != null) {
                    best = plan;
                    low = t;
                } else {
                    high = t;
                }
            }
        }
        if (best != null && ThronesMap.distance(dead.x, dead.y, best.x(), best.y()) > 0.05) {
            game.move(room, dead, best.x(), best.y());
        }
    }

    private MovePlan affordable(Room room, House dead, double x, double y) {
        try {
            MovePlan plan = game.plan(room, dead, x, y);
            return plan.movementPoints() <= dead.movementRemaining + 0.0001 ? plan : null;
        } catch (RuleException refused) {
            return null;
        }
    }
}
