package com.xigua.yknminions.gui;

import com.xigua.Main;
import com.xigua.yknminions.config.MinionType;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class AdminGui implements Listener {
    private static final int INPUT_END = 45;
    private static final int SAVE_SLOT = 48;
    private static final int EXIT_SLOT = 50;
    private static final int PAGE_SIZE = 45;
    private static final int BROWSER_CLOSE_SLOT = 48;
    private static final int BROWSER_PAGE_SLOT = 50;
    private static final int LEVELS_BACK_SLOT = 48;
    private static final int LEVELS_CLOSE_SLOT = 49;
    private static final int LEVELS_NEXT_TYPE_SLOT = 50;

    private final Main plugin;
    private final ItemResolver resolver;
    private final SpecialItemService specialItems;

    public AdminGui(Main plugin, ItemResolver resolver, SpecialItemService specialItems) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.specialItems = specialItems;
    }

    public void openLevelEditor(Player player, String typeId, int currentLevel) {
        MinionType type = plugin.pluginConfig().minionType(typeId).orElse(null);
        if (type == null || currentLevel < 1 || currentLevel >= plugin.pluginConfig().maxLevel()) {
            player.sendMessage(plugin.pluginConfig().prefixed("§c小人类型或等级无效；等级必须在 1～"
                    + (plugin.pluginConfig().maxLevel() - 1) + " 之间。"));
            return;
        }
        LevelHolder holder = new LevelHolder(player.getUniqueId(), type.id(), currentLevel);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("升级材料 " + type.id() + " " + currentLevel + "→" + (currentLevel + 1)));
        holder.inventory = inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = INPUT_END; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(SAVE_SLOT, lore(Material.CHEST, "保存", NamedTextColor.GREEN,
                List.of("保存前 5 行中的全部材料", "相同物品会自动合并数量")));
        inventory.setItem(EXIT_SLOT, lore(Material.BARRIER, "退出", NamedTextColor.RED,
                List.of("不保存并退还放入的物品")));
        player.openInventory(inventory);
    }

    public void openMinionBrowser(Player player) {
        openMinionTypeBrowser(player, new ArrayList<>(plugin.pluginConfig().minionTypes().values()), 0);
    }

    public void openSpecialBrowser(Player player) {
        List<ItemStack> entries = specialItems.ids().stream().sorted()
                .map(specialItems::create).filter(item -> item != null).toList();
        openBrowser(player, BrowserType.SPECIAL, entries, 0);
    }

    private void openMinionTypeBrowser(Player player, List<MinionType> types, int requestedPage) {
        int pages = Math.max(1, (types.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        MinionTypesHolder holder = new MinionTypesHolder(types, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("小人类型 " + (page + 1) + "/" + pages + " MADE BY Yvk1na"));
        holder.inventory = inventory;
        int from = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && from + slot < types.size(); slot++) {
            inventory.setItem(slot, minionTypeIcon(types.get(from + slot)));
        }
        fillBrowserBottom(inventory);
        inventory.setItem(BROWSER_CLOSE_SLOT,
                lore(Material.BARRIER, "关闭", NamedTextColor.RED, List.of("关闭管理界面")));
        inventory.setItem(BROWSER_PAGE_SLOT, lore(Material.ARROW, "换页", NamedTextColor.YELLOW,
                List.of("左键：下一页", "右键：上一页", "当前第 " + (page + 1) + "/" + pages + " 页")));
        player.openInventory(inventory);
    }

    private void openMinionLevels(Player player, List<MinionType> types, int requestedTypeIndex) {
        if (types.isEmpty()) {
            openMinionTypeBrowser(player, types, 0);
            return;
        }
        int typeIndex = Math.floorMod(requestedTypeIndex, types.size());
        MinionType type = types.get(typeIndex);
        MinionLevelsHolder holder = new MinionLevelsHolder(types, typeIndex);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text(type.id() + " 小人等级"));
        holder.inventory = inventory;
        for (int level = 1; level <= plugin.pluginConfig().maxLevel() && level <= PAGE_SIZE; level++) {
            inventory.setItem(level - 1,
                    specialItems.createMinionItem(type.id(), level, type.displayName()));
        }
        fillBrowserBottom(inventory);
        inventory.setItem(LEVELS_BACK_SLOT,
                lore(Material.ARROW, "返回", NamedTextColor.YELLOW, List.of("返回小人类型列表")));
        inventory.setItem(LEVELS_CLOSE_SLOT,
                lore(Material.BARRIER, "关闭", NamedTextColor.RED, List.of("关闭管理界面")));
        MinionType next = types.get((typeIndex + 1) % types.size());
        inventory.setItem(LEVELS_NEXT_TYPE_SLOT,
                lore(Material.CHEST, "下一个小人", NamedTextColor.GREEN, List.of("点击查看：" + next.id())));
        player.openInventory(inventory);
    }

    private void openBrowser(Player player, BrowserType type, List<ItemStack> entries, int requestedPage) {
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        BrowserHolder holder = new BrowserHolder(type, entries, page);
        String title = "特殊物品 " + (page + 1) + "/" + pages + " MADE BY Yvk1na";
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(title));
        holder.inventory = inventory;
        int from = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && from + slot < entries.size(); slot++) {
            inventory.setItem(slot, entries.get(from + slot));
        }
        fillBrowserBottom(inventory);
        inventory.setItem(BROWSER_CLOSE_SLOT, lore(Material.BARRIER, "关闭", NamedTextColor.RED, List.of("关闭管理界面")));
        inventory.setItem(BROWSER_PAGE_SLOT, lore(Material.ARROW, "换页", NamedTextColor.YELLOW,
                List.of("左键：下一页", "右键：上一页", "当前第 " + (page + 1) + "/" + pages + " 页")));
        player.openInventory(inventory);
    }

    private void fillBrowserBottom(Inventory inventory) {
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = PAGE_SIZE; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private ItemStack minionTypeIcon(MinionType type) {
        ItemStack item = specialItems.createMinionItem(type.id(), 1, type.displayName());
        ItemMeta meta = item.getItemMeta();
        meta.itemName(LegacyComponentSerializer.legacySection().deserialize(type.displayName()));
        meta.lore(List.of(Component.text("左键查看全部等级", NamedTextColor.YELLOW)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof LevelHolder level) handleLevelClick(event, level);
        else if (holder instanceof MinionTypesHolder types) handleMinionTypesClick(event, types);
        else if (holder instanceof MinionLevelsHolder levels) handleMinionLevelsClick(event, levels);
        else if (holder instanceof BrowserHolder browser) handleBrowserClick(event, browser);
    }

    private void handleLevelClick(InventoryClickEvent event, LevelHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;
        if (rawSlot < INPUT_END) return;
        event.setCancelled(true);
        if (rawSlot == SAVE_SLOT) saveLevelMaterials(player, holder);
        else if (rawSlot == EXIT_SLOT) closeLevelEditor(player, holder);
    }

    private void handleBrowserClick(InventoryClickEvent event, BrowserHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < PAGE_SIZE && event.isLeftClick()) {
            int index = holder.page * PAGE_SIZE + rawSlot;
            if (index < holder.entries.size()) InventoryUtil.giveOrDrop(player, holder.entries.get(index).clone());
            return;
        }
        if (rawSlot == BROWSER_PAGE_SLOT) {
            if (event.isLeftClick() && (holder.page + 1) * PAGE_SIZE < holder.entries.size()) {
                openBrowser(player, holder.type, holder.entries, holder.page + 1);
            } else if (event.isRightClick() && holder.page > 0) {
                openBrowser(player, holder.type, holder.entries, holder.page - 1);
            }
        } else if (rawSlot == BROWSER_CLOSE_SLOT) player.closeInventory();
    }

    private void handleMinionTypesClick(InventoryClickEvent event, MinionTypesHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < PAGE_SIZE && event.isLeftClick()) {
            int index = holder.page * PAGE_SIZE + rawSlot;
            if (index < holder.types.size()) openMinionLevels(player, holder.types, index);
            return;
        }
        if (rawSlot == BROWSER_PAGE_SLOT) {
            if (event.isLeftClick() && (holder.page + 1) * PAGE_SIZE < holder.types.size()) {
                openMinionTypeBrowser(player, holder.types, holder.page + 1);
            } else if (event.isRightClick() && holder.page > 0) {
                openMinionTypeBrowser(player, holder.types, holder.page - 1);
            }
        } else if (rawSlot == BROWSER_CLOSE_SLOT) player.closeInventory();
    }

    private void handleMinionLevelsClick(InventoryClickEvent event, MinionLevelsHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < plugin.pluginConfig().maxLevel() && event.isLeftClick()) {
            MinionType type = holder.types.get(holder.typeIndex);
            InventoryUtil.giveOrDrop(player,
                    specialItems.createMinionItem(type.id(), rawSlot + 1, type.displayName()));
            return;
        }
        if (rawSlot == LEVELS_BACK_SLOT) {
            openMinionTypeBrowser(player, holder.types, holder.typeIndex / PAGE_SIZE);
        } else if (rawSlot == LEVELS_CLOSE_SLOT) {
            player.closeInventory();
        } else if (rawSlot == LEVELS_NEXT_TYPE_SLOT) {
            openMinionLevels(player, holder.types, holder.typeIndex + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof BrowserHolder || holder instanceof MinionTypesHolder
                || holder instanceof MinionLevelsHolder) {
            event.setCancelled(true);
            return;
        }
        if (holder instanceof LevelHolder && event.getRawSlots().stream()
                .anyMatch(slot -> slot >= INPUT_END && slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof LevelHolder holder) || holder.handled
                || !(event.getPlayer() instanceof Player player)) return;
        holder.handled = true;
        returnInputItems(player, event.getInventory());
    }

    private void saveLevelMaterials(Player player, LevelHolder holder) {
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        for (int slot = 0; slot < INPUT_END; slot++) {
            ItemStack item = holder.inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            String descriptor = resolver.canonicalKey(item).orElse(null);
            if (descriptor == null) {
                player.sendMessage(plugin.pluginConfig().prefixed("§c无法识别第 " + (slot + 1) + " 格中的物品。"));
                return;
            }
            totals.merge(descriptor, item.getAmount(), AdminGui::safeAdd);
        }

        File file = new File(plugin.getDataFolder(), "minions.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int targetLevel = holder.currentLevel + 1;
        String levelPath = "types." + holder.typeId + ".levels." + targetLevel;
        if (!yaml.isConfigurationSection(levelPath)) {
            MinionType type = plugin.pluginConfig().minionType(holder.typeId).orElse(null);
            double interval = type == null ? 10.0 : type.level(targetLevel).workIntervalSeconds();
            yaml.set(levelPath + ".work-interval-seconds", interval);
        }
        List<Map<String, Object>> requirements = totals.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("item", entry.getKey());
                    value.put("amount", entry.getValue());
                    return value;
                }).toList();
        yaml.set(levelPath + ".upgrade-materials", requirements);
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "保存小人升级材料失败", exception);
            player.sendMessage(plugin.pluginConfig().prefixed("§c保存失败，请查看服务器日志。"));
            return;
        }

        holder.handled = true;
        returnInputItems(player, holder.inventory);
        player.closeInventory();
        plugin.reloadPlugin();
        player.sendMessage(plugin.pluginConfig().prefixed("§a已保存 " + holder.typeId + " " + holder.currentLevel
                + "→" + targetLevel + " 的升级材料，共 " + totals.size() + " 种。"));
    }

    private void closeLevelEditor(Player player, LevelHolder holder) {
        holder.handled = true;
        returnInputItems(player, holder.inventory);
        player.closeInventory();
    }

    private void returnInputItems(Player player, Inventory inventory) {
        for (int slot = 0; slot < INPUT_END; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            inventory.clear(slot);
            InventoryUtil.giveOrDrop(player, item);
        }
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static ItemStack named(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text(name, color));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lore(Material material, String name, NamedTextColor color, List<String> lines) {
        ItemStack item = named(material, name, color);
        ItemMeta meta = item.getItemMeta();
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private enum BrowserType { SPECIAL }

    private static final class LevelHolder implements InventoryHolder {
        private final UUID playerId;
        private final String typeId;
        private final int currentLevel;
        private Inventory inventory;
        private boolean handled;

        private LevelHolder(UUID playerId, String typeId, int currentLevel) {
            this.playerId = playerId;
            this.typeId = typeId;
            this.currentLevel = currentLevel;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class MinionTypesHolder implements InventoryHolder {
        private final List<MinionType> types;
        private final int page;
        private Inventory inventory;

        private MinionTypesHolder(List<MinionType> types, int page) {
            this.types = List.copyOf(types);
            this.page = page;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class MinionLevelsHolder implements InventoryHolder {
        private final List<MinionType> types;
        private final int typeIndex;
        private Inventory inventory;

        private MinionLevelsHolder(List<MinionType> types, int typeIndex) {
            this.types = List.copyOf(types);
            this.typeIndex = typeIndex;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class BrowserHolder implements InventoryHolder {
        private final BrowserType type;
        private final List<ItemStack> entries;
        private final int page;
        private Inventory inventory;

        private BrowserHolder(BrowserType type, List<ItemStack> entries, int page) {
            this.type = type;
            this.entries = entries;
            this.page = page;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
