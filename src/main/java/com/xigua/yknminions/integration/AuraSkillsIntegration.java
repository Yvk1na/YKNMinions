package com.xigua.yknminions.integration;

import com.xigua.Main;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.user.SkillsUser;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class AuraSkillsIntegration implements SkillXpIntegration {
    private final Main plugin;
    private final Set<String> warnedSkills = new HashSet<>();

    public AuraSkillsIntegration(Main plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean available() {
        return plugin.getServer().getPluginManager().isPluginEnabled("AuraSkills");
    }

    @Override
    public boolean validSkill(String skillId) {
        if (!available()) return false;
        try {
            Skill skill = resolveSkill(skillId);
            if (skill == null || !skill.isEnabled()) {
                warnSkill(skillId, "AuraSkills Skill 不存在或未启用");
                return false;
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            warnSkill(skillId, "AuraSkills Skill ID 无法解析");
            return false;
        }
    }

    @Override
    public AwardResult award(UUID playerId, String skillId, double experience,
                             String operationId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!available() || experience <= 0) {
            return AwardResult.SKIPPED;
        }
        if (!Double.isFinite(experience)) {
            plugin.getLogger().warning("拒绝非有限 AuraSkills 经验：" + operationId);
            return AwardResult.FAILED;
        }
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            Skill skill = resolveSkill(skillId);
            if (skill == null || !skill.isEnabled()) {
                warnSkill(skillId, "AuraSkills Skill 不存在或未启用");
                return AwardResult.SKIPPED;
            }
            SkillsUser user = api.getUser(playerId);
            if (user == null || !user.isLoaded()) {
                plugin.getLogger().warning("AuraSkills 玩家数据未加载，未发放经验："
                        + operationId + " player=" + playerId + " skill=" + skillId
                        + " xp=" + experience);
                return AwardResult.FAILED;
            }
            user.addSkillXp(skill, experience);
            plugin.getLogger().info("Minion Skill XP 已结算：" + operationId
                    + " player=" + playerId + " skill=" + skillId
                    + " xp=" + experience);
            return AwardResult.APPLIED;
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING,
                    "AuraSkills 经验发放失败：" + operationId, failure);
            return AwardResult.FAILED;
        }
    }

    private Skill resolveSkill(String skillId) {
        AuraSkillsApi api = AuraSkillsApi.get();
        NamespacedId id = skillId.contains(":")
                ? NamespacedId.fromString(skillId)
                : NamespacedId.fromDefault(skillId);
        return api.getGlobalRegistry().getSkill(id);
    }

    private void warnSkill(String skillId, String message) {
        if (warnedSkills.add(skillId)) {
            plugin.getLogger().warning(message + "：" + skillId);
        }
    }
}
