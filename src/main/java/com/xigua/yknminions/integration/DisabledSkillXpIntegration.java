package com.xigua.yknminions.integration;

import java.util.UUID;

public final class DisabledSkillXpIntegration implements SkillXpIntegration {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean validSkill(String skillId) {
        return false;
    }

    @Override
    public AwardResult award(UUID playerId, String skillId, double experience,
                             String operationId) {
        return AwardResult.SKIPPED;
    }
}
