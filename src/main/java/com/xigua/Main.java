package com.xigua;

import com.xigua.yknminions.command.MinionsCommand;
import com.xigua.yknminions.config.PluginConfig;
import com.xigua.yknminions.gui.AdminGui;
import com.xigua.yknminions.gui.MinionGui;
import com.xigua.yknminions.gui.SignInputService;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.listener.MinionListener;
import com.xigua.yknminions.service.AutoCraftService;
import com.xigua.yknminions.service.MinionManager;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveBundledResource("minions.yml");
        saveBundledResource("auto-craft-recipes.yml");

        specialItems = new SpecialItemService(this);
        itemResolver = new ItemResolver(this, specialItems);
        pluginConfig = new PluginConfig(this);
        pluginConfig.reload();
        autoCraftService = new AutoCraftService(this, itemResolver);
        autoCraftService.reload();
        minionManager = new MinionManager(this, pluginConfig, itemResolver, specialItems, autoCraftService);
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
        getLogger().info("YknMinions 已启用：加载了 " + pluginConfig.minionTypes().size() + " 种小人。兼容层："
                + itemResolver.compatibilitySummary());
    }

    @Override
    public void onDisable() {
        if (signInputService != null) signInputService.closeAll();
        if (minionManager != null) minionManager.shutdown();
    }

    public void reloadPlugin() {
        reloadConfig();
        pluginConfig.reload();
        autoCraftService.reload();
        minionManager.refreshModels();
        minionManager.restartTasks();
    }

    private void saveBundledResource(String name) {
        if (!getDataFolder().toPath().resolve(name).toFile().exists()) saveResource(name, false);
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
