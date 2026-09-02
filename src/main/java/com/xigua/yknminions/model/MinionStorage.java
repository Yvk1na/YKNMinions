package com.xigua.yknminions.model;

import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.ItemSpec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class MinionStorage {
    private final int maxSlots;
    private int unlockedSlots;
    private final List<ItemStack> contents = new ArrayList<>();
    private int reservedSlots;

    public MinionStorage(int maxSlots, int unlockedSlots, List<ItemStack> loaded) {
        this.maxSlots = Math.max(1, maxSlots);
        unlockedSlots(unlockedSlots);
        for (ItemStack item : loaded) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                contents.add(item.clone());
            }
        }
    }

    public int add(ItemStack source) {
        if (source == null || source.getType() == Material.AIR || source.getAmount() <= 0) return 0;
        ItemStack item = source.clone();
        for (int index = reservedSlots; index < contents.size(); index++) {
            ItemStack current = contents.get(index);
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
        int count = 0;
        for (int index = reservedSlots; index < contents.size(); index++) {
            ItemStack item = contents.get(index);
            if (resolver.matches(item, spec)) count += item.getAmount();
        }
        return count;
    }

    public boolean canFit(ItemStack source) {
        if (source == null || source.getType() == Material.AIR || source.getAmount() <= 0) return true;
        int capacity = Math.max(0, unlockedSlots - contents.size()) * source.getMaxStackSize();
        for (int index = reservedSlots; index < contents.size(); index++) {
            ItemStack current = contents.get(index);
            if (current.isSimilar(source)) capacity += current.getMaxStackSize() - current.getAmount();
        }
        return capacity >= source.getAmount();
    }

    public boolean remove(ItemSpec spec, int amount, ItemResolver resolver) {
        if (amount <= 0) return true;
        if (count(spec, resolver) < amount) return false;
        int remaining = amount;
        Iterator<ItemStack> iterator = contents.listIterator(reservedSlots);
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
        if (reservedSlots > 0) return List.of();
        List<ItemStack> result = snapshot();
        contents.clear();
        return result;
    }

    public List<ItemStack> reserveAll() {
        if (reservedSlots > 0 || contents.isEmpty()) return List.of();
        reservedSlots = contents.size();
        return reservedSnapshot();
    }

    public boolean restoreReservation(List<ItemStack> expected) {
        if (reservedSlots > 0 || expected == null || expected.isEmpty() || expected.size() > contents.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            ItemStack actual = contents.get(index);
            ItemStack saved = expected.get(index);
            if (saved == null || actual.getAmount() != saved.getAmount() || !actual.isSimilar(saved)) return false;
        }
        reservedSlots = expected.size();
        return true;
    }

    public List<ItemStack> reservedSnapshot() {
        return contents.subList(0, reservedSlots).stream().map(ItemStack::clone).toList();
    }

    public boolean commitReservation() {
        if (reservedSlots <= 0) return false;
        contents.subList(0, reservedSlots).clear();
        reservedSlots = 0;
        return true;
    }

    /**
     * Removes only the amounts confirmed delivered by Skyblock-Core and makes
     * every undelivered remainder available to the minion again.
     */
    public boolean settleReservation(List<Long> deliveredPerLine) {
        return settleReservation(reservedSnapshot(), deliveredPerLine);
    }

    public boolean settleReservation(List<ItemStack> expected,
                                     List<Long> deliveredPerLine) {
        if (reservedSlots <= 0 || expected == null || deliveredPerLine == null
                || expected.size() != reservedSlots
                || deliveredPerLine.size() != reservedSlots) return false;
        for (int index = 0; index < reservedSlots; index++) {
            ItemStack actual = contents.get(index);
            ItemStack saved = expected.get(index);
            Long delivered = deliveredPerLine.get(index);
            if (saved == null || actual.getAmount() != saved.getAmount()
                    || !actual.isSimilar(saved) || delivered == null || delivered < 0
                    || delivered > actual.getAmount()) return false;
        }
        List<ItemStack> settled = new ArrayList<>(contents.size());
        for (int index = 0; index < reservedSlots; index++) {
            ItemStack reserved = contents.get(index);
            int remaining = reserved.getAmount() - Math.toIntExact(deliveredPerLine.get(index));
            if (remaining <= 0) continue;
            ItemStack remainder = reserved.clone();
            remainder.setAmount(remaining);
            settled.add(remainder);
        }
        for (int index = reservedSlots; index < contents.size(); index++) {
            settled.add(contents.get(index).clone());
        }
        contents.clear();
        contents.addAll(settled);
        reservedSlots = 0;
        return true;
    }

    public StateSnapshot stateSnapshot() {
        return new StateSnapshot(contents, reservedSlots);
    }

    public void restoreState(StateSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot");
        contents.clear();
        snapshot.contents().forEach(item -> contents.add(item.clone()));
        reservedSlots = snapshot.reservedSlots();
    }

    public boolean releaseReservation() {
        if (reservedSlots <= 0) return false;
        reservedSlots = 0;
        return true;
    }

    public boolean hasReservation() { return reservedSlots > 0; }
    public boolean isEmpty() { return contents.isEmpty(); }

    public boolean isFull() {
        if (contents.size() < unlockedSlots) return false;
        for (int index = reservedSlots; index < contents.size(); index++) {
            ItemStack item = contents.get(index);
            if (item.getAmount() < item.getMaxStackSize()) return false;
        }
        return true;
    }

    public int unlockedSlots() { return unlockedSlots; }

    public void unlockedSlots(int slots) {
        unlockedSlots = Math.max(1, Math.min(maxSlots, slots));
    }

    public record StateSnapshot(List<ItemStack> contents, int reservedSlots) {
        public StateSnapshot {
            contents = contents.stream().map(ItemStack::clone).toList();
            if (reservedSlots < 0 || reservedSlots > contents.size()) {
                throw new IllegalArgumentException("reservedSlots");
            }
        }

        @Override
        public List<ItemStack> contents() {
            return contents.stream().map(ItemStack::clone).toList();
        }
    }
}
