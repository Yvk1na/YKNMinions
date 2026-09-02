package com.xigua.yknminions.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Keeps owner-scoped collection deltas until EcoCollections can accept them. */
public final class CollectionProgressTracker {
    private final UUID ownerId;
    private final CollectionIntegration integration;
    private final Map<String, Double> pending = new LinkedHashMap<>();

    public CollectionProgressTracker(UUID ownerId, CollectionIntegration integration,
                                     Map<String, Double> loadedPending) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.integration = Objects.requireNonNull(integration, "integration");
        if (loadedPending != null) {
            loadedPending.forEach((collectionId, amount) -> {
                if (collectionId != null && !collectionId.isBlank() && amount != null
                        && Double.isFinite(amount) && amount > 0) {
                    pending.put(collectionId, amount);
                }
            });
        }
    }

    public void record(String collectionId, double amount) {
        if (collectionId == null || collectionId.isBlank()
                || !Double.isFinite(amount) || amount <= 0) return;
        CollectionIntegration.ProgressResult result =
                integration.add(ownerId, collectionId, amount);
        if (result == CollectionIntegration.ProgressResult.DEFERRED_OFFLINE
                || result == CollectionIntegration.ProgressResult.FAILED) {
            pending.merge(collectionId, amount, CollectionProgressTracker::finiteSum);
        }
    }

    /** Records production without calling an external plugin before local state is durable. */
    public void recordDeferred(String collectionId, double amount) {
        if (collectionId == null || collectionId.isBlank()
                || !Double.isFinite(amount) || amount <= 0) return;
        pending.merge(collectionId, amount, CollectionProgressTracker::finiteSum);
    }

    public boolean flush() {
        if (pending.isEmpty() || !integration.available()) return false;
        boolean changed = false;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Double> entry = iterator.next();
            CollectionIntegration.ProgressResult result = integration.add(
                    ownerId, entry.getKey(), entry.getValue());
            if (result == CollectionIntegration.ProgressResult.APPLIED
                    || result == CollectionIntegration.ProgressResult.SKIPPED) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    public Map<String, Double> snapshot() {
        return Map.copyOf(pending);
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    private static double finiteSum(double left, double right) {
        double result = left + right;
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("pending collection progress overflow");
        }
        return result;
    }
}
