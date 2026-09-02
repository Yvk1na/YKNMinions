package com.xigua.yknminions.integration;

import java.util.UUID;

public final class DisabledCollectionIntegration implements CollectionIntegration {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean validCollection(String collectionId) {
        return false;
    }

    @Override
    public ProgressResult add(UUID playerId, String collectionId, double amount) {
        return ProgressResult.SKIPPED;
    }
}
