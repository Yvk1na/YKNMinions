package com.xigua.yknminions.integration;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisabledIntegrationsTest {
    @Test
    void disabledSkillAdapterIsACompleteNoOp() {
        DisabledSkillXpIntegration integration = new DisabledSkillXpIntegration();

        assertFalse(integration.available());
        assertFalse(integration.validSkill("mining"));
        assertEquals(SkillXpIntegration.AwardResult.SKIPPED,
                integration.award(UUID.randomUUID(), "mining", 64, "claim:test"));
    }

    @Test
    void disabledCollectionAdapterIsACompleteNoOp() {
        DisabledCollectionIntegration integration = new DisabledCollectionIntegration();

        assertFalse(integration.available());
        assertFalse(integration.validCollection("coal"));
        assertEquals(CollectionIntegration.ProgressResult.SKIPPED,
                integration.add(UUID.randomUUID(), "coal", 64));
    }
}
