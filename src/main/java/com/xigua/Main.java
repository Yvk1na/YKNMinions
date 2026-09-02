package com.xigua;

import com.xigua.yknminions.command.MinionsCommand;
import com.xigua.yknminions.config.PluginConfig;
import com.xigua.yknminions.gui.AdminGui;
import com.xigua.yknminions.gui.MinionGui;
import com.xigua.yknminions.gui.SignInputService;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.integration.AuraSkillsIntegration;
import com.xigua.yknminions.integration.CollectionIntegration;
import com.xigua.yknminions.integration.DisabledCollectionIntegration;
import com.xigua.yknminions.integration.DisabledSkillXpIntegration;
import com.xigua.yknminions.integration.EcoCollectionsIntegration;
import com.xigua.yknminions.integration.SkillXpIntegration;
import com.xigua.yknminions.listener.MinionListener;
import com.xigua.yknminions.service.AutoCraftService;
import com.xigua.yknminions.service.MinionManager;
import dev.chengzhi.skyblockcore.api.delivery.DetailedItemDeliveryApi;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Main extends JavaPlugin {
    private PluginConfig pluginConfig;
    private SpecialItemService specialItems;
    private ItemResolver itemResolver;
    private AutoCraftService autoCraftService;
    private MinionManager minionManager;
    private MinionGui minionGui;
    private AdminGui adminGui;
    private SignInputService signInputService;
    private DetailedItemDeliveryApi itemDeliveryApi;
    private SkillXpIntegration skillXpIntegration;
    private CollectionIntegration collectionIntegration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveBundledResource("minions.yml");
        saveBundledResource("auto-craft-recipes.yml");

        itemDeliveryApi = getServer().getServicesManager().load(DetailedItemDeliveryApi.class);
        if (itemDeliveryApi == null) {
            getLogger().severe("Skyblock-Core 未注册 DetailedItemDeliveryApi，YknMinions 将停止加载。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        specialItems = new SpecialItemService(this);
        itemResolver = new ItemResolver(this, specialItems);
        pluginConfig = new PluginConfig(this);
        pluginConfig.reload();
        autoCraftService = new AutoCraftService(this, itemResolver);
        autoCraftService.reload();
        skillXpIntegration = loadSkillXpIntegration();
        collectionIntegration = loadCollectionIntegration();
        minionManager = new MinionManager(this, pluginConfig, itemResolver, specialItems, autoCraftService,
                itemDeliveryApi, skillXpIntegration, collectionIntegration);
        signInputService = new SignInputService(this);
        minionGui = new MinionGui(this, minionManager, pluginConfig, itemResolver, specialItems, signInputService);
        adminGui = new AdminGui(this, itemResolver, specialItems);
        signInputService.setGui(minionGui);

        MinionListener listener = new MinionListener(this, minionManager, minionGui, specialItems);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(minionGui, this);
        getServer().getPluginManager().registerEvents(adminGui, this);
        getServer().getPluginManager().registerEvents(signInputService, this);

        MinionsCommand command = new MinionsCommand(this, adminGui);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("minions"), "minions command missing from plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        minionManager.load();
        minionManager.startTasks();
        minionGui.startRefreshTask();
        scheduleIntegrationConfigurationValidation();
        getLogger().info("YknMinions 已启用：加载了 " + pluginConfig.minionTypes().size() + " 种小人。兼容层："
                + itemResolver.compatibilitySummary() + "；AuraSkills="
                + skillXpIntegration.available() + "；EcoCollections="
                + collectionIntegration.available());
    }

    @Override
    public void onDisable() {
        if (minionGui != null) minionGui.stopRefreshTask();
        if (signInputService != null) signInputService.closeAll();
        if (minionManager != null) minionManager.shutdown();
    }

    public void reloadPlugin() {
        reloadConfig();
        pluginConfig.reload();
        autoCraftService.reload();
        validateIntegrationConfiguration();
        minionManager.refreshModels();
        minionManager.restartTasks();
    }

    private void validateIntegrationConfiguration() {
        if (skillXpIntegration == null || collectionIntegration == null) return;
        int skillMappings = 0;
        int validSkillMappings = 0;
        int collectionMappings = 0;
        int validCollectionMappings = 0;
        for (var type : pluginConfig.minionTypes().values()) {
            if (type.skillSettings() != null && skillXpIntegration.available()) {
                skillMappings++;
                if (skillXpIntegration.validSkill(type.skillSettings().skillId())) {
                    validSkillMappings++;
                }
            }
            if (type.collectionSettings() != null && collectionIntegration.available()) {
                collectionMappings++;
                if (collectionIntegration.validCollection(type.collectionSettings().id())) {
                    validCollectionMappings++;
                }
            }
        }
        if (skillMappings > 0 || collectionMappings > 0) {
            getLogger().info("可选集成配置校验完成：AuraSkills=" + validSkillMappings + "/"
                    + skillMappings + "；EcoCollections=" + validCollectionMappings + "/"
                    + collectionMappings);
        }
    }

    private void scheduleIntegrationConfigurationValidation() {
        // AuraSkills and EcoCollections populate their registries in delayed init tasks.
        // Waiting one second prevents valid IDs from being rejected during onEnable.
        getServer().getScheduler().runTaskLater(this,
                this::validateIntegrationConfiguration, 20L);
    }

    private void saveBundledResource(String name) {
        if (!getDataFolder().toPath().resolve(name).toFile().exists()) saveResource(name, false);
    }

    private SkillXpIntegration loadSkillXpIntegration() {
        if (!getServer().getPluginManager().isPluginEnabled("AuraSkills")) {
            getLogger().warning("未检测到 AuraSkills：小人领取仍可用，但不会发放技能经验。");
            return new DisabledSkillXpIntegration();
        }
        try {
            return new AuraSkillsIntegration(this);
        } catch (RuntimeException | LinkageError failure) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "AuraSkills API 连接失败：技能经验集成已停用。", failure);
            return new DisabledSkillXpIntegration();
        }
    }

    private CollectionIntegration loadCollectionIntegration() {
        if (!getServer().getPluginManager().isPluginEnabled("EcoCollections")) {
            getLogger().warning("未检测到 EcoCollections：小人生产仍可用，但不会增加图鉴进度。");
            return new DisabledCollectionIntegration();
        }
        try {
            EcoCollectionsIntegration integration = new EcoCollectionsIntegration(this);
            return integration.available() ? integration : new DisabledCollectionIntegration();
        } catch (RuntimeException | LinkageError failure) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "EcoCollections API 连接失败：图鉴进度集成已停用。", failure);
            return new DisabledCollectionIntegration();
        }
    }

    public NamespacedKey key(String value) {
        return new NamespacedKey(this, value);
    }

    public PluginConfig pluginConfig() { return pluginConfig; }
    public SpecialItemService specialItems() { return specialItems; }
    public ItemResolver itemResolver() { return itemResolver; }
    public AutoCraftService autoCraftService() { return autoCraftService; }
    public MinionManager minionManager() { return minionManager; }
    public MinionGui minionGui() { return minionGui; }
}
