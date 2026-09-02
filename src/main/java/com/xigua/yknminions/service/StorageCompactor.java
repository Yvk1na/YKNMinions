package com.xigua.yknminions.service;

import com.xigua.yknminions.model.MinionStorage;
import org.bukkit.inventory.ItemStack;

/** Storage-only compression boundary shared by online and offline production. */
public interface StorageCompactor {
    void compact(MinionStorage storage, ItemStack trigger);
}
