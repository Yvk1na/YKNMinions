package com.xigua.yknminions.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** Backwards-compatible codec for world-independent production state. */
public final class OfflineStateCodec {
    private OfflineStateCodec() { }

    public static Loaded load(ConfigurationSection section) {
        List<Integer> pending = section.getIntegerList("pending-mob-sizes").stream()
                .filter(size -> size >= 0 && size <= 127).toList();
        long pendingKillAt = pending.isEmpty()
                ? 0L : Math.max(0L, section.getLong("pending-mob-kill-at", 0L));
        return new Loaded(section.getBoolean("offline-harvest-next", true),
                section.getBoolean("offline-workable", true), pending, pendingKillAt);
    }

    public static void save(ConfigurationSection section, boolean harvestNext,
                            boolean workable, List<Integer> pendingMobSizes,
                            long pendingMobKillAt) {
        section.set("offline-harvest-next", harvestNext);
        section.set("offline-workable", workable);
        section.set("pending-mob-sizes", pendingMobSizes.isEmpty() ? null : pendingMobSizes);
        section.set("pending-mob-kill-at", pendingMobSizes.isEmpty() ? null : pendingMobKillAt);
    }

    public record Loaded(boolean harvestNext, boolean workable,
                         List<Integer> pendingMobSizes, long pendingMobKillAt) {
        public Loaded {
            pendingMobSizes = List.copyOf(pendingMobSizes);
        }
    }
}
