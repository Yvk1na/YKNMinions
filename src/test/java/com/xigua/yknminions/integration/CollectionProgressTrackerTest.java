package com.xigua.yknminions.integration;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CollectionProgressTrackerTest {
    @Test
    void offlineProgressStaysLocalUntilExplicitFlush() {
        AtomicInteger calls = new AtomicInteger();
        CollectionIntegration integration = new CollectionIntegration() {
            @Override public boolean available() { return true; }
            @Override public boolean validCollection(String collectionId) { return true; }
            @Override public ProgressResult add(UUID playerId, String collectionId, double amount) {
                calls.incrementAndGet();
                return ProgressResult.APPLIED;
            }
        };
        CollectionProgressTracker tracker = new CollectionProgressTracker(
                UUID.randomUUID(), integration, Map.of());

        tracker.recordDeferred("coal", 64);
        assertEquals(0, calls.get());
        assertEquals(64.0, tracker.snapshot().get("coal"));

        assertTrue(tracker.flush());
        assertEquals(1, calls.get());
        assertTrue(tracker.snapshot().isEmpty());
    }
}
