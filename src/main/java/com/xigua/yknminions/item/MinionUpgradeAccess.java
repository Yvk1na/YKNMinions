package com.xigua.yknminions.item;

import org.bukkit.inventory.ItemStack;

/** Identifies upgrade items without coupling production tests to Bukkit item metadata. */
public interface MinionUpgradeAccess {
    boolean is(ItemStack item, String id);
}
