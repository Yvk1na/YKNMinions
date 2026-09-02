package com.xigua.yknminions.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinionSoundKeyTest {
    @Test
    void legacyEnumStyleSoundIsConvertedToRegistryKey() {
        assertEquals("minecraft:entity.slime.attack",
                MinionInstance.soundKey("ENTITY_SLIME_ATTACK").toString());
    }

    @Test
    void namespacedSoundIsPreserved() {
        assertEquals("custom:minion_work",
                MinionInstance.soundKey("custom:minion_work").toString());
    }

    @Test
    void blankSoundIsRejected() {
        assertNull(MinionInstance.soundKey("  "));
    }
}
