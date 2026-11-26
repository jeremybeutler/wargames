package org.wargames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Battle dice. Each side, party or stronghold, rolls one die per army unit (a leader or bare
 * walls with no units left still roll one). The higher total wins the battle, and ties go to
 * the defender. The loser loses exactly one unit, or falls (a stronghold is captured) once its
 * units are gone. A party attacks at most once per turn.
 */
final class Battle {
    private Battle() {}

    record Result(List<Integer> attackerDice, List<Integer> defenderDice,
                  int attackerTotal, int defenderTotal) {
        boolean attackerWon() { return attackerTotal > defenderTotal; }
        int attackerLosses() { return attackerWon() ? 0 : 1; }
        int defenderLosses() { return attackerWon() ? 1 : 0; }
    }

    /** One die per unit; a leader standing alone still rolls one. */
    static int dice(GameSessionController.Party party) {
        return party.alive ? Math.max(1, party.units) : 0;
    }

    /** One die per garrison unit; empty walls still roll one. */
    static int dice(GameSessionController.Objective stronghold) {
        return Math.max(1, stronghold.units);
    }

    /**
     * Rolls a battle. Modifiers are added to every die of that side (e.g. an attack bonus,
     * or -1 for defending on volcanic ground). {@code d6} returns a die face from 1 to 6.
     */
    static Result roll(int attackDice, int attackModifier, int defenseDice, int defenseModifier,
                       IntSupplier d6) {
        List<Integer> attacker = rollDice(attackDice, attackModifier, d6);
        List<Integer> defender = rollDice(defenseDice, defenseModifier, d6);
        return new Result(attacker, defender, total(attacker), total(defender));
    }

    private static int total(List<Integer> dice) {
        return dice.stream().mapToInt(Integer::intValue).sum();
    }

    private static List<Integer> rollDice(int count, int modifier, IntSupplier d6) {
        List<Integer> dice = new ArrayList<>();
        for (int index = 0; index < count; index++) dice.add(d6.getAsInt() + modifier);
        dice.sort(Comparator.reverseOrder());
        return dice;
    }
}
