package com.xigua.yknminions.item;

import com.xigua.Main;
import com.xigua.yknminions.config.FuelType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class SpecialItemService implements MinionUpgradeAccess {
    public static final String DIAMOND_SPREAD = "diamond_spread";
    public static final String EMERALD_SPREAD = "emerald_spread";
    public static final String IRON_SPREAD = "iron_spread";
    public static final String GOLD_SPREAD = "gold_spread";
    public static final String LAPIS_SPREAD = "lapis_spread";
    public static final String AUTO_CRAFT = "auto_craft";
    public static final String INFINITE_ENERGY = "infinite_energy";
    public static final String SMALL_FUEL = "small_fuel";
    public static final String MEDIUM_FUEL = "medium_fuel";
    public static final String SUPER_FUEL = "super_fuel";
    private static final Set<String> UPGRADE_IDS = Set.of(
            DIAMOND_SPREAD, EMERALD_SPREAD, IRON_SPREAD, GOLD_SPREAD, LAPIS_SPREAD, AUTO_CRAFT);
    private static final Set<String> FUEL_IDS = Set.of(
            INFINITE_ENERGY, SMALL_FUEL, MEDIUM_FUEL, SUPER_FUEL);
    private static final Set<String> IDS = Set.of(
            DIAMOND_SPREAD, EMERALD_SPREAD, IRON_SPREAD, GOLD_SPREAD, LAPIS_SPREAD,
            AUTO_CRAFT, INFINITE_ENERGY, SMALL_FUEL, MEDIUM_FUEL, SUPER_FUEL);

    private final Main plugin;

    public SpecialItemService(Main plugin) {
        this.plugin = plugin;
    }

    public ItemStack create(String rawId) {
        String id = rawId.toLowerCase(Locale.ROOT);
        ItemStack item;
        Component name;
        List<Component> lore;
        switch (id) {
            case DIAMOND_SPREAD -> {
                item = new ItemStack(Material.DIAMOND);
                name = Component.text("钻石蔓延", NamedTextColor.AQUA);
                lore = List.of(Component.text("放入小人升级栏后，产出资源时有概率额外获得钻石。", NamedTextColor.GRAY));
            }
            case EMERALD_SPREAD -> {
                item = new ItemStack(Material.EMERALD);
                name = Component.text("绿宝石蔓延", NamedTextColor.GREEN);
                lore = List.of(Component.text("放入小人升级栏后，产出资源时有概率额外获得绿宝石。", NamedTextColor.GRAY));
            }
            case IRON_SPREAD -> {
                item = new ItemStack(Material.IRON_INGOT);
                name = Component.text("铁蔓延", NamedTextColor.WHITE);
                lore = List.of(Component.text("放入小人升级栏后，产出资源时有概率额外获得铁锭。", NamedTextColor.GRAY));
            }
            case GOLD_SPREAD -> {
                item = new ItemStack(Material.GOLD_INGOT);
                name = Component.text("金蔓延", NamedTextColor.GOLD);
                lore = List.of(Component.text("放入小人升级栏后，产出资源时有概率额外获得金锭。", NamedTextColor.GRAY));
            }
            case LAPIS_SPREAD -> {
                item = new ItemStack(Material.LAPIS_LAZULI);
                name = Component.text("青金石蔓延", NamedTextColor.BLUE);
                lore = List.of(Component.text("放入小人升级栏后，产出资源时有概率额外获得青金石。", NamedTextColor.GRAY));
            }
            case AUTO_CRAFT -> {
                item = new ItemStack(Material.HOPPER);
                name = Component.text("自动合成", NamedTextColor.GOLD);
                lore = List.of(Component.text("放入小人升级栏后，自动将足够数量的资源压缩合成。", NamedTextColor.GRAY));
            }
            case INFINITE_ENERGY -> {
                item = new ItemStack(Material.HEAVY_CORE);
                name = Component.text("无尽能源", NamedTextColor.LIGHT_PURPLE);
                lore = List.of(Component.text("永不燃烧殆尽的燃料", NamedTextColor.GRAY));
            }
            case SMALL_FUEL -> {
                item = new ItemStack(Material.CHARCOAL);
                name = Component.text("小型燃料", NamedTextColor.YELLOW);
                lore = fuelLore(id);
            }
            case MEDIUM_FUEL -> {
                item = new ItemStack(Material.COAL);
                name = Component.text("中型燃料", NamedTextColor.GOLD);
                lore = fuelLore(id);
            }
            case SUPER_FUEL -> {
                item = new ItemStack(Material.LAVA_BUCKET);
                name = Component.text("超级燃料", NamedTextColor.RED);
                lore = fuelLore(id);
            }
            default -> { return null; }
        }
        ItemMeta meta = item.getItemMeta();
        meta.itemName(name);
        meta.lore(lore);
        meta.getPersistentDataContainer().set(plugin.key("special_id"), PersistentDataType.STRING, id);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> fuelLore(String id) {
        FuelType fuel = plugin.pluginConfig().fuel(id).orElse(null);
        if (fuel == null) return List.of(Component.text("小人专用燃料", NamedTextColor.GRAY));
        String duration = fuel.burnTimeSeconds() + " 秒";
        String efficiency = formatNumber(fuel.efficiency() * 100.0) + "%";
        return List.of(Component.text("能够燃烧 " + duration + "的燃料，能够给小人带来 "
                + efficiency + " 的提升", NamedTextColor.GRAY));
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    public Optional<String> idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(plugin.key("special_id"), PersistentDataType.STRING);
        return id == null || !IDS.contains(id) ? Optional.empty() : Optional.of(id);
    }

    public boolean is(ItemStack item, String id) {
        return idOf(item).map(id::equals).orElse(false);
    }

    public Set<String> ids() {
        return IDS;
    }

    public Set<String> upgradeIds() {
        return UPGRADE_IDS;
    }

    public Set<String> fuelIds() {
        return FUEL_IDS;
    }

    public ItemStack createMinionItem(String type, int level, String displayName) {
        ItemStack item = plugin.pluginConfig().minionType(type)
                .map(minionType -> minionType.model().helmet())
                .map(plugin.itemResolver()::create)
                .orElse(null);
        if (item == null) {
            item = plugin.pluginConfig().minionType(type)
                    .map(minionType -> minionType.drop())
                    .map(plugin.itemResolver()::create)
                    .orElse(null);
        }
        if (item == null) item = new ItemStack(Material.PLAYER_HEAD);
        item = item.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(LegacyComponentSerializer.legacySection().deserialize(displayName + " " + roman(level)));
        meta.lore(List.of(
                Component.text("等级 " + level, NamedTextColor.YELLOW),
                Component.text("右键方块放置小人", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(plugin.key("minion_type"), PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(plugin.key("minion_level"), PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
        return item;
    }

    public Optional<MinionItemData> minionData(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        String type = meta.getPersistentDataContainer().get(plugin.key("minion_type"), PersistentDataType.STRING);
        Integer level = meta.getPersistentDataContainer().get(plugin.key("minion_level"), PersistentDataType.INTEGER);
        return type == null || level == null ? Optional.empty() : Optional.of(new MinionItemData(type, level));
    }

    private static String roman(int value) {
        String[] romans = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"};
        return value >= 1 && value <= romans.length ? romans[value - 1] : String.valueOf(value);
    }

    public record MinionItemData(String type, int level) {}
}
