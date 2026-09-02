package com.xigua.yknminions.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinionPositionTest {
    @Test
    void chunkIdentityUsesWorldNameWithoutBukkitWorldReference() {
        MinionPosition position = new MinionPosition("island_test_normal", -0.5, 80, 31.9, 90);

        assertEquals(-1, position.chunkX());
        assertEquals(1, position.chunkZ());
        assertTrue(position.isChunk("island_test_normal", -1, 1));
        assertFalse(position.isChunk("island_test_normal", 0, 1));
        assertFalse(position.isChunk("another_world", -1, 1));
    }

    @Test
    void rejectsBlankOrNonFinitePersistentCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinionPosition("", 0, 64, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MinionPosition("world", Double.NaN, 64, 0, 0));
    }
}
