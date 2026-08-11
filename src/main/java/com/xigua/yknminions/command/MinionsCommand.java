package com.xigua.yknminions.command;

import com.xigua.Main;
import com.xigua.yknminions.config.MinionType;
import com.xigua.yknminions.gui.AdminGui;
import com.xigua.yknminions.util.InventoryUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MinionsCommand implements CommandExecutor, TabCompleter {
    private final Main plugin;
    private final AdminGui adminGui;

    public MinionsCommand(Main plugin, AdminGui adminGui) {
        this.plugin = plugin;
        this.adminGui = adminGui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "get" -> getSpecial(sender, args);
            case "give" -> giveMinion(sender, args);
            case "reload" -> reload(sender);
            case "level" -> levelEditor(sender, args);
            case "admin" -> adminBrowser(sender, args);
            default -> help(sender, label);
        }
        return true;
    }

    private void getSpecial(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yknminions.get")) { noPermission(sender); return; }
        if (args.length < 2) { sender.sendMessage(message("§c用法: /minions get <特殊物品ID> [玩家] [数量]")); return; }
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player player ? player : null;
        if (target == null) { sender.sendMessage(message("§c找不到目标玩家。")); return; }
        ItemStack item = plugin.specialItems().create(args[1]);
        if (item == null) { sender.sendMessage(message("§c未知物品 ID：" + args[1])); return; }
        int amount = args.length >= 4 ? parseInt(args[3], 1) : 1;
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        InventoryUtil.giveOrDrop(target, item);
        sender.sendMessage(message("§a已给予 " + target.getName() + "：" + args[1] + " × " + item.getAmount()));
    }

    private void giveMinion(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yknminions.give")) { noPermission(sender); return; }
        if (args.length < 2) { sender.sendMessage(message("§c用法: /minions give <类型> [等级] [玩家]")); return; }
        MinionType type = plugin.pluginConfig().minionType(args[1]).orElse(null);
        if (type == null) { sender.sendMessage(message("§c未知小人类型：" + args[1])); return; }
        int level = args.length >= 3 ? parseInt(args[2], 1) : 1;
        if (level < 1 || level > plugin.pluginConfig().maxLevel()) {
            sender.sendMessage(plugin.pluginConfig().message("invalid-level"));
            return;
        }
        Player target = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : sender instanceof Player player ? player : null;
        if (target == null) { sender.sendMessage(message("§c找不到目标玩家。")); return; }
        InventoryUtil.giveOrDrop(target, plugin.specialItems().createMinionItem(type.id(), level, type.displayName()));
        sender.sendMessage(message("§a已给予 " + target.getName() + " 一个 " + type.id() + " 小人（等级 " + level + "）。"));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("yknminions.reload")) { noPermission(sender); return; }
        plugin.reloadPlugin();
        sender.sendMessage(message("§aYknMinions 配置已重载。"));
    }

    private void levelEditor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yknminions.level")) { noPermission(sender); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.pluginConfig().message("player-only")); return; }
        if (args.length < 3) {
            sender.sendMessage(message("§c用法: /minions level <小人ID> <当前等级>"));
            return;
        }
        MinionType type = plugin.pluginConfig().minionType(args[1]).orElse(null);
        int level = parseInt(args[2], -1);
        if (type == null || level < 1 || level >= plugin.pluginConfig().maxLevel()) {
            sender.sendMessage(message("§c小人ID无效，或等级不在 1～" + (plugin.pluginConfig().maxLevel() - 1) + " 之间。"));
            return;
        }
        adminGui.openLevelEditor(player, type.id(), level);
    }

    private void adminBrowser(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yknminions.admin")) { noPermission(sender); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.pluginConfig().message("player-only")); return; }
        if (args.length < 2) {
            sender.sendMessage(message("§c用法: /minions admin <minions|special>"));
            return;
        }
        if (args[1].equalsIgnoreCase("minions")) adminGui.openMinionBrowser(player);
        else if (args[1].equalsIgnoreCase("special")) adminGui.openSpecialBrowser(player);
        else sender.sendMessage(message("§c未知管理界面，请使用 minions 或 special。"));
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(message("§6YknMinions 命令："));
        sender.sendMessage(message("§e/" + label + " get <id> [玩家] [数量] §7- 获得特殊物品"));
        sender.sendMessage(message("§e/" + label + " give <类型> [等级] [玩家] §7- 获得小人物品"));
        sender.sendMessage(message("§e/" + label + " reload §7- 重载配置"));
        if (sender.hasPermission("yknminions.level")) {
            sender.sendMessage(message("§e/" + label + " level <ID> <当前等级> §7- 编辑下一级材料"));
        }
        if (sender.hasPermission("yknminions.admin")) {
            sender.sendMessage(message("§e/" + label + " admin <minions|special> §7- 打开管理员物品 GUI"));
        }
    }

    private void noPermission(CommandSender sender) { sender.sendMessage(plugin.pluginConfig().message("no-permission")); }
    private String message(String message) { return plugin.pluginConfig().prefixed(message); }
    private int parseInt(String raw, int fallback) { try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return fallback; } }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        List<String> candidates = new ArrayList<>();
        if (args.length == 1) {
            candidates.addAll(List.of("get", "give", "reload"));
            if (sender.hasPermission("yknminions.level")) candidates.add("level");
            if (sender.hasPermission("yknminions.admin")) candidates.add("admin");
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("get")) candidates.addAll(plugin.specialItems().ids());
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) candidates.addAll(plugin.pluginConfig().minionTypes().keySet());
        else if (args.length == 2 && args[0].equalsIgnoreCase("level")) candidates.addAll(plugin.pluginConfig().minionTypes().keySet());
        else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) candidates.addAll(List.of("minions", "special"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("level")) {
            for (int level = 1; level < plugin.pluginConfig().maxLevel(); level++) candidates.add(String.valueOf(level));
        }
        else if ((args.length == 3 && args[0].equalsIgnoreCase("get"))
                || (args.length == 4 && args[0].equalsIgnoreCase("give"))) {
            Bukkit.getOnlinePlayers().forEach(player -> candidates.add(player.getName()));
        }
        String current = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(current)).sorted().toList();
    }
}
