package com.xigua.yknminions.gui;

import com.xigua.Main;
import com.xigua.yknminions.config.PluginConfig;
import com.xigua.yknminions.config.Requirement;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.model.MinionInstance;
import com.xigua.yknminions.service.MinionManager;
import com.xigua.yknminions.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class MinionGui implements Listener {
    public static final int FUEL_SLOT = 19;
    public static final int UPGRADE_ONE_SLOT = 28;
    public static final int UPGRADE_TWO_SLOT = 37;
    private static final int LEVEL_SLOT = 4;
    private static final int UPGRADE_SLOT = 5;
    private static final int COLLECT_SLOT = 48;
    private static final int QUICK_UPGRADE_SLOT = 50;
    private static final int PICKUP_SLOT = 53;
    private static final int[] STORAGE_SLOTS = {21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43};

    private final Main plugin;
    private final MinionManager manager;
    private final PluginConfig config;
    private final ItemResolver resolver;
    private final SpecialItemService specialItems;
    private final SignInputService signInput;
    private BukkitTask refreshTask;

    public MinionGui(Main plugin, MinionManager manager, PluginConfig config, ItemResolver resolver,
                     SpecialItemService specialItems, SignInputService signInput) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.resolver = resolver;
        this.specialItems = specialItems;
        this.signInput = signInput;
    }

    public void startRefreshTask() {
        stopRefreshTask();
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMainInventories, 1L, 1L);
    }

    public void stopRefreshTask() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
    }

    private void refreshOpenMainInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (!(inventory.getHolder() instanceof MainHolder holder)) continue;
            MinionInstance minion = manager.byId(holder.minionId).orElse(null);
            if (minion == null) {
                player.closeInventory();
                continue;
            }
            renderStorage(inventory, minion);
        }
    }

    public void openMain(Player player, MinionInstance minion) {
        MainHolder holder = new MainHolder(minion.id());
        String title = minion.type().map(type -> stripColors(type.displayName())).orElse("Minion") + " " + roman(minion.level());
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(title));
        holder.inventory = inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        renderStorage(inventory, minion);

        ItemStack head = named(Material.PLAYER_HEAD, Component.text("小人等级 " + minion.level(), NamedTextColor.GREEN));
        head.setAmount(Math.max(1, minion.level()));
        inventory.setItem(LEVEL_SLOT, head);
        inventory.setItem(UPGRADE_SLOT, upgradeButton(minion));
        inventory.setItem(COLLECT_SLOT, lore(Material.CHEST, "收取资源", NamedTextColor.GOLD,
                List.of("左键点击，将全部收获放入背包。")));
        inventory.setItem(QUICK_UPGRADE_SLOT, lore(Material.DIAMOND, "快速升级", NamedTextColor.AQUA,
                List.of("点击后在告示牌中输入目标等级。")));
        inventory.setItem(PICKUP_SLOT, lore(Material.BEDROCK, "收起小人", NamedTextColor.RED,
                List.of("收取资源并将小人变回物品。")));

        renderInteractiveSlot(inventory, FUEL_SLOT, minion.fuel(), Material.LIME_STAINED_GLASS_PANE,
                "燃料栏", "仅可放置无尽能源、小型燃料、中型燃料或超级燃料");
        renderInteractiveSlot(inventory, UPGRADE_ONE_SLOT, minion.upgradeOne(), Material.ORANGE_STAINED_GLASS_PANE,
                "升级模块 1", "可放置资源蔓延或自动合成");
        renderInteractiveSlot(inventory, UPGRADE_TWO_SLOT, minion.upgradeTwo(), Material.ORANGE_STAINED_GLASS_PANE,
                "升级模块 2", "可放置资源蔓延或自动合成");
        player.openInventory(inventory);
    }

    private void renderStorage(Inventory inventory, MinionInstance minion) {
        int unlocked = Math.min(minion.storage().unlockedSlots(), STORAGE_SLOTS.length);
        List<ItemStack> stored = minion.storage().snapshot();
        ItemStack locked = lore(Material.WHITE_STAINED_GLASS_PANE, "未解锁的背包格", NamedTextColor.WHITE,
                List.of("小人每升一级解锁一个格子。"));
        for (int index = 0; index < STORAGE_SLOTS.length; index++) {
            ItemStack desired = index >= unlocked ? locked
                    : index < stored.size() ? stored.get(index) : null;
            int inventorySlot = STORAGE_SLOTS[index];
            if (!Objects.equals(inventory.getItem(inventorySlot), desired)) {
                inventory.setItem(inventorySlot, desired);
            }
        }
    }

    public void openConfirmation(Player player, MinionInstance minion, int targetLevel) {
        if (targetLevel <= minion.level() || targetLevel > config.maxLevel()) {
            player.sendMessage(config.message("invalid-level"));
            openMain(player, minion);
            return;
        }
        ConfirmHolder holder = new ConfirmHolder(minion.id(), targetLevel);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("确认快速升级"));
        holder.inventory = inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        List<String> lines = new ArrayList<>();
        lines.add("升级到等级 " + targetLevel + " 一共需要：");
        List<Requirement> requirements = manager.totalRequirements(minion, targetLevel);
        if (requirements.isEmpty()) lines.add("无需材料");
        requirements.forEach(requirement -> lines.add("• " + requirement.item().descriptor() + " × " + requirement.amount()));
        inventory.setItem(11, lore(Material.CHEST, "确定升级", NamedTextColor.GREEN, lines));
        inventory.setItem(15, lore(Material.BARRIER, "返回", NamedTextColor.RED, List.of("返回小人主界面")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof MainHolder main) handleMainClick(event, main);
        else if (holder instanceof ConfirmHolder confirm) handleConfirmClick(event, confirm);
    }

    private void handleMainClick(InventoryClickEvent event, MainHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MinionInstance minion = manager.byId(holder.minionId).orElse(null);
        if (minion == null) { player.closeInventory(); return; }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() >= topSize) {
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }
            if (event.isShiftClick()) {
                event.setCancelled(true);
                routeShiftClick(event, minion);
                openMain(player, minion);
            }
            return;
        }
        event.setCancelled(true);
        switch (event.getRawSlot()) {
            case FUEL_SLOT -> handleInteractiveCursor(player, minion, FUEL_SLOT);
            case UPGRADE_ONE_SLOT -> handleInteractiveCursor(player, minion, UPGRADE_ONE_SLOT);
            case UPGRADE_TWO_SLOT -> handleInteractiveCursor(player, minion, UPGRADE_TWO_SLOT);
            case COLLECT_SLOT -> manager.collect(player, minion);
            case QUICK_UPGRADE_SLOT -> {
                if (minion.level() >= config.maxLevel()) player.sendMessage(config.prefixed("§e该小人已经满级。"));
                else { player.closeInventory(); signInput.open(player, minion); }
            }
            case UPGRADE_SLOT -> {
                if (minion.level() >= config.maxLevel()) player.sendMessage(config.prefixed("§e该小人已经满级。"));
                else manager.upgrade(player, minion, minion.level() + 1);
                openMain(player, minion);
            }
            case PICKUP_SLOT -> { player.closeInventory(); manager.pickup(player, minion); }
            default -> { }
        }
    }

    private void handleConfirmClick(InventoryClickEvent event, ConfirmHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        MinionInstance minion = manager.byId(holder.minionId).orElse(null);
        if (minion == null) { player.closeInventory(); return; }
        if (event.getRawSlot() == 11) {
            manager.upgrade(player, minion, holder.targetLevel);
            openMain(player, minion);
        } else if (event.getRawSlot() == 15) openMain(player, minion);
    }

    private void handleInteractiveCursor(Player player, MinionInstance minion, int slot) {
        ItemStack cursor = emptyToNull(player.getItemOnCursor());
        ItemStack current = slot == FUEL_SLOT ? minion.fuel() : slot == UPGRADE_ONE_SLOT ? minion.upgradeOne() : minion.upgradeTwo();
        if (cursor == null) {
            player.setItemOnCursor(current);
            setSlot(minion, slot, null);
            openMain(player, minion);
            return;
        }
        if (!validForSlot(cursor, slot)) {
            player.sendMessage(config.message("invalid-item"));
            return;
        }
        if (slot == FUEL_SLOT) {
            setSlot(minion, slot, cursor);
            player.setItemOnCursor(current);
        } else if (current == null) {
            ItemStack one = cursor.clone();
            one.setAmount(1);
            setSlot(minion, slot, one);
            cursor.setAmount(cursor.getAmount() - 1);
            player.setItemOnCursor(cursor.getAmount() <= 0 ? null : cursor);
        } else {
            ItemStack one = cursor.clone();
            one.setAmount(1);
            setSlot(minion, slot, one);
            ItemStack remaining = cursor.clone();
            remaining.setAmount(cursor.getAmount() - 1);
            player.setItemOnCursor(current);
            if (remaining.getAmount() > 0) InventoryUtil.giveOrDrop(player, remaining);
        }
        openMain(player, minion);
    }

    private void routeShiftClick(InventoryClickEvent event, MinionInstance minion) {
        ItemStack item = emptyToNull(event.getCurrentItem());
        if (item == null) return;
        if (validForSlot(item, FUEL_SLOT) && minion.fuel() == null) {
            minion.fuel(item);
            event.setCurrentItem(null);
            return;
        }
        if (!validForSlot(item, UPGRADE_ONE_SLOT)) return;
        int slot = minion.upgradeOne() == null ? UPGRADE_ONE_SLOT : minion.upgradeTwo() == null ? UPGRADE_TWO_SLOT : -1;
        if (slot < 0) return;
        ItemStack one = item.clone();
        one.setAmount(1);
        setSlot(minion, slot, one);
        item.setAmount(item.getAmount() - 1);
        event.setCurrentItem(item.getAmount() <= 0 ? null : item);
    }

    private boolean validForSlot(ItemStack item, int slot) {
        if (slot == FUEL_SLOT) return config.fuels().stream().anyMatch(fuel -> resolver.matches(item, fuel.item()));
        return specialItems.idOf(item).map(specialItems.upgradeIds()::contains).orElse(false);
    }

    private void setSlot(MinionInstance minion, int slot, ItemStack item) {
        if (slot == FUEL_SLOT) minion.fuel(item);
        else if (slot == UPGRADE_ONE_SLOT) minion.upgradeOne(item);
        else minion.upgradeTwo(item);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MainHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    private ItemStack upgradeButton(MinionInstance minion) {
        if (minion.level() >= config.maxLevel()) return lore(Material.GOLD_INGOT, "已达到最高等级", NamedTextColor.YELLOW, List.of("最高等级为 11"));
        List<String> lines = new ArrayList<>();
        lines.add("升级到等级 " + (minion.level() + 1) + " 需要：");
        manager.totalRequirements(minion, minion.level() + 1)
                .forEach(requirement -> lines.add("• " + requirement.item().descriptor() + " × " + requirement.amount()));
        return lore(Material.GOLD_INGOT, "升级小人", NamedTextColor.GOLD, lines);
    }

    private void renderInteractiveSlot(Inventory inventory, int slot, ItemStack actual, Material emptyIcon, String name, String line) {
        inventory.setItem(slot, actual == null ? lore(emptyIcon, name, NamedTextColor.YELLOW, List.of(line)) : actual);
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lore(Material material, String name, NamedTextColor color, List<String> lines) {
        ItemStack item = named(material, Component.text(name, color));
        ItemMeta meta = item.getItemMeta();
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack emptyToNull(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : item.clone();
    }

    private static String stripColors(String value) {
        return value.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }

    private static String roman(int value) {
        String[] values = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"};
        return value >= 1 && value <= values.length ? values[value - 1] : String.valueOf(value);
    }

    private static final class MainHolder implements InventoryHolder {
        private final UUID minionId;
        private Inventory inventory;
        private MainHolder(UUID minionId) { this.minionId = minionId; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private final UUID minionId;
        private final int targetLevel;
        private Inventory inventory;
        private ConfirmHolder(UUID minionId, int targetLevel) { this.minionId = minionId; this.targetLevel = targetLevel; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
