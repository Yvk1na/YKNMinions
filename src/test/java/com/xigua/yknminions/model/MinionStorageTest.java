package com.xigua.yknminions.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinionStorageTest {
    @Test
    void clampsAndUpdatesUnlockedCapacity() {
        MinionStorage storage = new MinionStorage(15, 5, List.of());
        assertEquals(5, storage.unlockedSlots());
        storage.unlockedSlots(7);
        assertEquals(7, storage.unlockedSlots());
        storage.unlockedSlots(99);
        assertEquals(15, storage.unlockedSlots());
    }
}
