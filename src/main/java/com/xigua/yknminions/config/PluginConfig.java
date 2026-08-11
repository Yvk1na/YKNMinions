package com.xigua.yknminions.config;

import com.xigua.Main;
import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.util.RandomRange;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public final class PluginConfig {
    private final Main plugin;
    private Map<String, MinionType> minionTypes = Map.of();
    private List<FuelType> fuels = List.of();
    private int maxLevel;
    private int storageSlots;
    private int initialStorageSlots;
    private int storageSlotsPerLevel;
    private long tickPeriod;
    private long saveIntervalTicks;
    private List<SpreadSettings> spreads = List.of();
    private String prefix;

    public PluginConfig(Main plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        prefix = colorize(plugin.getConfig().getString("prefix",
                plugin.getConfig().getString("messages.prefix", "&6[YknMinions] &r")));
        maxLevel = Math.max(1, Math.min(64, plugin.getConfig().getInt("max-level", 11)));
        storageSlots = Math.max(1, Math.min(15, plugin.getConfig().getInt("storage-slots", 15)));
        initialStorageSlots = Math.max(1, Math.min(storageSlots,
                plugin.getConfig().getInt("initial-storage-slots", 5)));
        storageSlotsPerLevel = Math.max(0, Math.min(15,
                plugin.getConfig().getInt("storage-slots-per-level", 1)));
        tickPeriod = Math.max(1L, plugin.getConfig().getLong("tick-period", 2L));
        saveIntervalTicks = Math.max(20L, plugin.getConfig().getLong("save-interval-seconds", 60L) * 20L);
        spreads = loadSpreads();
        fuels = loadFuels();
        minionTypes = loadMinionTypes();
    }

    private List<FuelType> loadFuels() {
        LinkedHashMap<String, FuelDefaults> supported = new LinkedHashMap<>();
        supported.put("infinite_energy", new FuelDefaults(0.0, 0L, true));
        supported.put("small_fuel", new FuelDefaults(0.10, 3600L, false));
        supported.put("medium_fuel", new FuelDefaults(0.25, 10800L, false));
        supported.put("super_fuel", new FuelDefaults(0.50, 43200L, false));
        List<FuelType> result = new ArrayList<>();
        supported.forEach((id, defaults) -> {
            try {
                String path = "fuels." + id + ".";
                double efficiency = plugin.getConfig().getDouble(
                        path + "efficiency", defaults.efficiency());
                long burnTimeSeconds = defaults.infinite() ? 0L : plugin.getConfig().getLong(
                        path + "burn-time-seconds", defaults.burnTimeSeconds());
                result.add(new FuelType(id, new ItemSpec("yknminions:" + id),
                        efficiency, burnTimeSeconds, defaults.infinite()));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "无法加载燃料 " + id, exception);
            }
        });
        return List.copyOf(result);
    }

    private record FuelDefaults(double efficiency, long burnTimeSeconds, boolean infinite) {}

    private List<SpreadSettings> loadSpreads() {
        LinkedHashMap<String, String> rewards = new LinkedHashMap<>();
        rewards.put("diamond_spread", "minecraft:diamond");
        rewards.put("emerald_spread", "minecraft:emerald");
        rewards.put("iron_spread", "minecraft:iron_ingot");
        rewards.put("gold_spread", "minecraft:gold_ingot");
        rewards.put("lapis_spread", "minecraft:lapis_lazuli");
        List<SpreadSettings> result = new ArrayList<>();
        rewards.forEach((id, reward) -> {
            String path = id.replace('_', '-') + ".";
            result.add(new SpreadSettings(id, new ItemSpec(reward),
                    plugin.getConfig().getDouble(path + "chance", 0.10),
                    RandomRange.parse(plugin.getConfig().get(path + "amount"), 1)));
        });
        return List.copyOf(result);
    }

    private Map<String, MinionType> loadMinionTypes() {
        File file = new File(plugin.getDataFolder(), "minions.yml");
        mergeBundledMinionTypes(file);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("types");
        if (root == null) return Map.of();
        Map<String, MinionType> result = new LinkedHashMap<>();
        for (String rawId : root.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            try {
                ModelSettings model = readModel(Objects.requireNonNull(section.getConfigurationSection("model")));
                Map<Integer, LevelSettings> levels = readLevels(Objects.requireNonNull(section.getConfigurationSection("levels")));
                RandomRange dropAmount = RandomRange.parse(section.get("drop-amount"), 1);
                SlimeSettings slimeSettings = readSlimeSettings(id,
                        section.getConfigurationSection("slime-settings"), dropAmount);
                result.put(id, new MinionType(id,
                        section.getString("display-name", id),
                        new ItemSpec(section.getString("drop", "minecraft:cobblestone")),
                        dropAmount,
                        section.getString("work-sound", "BLOCK_STONE_BREAK"),
                        model, readMobSettings(id, section.getConfigurationSection("mob-settings"),
                        dropAmount, slimeSettings), slimeSettings,
                        readMiningSettings(section.getConfigurationSection("mining-settings")),
                        readFarmingSettings(section.getConfigurationSection("farming-settings")), levels));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "无法加载小人类型 " + id, exception);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void mergeBundledMinionTypes(File file) {
        try (InputStream stream = plugin.getResource("minions.yml")) {
            if (stream == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection bundledTypes = bundled.getConfigurationSection("types");
            if (bundledTypes == null) return;
            YamlConfiguration installed = YamlConfiguration.loadConfiguration(file);
            boolean changed = false;
            for (String id : bundledTypes.getKeys(false)) {
                String targetPath = "types." + id;
                if (installed.isConfigurationSection(targetPath)) continue;
                ConfigurationSection source = bundledTypes.getConfigurationSection(id);
                if (source == null) continue;
                for (Map.Entry<String, Object> entry : source.getValues(true).entrySet()) {
                    if (!(entry.getValue() instanceof ConfigurationSection)) {
                        installed.set(targetPath + "." + entry.getKey(), entry.getValue());
                    }
                }
                changed = true;
            }
            if (changed) {
                installed.save(file);
                plugin.getLogger().info("已向 minions.yml 补充新版本内置的小人类型。");
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法合并新版内置小人配置", exception);
        }
    }

    private ModelSettings readModel(ConfigurationSection section) {
        return new ModelSettings(
                specOrNull(section.getString("helmet")), specOrNull(section.getString("chestplate")),
                specOrNull(section.getString("leggings")), specOrNull(section.getString("boots")),
                specOrNull(section.getString("tool")), parseColor(section.getString("leather-color", "160,101,64")));
    }

    private SlimeSettings readSlimeSettings(String typeId, ConfigurationSection section, RandomRange fallbackDropAmount) {
        if (section == null && !"slime".equals(typeId)) return null;
        LinkedHashMap<Integer, Double> chances = new LinkedHashMap<>();
        ConfigurationSection chanceSection = section == null ? null : section.getConfigurationSection("size-chances");
        if (chanceSection != null) {
            for (String rawSize : chanceSection.getKeys(false)) {
                try {
                    chances.put(Integer.parseInt(rawSize), chanceSection.getDouble(rawSize));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("忽略无效的史莱姆大小: " + rawSize);
                }
            }
        }
        if (chances.isEmpty()) {
            chances.put(1, 50.0);
            chances.put(2, 35.0);
            chances.put(4, 15.0);
        }
        Object configuredDrop = section == null ? null : section.get("drop-per-kill");
        RandomRange dropPerKill = configuredDrop == null
                ? fallbackDropAmount
                : RandomRange.parse(configuredDrop, fallbackDropAmount.min());
        long turnDelayTicks = section == null ? 10L : section.getLong("turn-delay-ticks", 10L);
        return new SlimeSettings(chances, dropPerKill, turnDelayTicks);
    }

    private MobSettings readMobSettings(String typeId, ConfigurationSection section,
                                        RandomRange fallbackDropAmount, SlimeSettings slimeSettings) {
        if (section == null) {
            if (!"slime".equals(typeId) || slimeSettings == null) return null;
            return new MobSettings(EntityType.SLIME, slimeSettings.dropPerKill(), slimeSettings.turnDelayTicks());
        }
        String rawEntity = section.getString("entity", typeId).toUpperCase(Locale.ROOT);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(rawEntity);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的目标生物类型: " + rawEntity, exception);
        }
        RandomRange dropPerKill = RandomRange.parse(section.get("drop-per-kill"), fallbackDropAmount.min());
        long turnDelayTicks = section.getLong("turn-delay-ticks", 10L);
        return new MobSettings(entityType, dropPerKill, turnDelayTicks);
    }

    private MiningSettings readMiningSettings(ConfigurationSection section) {
        if (section == null) return null;
        Material block = Material.matchMaterial(section.getString("block", ""), false);
        if (block == null) throw new IllegalArgumentException("未知的采集方块: " + section.getString("block"));
        return new MiningSettings(block);
    }

    private FarmingSettings readFarmingSettings(ConfigurationSection section) {
        if (section == null) return null;
        String rawCrop = section.getString("crop", "").toUpperCase(Locale.ROOT);
        try {
            return new FarmingSettings(FarmingSettings.Crop.valueOf(rawCrop));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的农业作物类型: " + rawCrop, exception);
        }
    }

    private Map<Integer, LevelSettings> readLevels(ConfigurationSection section) {
        Map<Integer, LevelSettings> result = new HashMap<>();
        for (String key : section.getKeys(false)) {
            int level = Integer.parseInt(key);
            ConfigurationSection current = section.getConfigurationSection(key);
            if (current == null) continue;
            List<Requirement> requirements = new ArrayList<>();
            for (Map<?, ?> entry : current.getMapList("upgrade-materials")) {
                Object item = entry.get("item");
                Object amount = entry.get("amount");
                if (item == null || !(amount instanceof Number number) || number.intValue() <= 0) continue;
                requirements.add(new Requirement(new ItemSpec(String.valueOf(item)), number.intValue()));
            }
            result.put(level, new LevelSettings(level,
                    current.getDouble("work-interval-seconds", 10.0), requirements));
        }
        return result;
    }

    private static ItemSpec specOrNull(String value) {
        return value == null || value.isBlank() ? null : new ItemSpec(value);
    }

    private static Color parseColor(String raw) {
        try {
            String[] split = raw.split(",");
            return Color.fromRGB(Integer.parseInt(split[0].trim()), Integer.parseInt(split[1].trim()), Integer.parseInt(split[2].trim()));
        } catch (RuntimeException ignored) {
            return Color.fromRGB(160, 101, 64);
        }
    }

    public String message(String key) {
        return prefixed(plugin.getConfig().getString("messages." + key, key));
    }

    public String prefixed(String message) { return prefix + colorize(message); }
    public String prefix() { return prefix; }

    private static String colorize(String value) {
        if (value == null) return "";
        return value.replaceAll("&([0-9A-FK-ORa-fk-or])", "§$1");
    }

    public Map<String, MinionType> minionTypes() { return minionTypes; }
    public Optional<MinionType> minionType(String id) { return Optional.ofNullable(minionTypes.get(id.toLowerCase(Locale.ROOT))); }
    public List<FuelType> fuels() { return fuels; }
    public Optional<FuelType> fuel(String id) {
        if (id == null) return Optional.empty();
        return fuels.stream().filter(fuel -> fuel.id().equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }
    public int maxLevel() { return maxLevel; }
    public int storageSlots() { return storageSlots; }
    public int storageSlots(int level) {
        long unlocked = (long) initialStorageSlots + (long) Math.max(0, level - 1) * storageSlotsPerLevel;
        return (int) Math.min(storageSlots, unlocked);
    }
    public long tickPeriod() { return tickPeriod; }
    public long saveIntervalTicks() { return saveIntervalTicks; }
    public List<SpreadSettings> spreads() { return spreads; }
}
