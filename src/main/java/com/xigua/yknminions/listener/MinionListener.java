package com.xigua.yknminions.listener;

import com.xigua.Main;
import com.xigua.yknminions.gui.MinionGui;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.model.MinionInstance;
import com.xigua.yknminions.service.MinionManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class MinionListener implements Listener {
    private final Main plugin;
    private final MinionManager manager;
    private final MinionGui gui;
    private final SpecialItemService specialItems;

    public MinionListener(Main plugin, MinionManager manager, MinionGui gui, SpecialItemService specialItems) {
        this.plugin = plugin;
        this.manager = manager;
        this.gui = gui;
        this.specialItems = specialItems;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinionInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ArmorStand stand)) return;
        MinionInstance minion = manager.byEntity(stand).orElse(null);
        if (minion == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!minion.owner().equals(player.getUniqueId()) && !player.hasPermission("yknminions.admin")) {
            player.sendMessage(plugin.pluginConfig().prefixed("§c你不是这个小人的主人。"));
            return;
        }
        gui.openMain(player, minion);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR)
                && specialItems.is(event.getItem(), SpecialItemService.SUPER_FUEL)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || event.getBlockFace() == null) return;
        ItemStack item = event.getItem();
        SpecialItemService.MinionItemData data = specialItems.minionData(item).orElse(null);
        if (data == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("yknminions.use")) {
            player.sendMessage(plugin.pluginConfig().message("no-permission"));
            return;
        }
        Location location = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0.0, 0.5);
        if (!manager.canPlace(location)) {
            player.sendMessage(plugin.pluginConfig().prefixed("§c这里没有足够空间放置小人，或附近已有小人。"));
            return;
        }
        if (plugin.pluginConfig().minionType(data.type()).isEmpty()) {
            player.sendMessage(plugin.pluginConfig().prefixed("§c未知的小人类型：" + data.type()));
            return;
        }
        MinionInstance created = manager.create(player, data.type(), data.level(), location);
        if (created == null) {
            player.sendMessage(plugin.pluginConfig().prefixed("§c小人创建失败，请查看服务器日志。"));
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE && item != null) {
            item.setAmount(item.getAmount() - 1);
            player.getInventory().setItemInMainHand(item.getAmount() <= 0 ? null : item);
        }
        player.sendMessage(plugin.pluginConfig().prefixed("§a已放置 " + data.type() + " 小人。"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        manager.accelerateFarmingGrowth(event.getBlock(), event.getNewState());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpecialItemPlace(BlockPlaceEvent event) {
        String id = specialItems.idOf(event.getItemInHand()).orElse(null);
        if (SpecialItemService.AUTO_CRAFT.equals(id) || SpecialItemService.INFINITE_ENERGY.equals(id)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof ArmorStand stand && manager.byEntity(stand).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMinionTargetDeath(EntityDeathEvent event) {
        LivingEntity target = event.getEntity();
        if (!target.getPersistentDataContainer().has(plugin.key("minion_target"), PersistentDataType.STRING)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.byTarget(target).ifPresent(minion -> minion.collectTargetKill(target));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMinionSlimeSplit(SlimeSplitEvent event) {
        if (event.getEntity().getPersistentDataContainer()
                .has(plugin.key("minion_target"), PersistentDataType.STRING)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (manager.byEntity(event.getRightClicked()).isPresent()) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        manager.onWorldLoad(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.onChunkUnload(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        manager.onWorldUnload(event.getWorld());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) manager.onPlayerJoin(player);
        });
    }
}
