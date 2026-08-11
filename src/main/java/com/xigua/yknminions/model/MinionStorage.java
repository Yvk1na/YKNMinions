package com.xigua.yknminions.model;

import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.ItemSpec;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class MinionStorage {
    private final int maxSlots;
    private int unlockedSlots;
    private final List<ItemStack> contents = new ArrayList<>();

    public MinionStorage(int maxSlots, int unlockedSlots, List<ItemStack> loaded) {
        this.maxSlots = Math.max(1, maxSlots);
        unlockedSlots(unlockedSlots);
        for (ItemStack item : loaded) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                contents.add(item.clone());
            }
        }
    }

    public int add(ItemStack source) {
        if (source == null || source.getType().isAir() || source.getAmount() <= 0) return 0;
        ItemStack item = source.clone();
        for (ItemStack current : contents) {
            if (!current.isSimilar(item)) continue;
            int move = Math.min(item.getAmount(), current.getMaxStackSize() - current.getAmount());
            if (move <= 0) continue;
            current.setAmount(current.getAmount() + move);
            item.setAmount(item.getAmount() - move);
            if (item.getAmount() == 0) return 0;
        }
        while (item.getAmount() > 0 && contents.size() < unlockedSlots) {
            int move = Math.min(item.getAmount(), item.getMaxStackSize());
            ItemStack split = item.clone();
            split.setAmount(move);
            contents.add(split);
            item.setAmount(item.getAmount() - move);
        }
        return item.getAmount();
    }

    public int count(ItemSpec spec, ItemResolver resolver) {
        return contents.stream().filter(item -> resolver.matches(item, spec)).mapToInt(ItemStack::getAmount).sum();
    }

    public boolean canFit(ItemStack source) {
        if (source == null || source.getType().isAir() || source.getAmount() <= 0) return true;
        int capacity = Math.max(0, unlockedSlots - contents.size()) * source.getMaxStackSize();
        for (ItemStack current : contents) {
            if (current.isSimilar(source)) capacity += current.getMaxStackSize() - current.getAmount();
        }
        return capacity >= source.getAmount();
    }

    public boolean remove(ItemSpec spec, int amount, ItemResolver resolver) {
        if (amount <= 0) return true;
        if (count(spec, resolver) < amount) return false;
        int remaining = amount;
        Iterator<ItemStack> iterator = contents.iterator();
        while (iterator.hasNext() && remaining > 0) {
            ItemStack current = iterator.next();
            if (!resolver.matches(current, spec)) continue;
            int take = Math.min(remaining, current.getAmount());
            current.setAmount(current.getAmount() - take);
            remaining -= take;
            if (current.getAmount() == 0) iterator.remove();
        }
        return remaining == 0;
    }

    public List<ItemStack> snapshot() {
        return contents.stream().map(ItemStack::clone).toList();
    }

    public List<ItemStack> takeAll() {
        List<ItemStack> result = snapshot();
        contents.clear();
        return result;
    }

    public boolean isFull() {
        if (contents.size() < unlockedSlots) return false;
        return contents.stream().allMatch(item -> item.getAmount() >= item.getMaxStackSize());
    }

    public int unlockedSlots() { return unlockedSlots; }

    public void unlockedSlots(int slots) {
        unlockedSlots = Math.max(1, Math.min(maxSlots, slots));
    }
}
