package com.xigua.yknminions.integration;

import com.exanthiax.ecocollections.api.CollectionAPI;
import com.exanthiax.ecocollections.collections.Collection;
import com.exanthiax.ecocollections.collections.Collections;
import com.xigua.Main;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class EcoCollectionsIntegration implements CollectionIntegration {
    private final Main plugin;
    private final Set<String> warnedCollections = new HashSet<>();

    public EcoCollectionsIntegration(Main plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean available() {
        return plugin.getServer().getPluginManager().isPluginEnabled("EcoCollections");
    }

    @Override
    public boolean validCollection(String collectionId) {
        if (!available()) return false;
        try {
            if (Collections.INSTANCE.getByID(collectionId) != null) return true;
            if (warnedCollections.add(collectionId)) {
                plugin.getLogger().warning("EcoCollections Collection 不存在，已跳过："
                        + collectionId);
            }
            return false;
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING,
                    "EcoCollections Collection ID 校验失败：" + collectionId, failure);
            return false;
        }
    }

    @Override
    public ProgressResult add(UUID playerId, String collectionId, double amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (!available() || amount <= 0) {
            return ProgressResult.SKIPPED;
        }
        if (!Double.isFinite(amount)) {
            plugin.getLogger().warning("拒绝非有限 EcoCollections 进度：" + collectionId);
            return ProgressResult.FAILED;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return ProgressResult.DEFERRED_OFFLINE;
        }
        try {
            Collection collection = Collections.INSTANCE.getByID(collectionId);
            if (collection == null) {
                if (warnedCollections.add(collectionId)) {
                    plugin.getLogger().warning(
                            "EcoCollections Collection 不存在，已跳过：" + collectionId);
                }
                return ProgressResult.SKIPPED;
            }
            CollectionAPI.giveCollectionCount(player, collection, amount);
            return ProgressResult.APPLIED;
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING,
                    "EcoCollections 进度增加失败：" + collectionId
                            + " player=" + playerId + " amount=" + amount,
                    failure);
            return ProgressResult.FAILED;
        }
    }
}
