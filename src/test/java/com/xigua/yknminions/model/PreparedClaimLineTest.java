package com.xigua.yknminions.model;

import com.xigua.yknminions.TestItemStack;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreparedClaimLineTest {
    @Test
    void experienceUsesDeliveredAmountAndConfiguredEquivalence() {
        PreparedClaimLine line = new PreparedClaimLine(
                new TestItemStack(Material.ENCHANTED_BOOK, 8),
                "auraskills", "mining", 0.5, 160);

        assertEquals(240.0, line.experience(3), 0.0001);
        assertEquals(0.0, line.experience(0), 0.0001);
    }

    @Test
    void itemIsDefensivelyCopiedAndDeliveryCannotExceedLine() {
        ItemStack original = new TestItemStack(Material.COAL, 16);
        PreparedClaimLine line = PreparedClaimLine.withoutSkill(original);
        original.setAmount(1);
        ItemStack returned = line.item();
        returned.setAmount(2);

        assertEquals(16, line.item().getAmount());
        assertThrows(IllegalArgumentException.class, () -> line.experience(17));
    }

    @Test
    void incompleteSkillSnapshotIsRejected() {
        ItemStack item = new TestItemStack(Material.COAL, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedClaimLine(item, "auraskills", null, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedClaimLine(item, null, null, 1, 1));
    }
}
