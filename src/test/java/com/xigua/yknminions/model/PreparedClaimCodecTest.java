package com.xigua.yknminions.model;

import com.xigua.yknminions.TestItemStack;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PreparedClaimCodecTest {
    @Test
    void newClaimRoundTripsBlockedStateAndSkillSnapshotThroughYamlText() throws Exception {
        UUID minionId = UUID.randomUUID();
        PreparedClaim source = new PreparedClaim(UUID.randomUUID(), UUID.randomUUID(),
                "minion:test:claim:one", List.of(
                new PreparedClaimLine(new TestItemStack(Material.COAL, 32),
                        "auraskills", "mining", 0.5, 1),
                PreparedClaimLine.withoutSkill(new TestItemStack(Material.DIAMOND, 2))));
        YamlConfiguration yaml = new YamlConfiguration();
        PreparedClaimCodec.save(yaml, source, true);
        YamlConfiguration reloaded = new YamlConfiguration();
        ConfigurationSerialization.registerClass(TestItemStack.class);
        try {
            reloaded.loadFromString(yaml.saveToString());
        } finally {
            ConfigurationSerialization.unregisterClass(TestItemStack.class);
        }

        PreparedClaimCodec.LoadedClaim loaded = PreparedClaimCodec.load(reloaded, minionId)
                .orElseThrow();

        assertTrue(loaded.blocked());
        assertEquals(source.id(), loaded.claim().id());
        assertEquals(source.operationId(), loaded.claim().operationId());
        assertEquals(2, loaded.claim().lines().size());
        assertEquals("mining", loaded.claim().lines().getFirst().skillId());
        assertEquals(0.5, loaded.claim().lines().getFirst().xpPerBaseUnit(), 0.0001);
    }

    @Test
    void legacyClaimLoadsWithoutRetroactiveExperience() {
        UUID minionId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prepared-claim.state", "PREPARED");
        yaml.set("prepared-claim.id", claimId.toString());
        yaml.set("prepared-claim.recipient", UUID.randomUUID().toString());
        yaml.set("prepared-claim.items", List.of(new TestItemStack(Material.IRON_INGOT, 24)));

        PreparedClaimCodec.LoadedClaim loaded = PreparedClaimCodec.load(yaml, minionId)
                .orElseThrow();

        assertFalse(loaded.blocked());
        assertEquals("minion:" + minionId + ":claim:" + claimId,
                loaded.claim().operationId());
        PreparedClaimLine line = loaded.claim().lines().getFirst();
        assertTrue(line.skillProviderOptional().isEmpty());
        assertEquals(0, line.experience(24), 0.0001);
    }

    @Test
    void mismatchedCompatibilityItemsAreRejected() {
        UUID minionId = UUID.randomUUID();
        PreparedClaim source = new PreparedClaim(UUID.randomUUID(), UUID.randomUUID(),
                "minion:test:claim:two", List.of(
                PreparedClaimLine.withoutSkill(new TestItemStack(Material.COAL, 4))));
        YamlConfiguration yaml = new YamlConfiguration();
        PreparedClaimCodec.save(yaml, source, false);
        yaml.set("prepared-claim.items", List.of(new TestItemStack(Material.COAL, 5)));

        assertThrows(IllegalArgumentException.class,
                () -> PreparedClaimCodec.load(yaml, minionId));
    }
}
