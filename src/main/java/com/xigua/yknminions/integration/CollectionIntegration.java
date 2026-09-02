package com.xigua.yknminions.integration;

import java.util.UUID;

public interface CollectionIntegration {
    boolean available();

    boolean validCollection(String collectionId);

    ProgressResult add(UUID playerId, String collectionId, double amount);

    enum ProgressResult {
        APPLIED,
        DEFERRED_OFFLINE,
        SKIPPED,
        FAILED
    }
}
