package com.xigua.yknminions.item;

import org.bukkit.inventory.ItemStack;

/** Item operations used by online and world-independent minion production. */
public interface MinionItemAccess {
    ItemStack create(ItemSpec spec, int amount);

    default ItemStack create(ItemSpec spec) {
        return create(spec, 1);
    }

    boolean matches(ItemStack stack, ItemSpec spec);
}
