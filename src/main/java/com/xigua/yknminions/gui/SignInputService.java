package com.xigua.yknminions.gui;

import com.xigua.Main;
import com.xigua.yknminions.model.MinionInstance;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SignInputService implements Listener {
    private final Main plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private MinionGui gui;

    public SignInputService(Main plugin) {
        this.plugin = plugin;
    }

    public void setGui(MinionGui gui) {
        this.gui = gui;
    }

    public void open(Player player, MinionInstance minion) {
        close(player);
        Location location = player.getLocation().getBlock().getLocation().add(0, 2, 0);
        BlockData signData = Material.OAK_SIGN.createBlockData();
        Sign sign = (Sign) signData.createBlockState();
        sign.getSide(Side.FRONT).line(0, Component.text(Math.min(plugin.pluginConfig().maxLevel(), minion.level() + 1)));
        sign.getSide(Side.FRONT).line(1, Component.text("^^^^^^^^^^^^^^^"));
        sign.getSide(Side.FRONT).line(2, Component.text("输入目标等级"));
        sign.getSide(Side.FRONT).line(3, Component.text("最高 11 级"));
        player.sendBlockChange(location, signData);
        player.sendBlockUpdate(location, (TileState) sign);
        sessions.put(player.getUniqueId(), new Session(minion.id(), location));
        player.openVirtualSign(Position.block(location.getBlockX(), location.getBlockY(), location.getBlockZ()), Side.FRONT);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Session current = sessions.get(player.getUniqueId());
            if (current != null && current.minionId.equals(minion.id())) close(player);
        }, 20L * 30L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVirtualSign(UncheckedSignChangeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        BlockPosition position = event.getEditedBlockPosition();
        if (position.blockX() != session.location.getBlockX() || position.blockY() != session.location.getBlockY()
                || position.blockZ() != session.location.getBlockZ()) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String raw = event.lines().stream().map(PlainTextComponentSerializer.plainText()::serialize)
                .filter(line -> !line.isBlank()).findFirst().orElse("").trim();
        close(player);
        int target;
        try { target = Integer.parseInt(raw); }
        catch (NumberFormatException ignored) {
            player.sendMessage(plugin.pluginConfig().message("invalid-level"));
            plugin.minionManager().byId(session.minionId).ifPresent(minion -> gui.openMain(player, minion));
            return;
        }
        int finalTarget = target;
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.minionManager().byId(session.minionId)
                .ifPresent(minion -> gui.openConfirmation(player, minion, finalTarget)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer());
    }

    private void close(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null || !player.isOnline() || session.location.getWorld() == null
                || !session.location.getWorld().equals(player.getWorld())) return;
        player.sendBlockChange(session.location, session.location.getBlock().getBlockData());
    }

    public void closeAll() {
        for (UUID uuid : sessions.keySet().toArray(UUID[]::new)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) close(player);
            else sessions.remove(uuid);
        }
    }

    private record Session(UUID minionId, Location location) {}
}
