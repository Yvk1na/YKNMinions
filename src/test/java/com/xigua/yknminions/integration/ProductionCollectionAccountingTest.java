package com.xigua.yknminions.integration;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductionCollectionAccountingTest {
    @Test
    void recordsOnlyTheAmountActuallyRetainedByStorage() {
        UUID owner = UUID.randomUUID();
        RecordingIntegration integration = new RecordingIntegration(true);
        CollectionProgressTracker tracker = new CollectionProgressTracker(
                owner, integration, Map.of());

        assertEquals(100, ProductionCollectionAccounting.record(
                tracker, "coal", 100, 0));
        assertEquals(4, ProductionCollectionAccounting.record(
                tracker, "coal", 100, 96));
        assertEquals(0, ProductionCollectionAccounting.record(
                tracker, "coal", 100, 100));

        assertEquals(List.of(new Call(owner, "coal", 100),
                new Call(owner, "coal", 4)), integration.calls);
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void offlineDeltaSurvivesReloadAndFlushesExactlyOnceToOwner() {
        UUID owner = UUID.randomUUID();
        RecordingIntegration offline = new RecordingIntegration(true,
                CollectionIntegration.ProgressResult.DEFERRED_OFFLINE);
        CollectionProgressTracker first = new CollectionProgressTracker(
                owner, offline, Map.of());
        ProductionCollectionAccounting.record(first, "coal", 1000, 0);
        assertEquals(Map.of("coal", 1000.0), first.snapshot());

        RecordingIntegration online = new RecordingIntegration(true,
                CollectionIntegration.ProgressResult.APPLIED);
        CollectionProgressTracker restored = new CollectionProgressTracker(
                owner, online, first.snapshot());

        assertTrue(restored.flush());
        assertFalse(restored.flush());
        assertEquals(List.of(new Call(owner, "coal", 1000)), online.calls);
        assertTrue(restored.snapshot().isEmpty());
    }

    @Test
    void missingOptionalPluginDoesNotAccumulateNewProgressOrBreakProduction() {
        CollectionProgressTracker tracker = new CollectionProgressTracker(
                UUID.randomUUID(), new DisabledCollectionIntegration(), Map.of());

        assertEquals(64, ProductionCollectionAccounting.record(
                tracker, "coal", 64, 0));
        assertTrue(tracker.snapshot().isEmpty());
        assertFalse(tracker.flush());
    }

    @Test
    void invalidProductionPartitionIsRejectedBeforeCallingIntegration() {
        RecordingIntegration integration = new RecordingIntegration(true);
        CollectionProgressTracker tracker = new CollectionProgressTracker(
                UUID.randomUUID(), integration, Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> ProductionCollectionAccounting.record(tracker, "coal", 4, 5));
        assertTrue(integration.calls.isEmpty());
    }

    private record Call(UUID owner, String collectionId, double amount) { }

    private static final class RecordingIntegration implements CollectionIntegration {
        private final boolean available;
        private final Queue<ProgressResult> results = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();

        private RecordingIntegration(boolean available, ProgressResult... results) {
            this.available = available;
            this.results.addAll(List.of(results));
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean validCollection(String collectionId) {
            return true;
        }

        @Override
        public ProgressResult add(UUID playerId, String collectionId, double amount) {
            calls.add(new Call(playerId, collectionId, amount));
            return results.isEmpty() ? ProgressResult.APPLIED : results.remove();
        }
    }
}
