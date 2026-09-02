package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationSettingsTest {
    @Test
    void normalizesPluginAndNamespacedIds() {
        SkillSettings skill = new SkillSettings(" AuraSkills ", " Custom:Mining ",
                List.of(new SkillRewardSettings(new ItemSpec("coal"), 0.5, 1)));
        CollectionSettings collection = new CollectionSettings(
                " EcoCollections ", " Custom:Coal ");

        assertEquals("auraskills", skill.provider());
        assertEquals("custom:mining", skill.skillId());
        assertEquals("ecocollections", collection.provider());
        assertEquals("custom:coal", collection.id());
    }

    @Test
    void rejectsInvalidIdsAndRates() {
        SkillRewardSettings validReward = new SkillRewardSettings(
                new ItemSpec("coal"), 0.5, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new SkillSettings("auraskills", "bad id", List.of(validReward)));
        assertThrows(IllegalArgumentException.class,
                () -> new CollectionSettings("ecocollections", "bad id"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillRewardSettings(new ItemSpec("coal"), -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillRewardSettings(new ItemSpec("coal"), 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillRewardSettings(new ItemSpec("coal"),
                        Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    void computesConfiguredResourceEquivalentUsingDoublePrecision() {
        SkillRewardSettings reward = new SkillRewardSettings(
                new ItemSpec("coal_block"), 0.5, 9);

        assertEquals(13.5, reward.experience(3), 0.0001);
        assertEquals(0, reward.experience(0), 0.0001);
    }
}
