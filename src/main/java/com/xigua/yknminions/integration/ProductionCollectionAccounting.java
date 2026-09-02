package com.xigua.yknminions.integration;

/** Shared accounting boundary for the primary output of one minion work cycle. */
public final class ProductionCollectionAccounting {
    private ProductionCollectionAccounting() { }

    public static int record(CollectionProgressTracker tracker, String collectionId,
                             int producedAmount, int finalLeftover) {
        if (producedAmount < 0 || finalLeftover < 0 || finalLeftover > producedAmount) {
            throw new IllegalArgumentException("invalid minion production partition");
        }
        int storedAmount = producedAmount - finalLeftover;
        if (tracker != null && collectionId != null && storedAmount > 0) {
            tracker.record(collectionId, storedAmount);
        }
        return storedAmount;
    }
}
