package com.xigua.yknminions.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class InventoryUtil {
    private InventoryUtil() {}

    public static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;
        Map<Integer, ItemStack> remaining = player.getInventory().addItem(item);
        Location location = player.getLocation();
        remaining.values().forEach(stack -> player.getWorld().dropItemNaturally(location, stack));
    }
}
