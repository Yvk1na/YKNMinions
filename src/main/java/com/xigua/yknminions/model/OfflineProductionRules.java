package com.xigua.yknminions.model;

import java.util.Deque;

/** Pure calculations shared by online scheduling and offline catch-up. */
public final class OfflineProductionRules {
    private OfflineProductionRules() { }

    public static long intervalMillis(double workIntervalSeconds, double speedMultiplier) {
        if (!Double.isFinite(workIntervalSeconds) || workIntervalSeconds <= 0.0
                || !Double.isFinite(speedMultiplier) || speedMultiplier <= 0.0) {
            throw new IllegalArgumentException("invalid work interval");
        }
        return Math.max(250L, Math.round(workIntervalSeconds * 1000.0 / speedMultiplier));
    }

    public static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    /** Mining and farming alternate preparation and harvesting in the long-run model. */
    public static boolean producesPrimary(boolean harvestNext) {
        return harvestNext;
    }

    public static boolean nextHarvestPhase(boolean harvestNext) {
        return !harvestNext;
    }

    public static void appendSplitChildren(Deque<Integer> pending, int killedSize,
                                           int childCount) {
        int childSize = killedSize / 2;
        if (childSize <= 0) return;
        if (childCount < 2 || childCount > 4) throw new IllegalArgumentException("childCount");
        for (int index = 0; index < childCount; index++) pending.addLast(childSize);
    }
}
