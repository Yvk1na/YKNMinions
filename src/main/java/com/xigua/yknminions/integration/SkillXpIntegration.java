package com.xigua.yknminions.integration;

import java.util.UUID;

public interface SkillXpIntegration {
    boolean available();

    boolean validSkill(String skillId);

    AwardResult award(UUID playerId, String skillId, double experience,
                      String operationId);

    enum AwardResult {
        APPLIED,
        SKIPPED,
        FAILED
    }
}
