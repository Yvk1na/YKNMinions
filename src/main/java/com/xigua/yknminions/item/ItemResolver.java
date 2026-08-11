package com.xigua.yknminions.item;

import com.xigua.Main;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public final class ItemResolver {
    private final Main plugin;
    private final SpecialItemService specialItems;

    public ItemResolver(Main plugin, SpecialItemService specialItems) {
        this.plugin = plugin;
        this.specialItems = specialItems;
    }

    public ItemStack create(ItemSpec spec, int amount) {
        ItemStack result = switch (spec.provider()) {
            case "minecraft" -> createVanilla(spec.value());
            case "yknminions" -> specialItems.create(spec.value());
            case "itemsadder" -> createItemsAdder(spec.value());
            case "craftengine" -> createCraftEngine(spec.value());
            case "mmoitems" -> createMmoItems(spec.value());
            default -> null;
        };
        if (result != null) result.setAmount(Math.max(1, Math.min(result.getMaxStackSize(), amount)));
        return result;
    }

    public ItemStack create(ItemSpec spec) {
        return create(spec, 1);
    }

    public ItemStack createOrFallback(ItemSpec spec, int amount) {
        ItemStack item = create(spec, amount);
        if (item != null) return item;
        ItemStack fallback = new ItemStack(Material.BARRIER, Math.max(1, Math.min(64, amount)));
        var meta = fallback.getItemMeta();
        meta.setDisplayName("§c无法解析: " + spec.descriptor());
        fallback.setItemMeta(meta);
        return fallback;
    }

    public boolean matches(ItemStack stack, ItemSpec spec) {
        Optional<String> key = canonicalKey(stack);
        if (key.isPresent() && key.get().equals(spec.descriptor())) return true;
        if (spec.provider().equals("mmoitems")) {
            ItemStack template = createMmoItems(spec.value());
            return template != null && template.isSimilar(stack);
        }
        return false;
    }

    public Optional<String> canonicalKey(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Optional.empty();
        Optional<String> local = specialItems.idOf(stack).map(id -> "yknminions:" + id);
        if (local.isPresent()) return local;

        String itemsAdder = itemsAdderId(stack);
        if (itemsAdder != null) return Optional.of(new ItemSpec("itemsadder:" + itemsAdder).descriptor());
        String craftEngine = craftEngineId(stack);
        if (craftEngine != null) return Optional.of(new ItemSpec("craftengine:" + craftEngine).descriptor());
        String mmoItems = mmoItemsId(stack);
        if (mmoItems != null) return Optional.of(new ItemSpec("mmoitems:" + mmoItems).descriptor());
        return Optional.of("minecraft:" + stack.getType().getKey().getKey());
    }

    private ItemStack createVanilla(String id) {
        Material material = Material.matchMaterial(id, false);
        return material == null || material.isAir() ? null : new ItemStack(material);
    }

    private ItemStack createItemsAdder(String id) {
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object wrapper = customStack.getMethod("getInstance", String.class).invoke(null, id);
            if (wrapper == null) return null;
            return ((ItemStack) customStack.getMethod("getItemStack").invoke(wrapper)).clone();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private String itemsAdderId(ItemStack stack) {
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object wrapper = customStack.getMethod("byItemStack", ItemStack.class).invoke(null, stack);
            return wrapper == null ? null : String.valueOf(customStack.getMethod("getNamespacedID").invoke(wrapper));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private ItemStack createCraftEngine(String id) {
        try {
            Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            Object definition = api.getMethod("byId", String.class).invoke(null, id);
            if (definition == null) return null;
            return ((ItemStack) definition.getClass().getMethod("buildBukkitItem").invoke(definition)).clone();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private String craftEngineId(ItemStack stack) {
        try {
            Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            Object key = api.getMethod("getCustomItemId", ItemStack.class).invoke(null, stack);
            return key == null ? null : key.toString();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private ItemStack createMmoItems(String value) {
        String[] split = value.split(":", 2);
        if (split.length != 2) return null;
        try {
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Class<?> mmoClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Field pluginField = mmoClass.getField("plugin");
            Object mmoPlugin = pluginField.get(null);
            Object type;
            try {
                Object typeManager = mmoPlugin.getClass().getMethod("getTypes").invoke(mmoPlugin);
                type = typeManager.getClass().getMethod("get", String.class).invoke(typeManager, split[0]);
            } catch (ReflectiveOperationException noManagerApi) {
                type = typeClass.getMethod("get", String.class).invoke(null, split[0]);
            }
            if (type == null) return null;
            for (Method method : mmoPlugin.getClass().getMethods()) {
                if (!method.getName().equals("getItem") || method.getParameterCount() != 2) continue;
                if (!method.getParameterTypes()[0].isInstance(type) || method.getParameterTypes()[1] != String.class) continue;
                Object result = method.invoke(mmoPlugin, type, split[1]);
                if (result instanceof ItemStack stack) return stack.clone();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
        return null;
    }

    private String mmoItemsId(ItemStack stack) {
        for (String className : List.of("io.lumine.mythic.lib.api.item.NBTItem", "net.Indyuce.mmoitems.api.item.NBTItem")) {
            try {
                Class<?> nbtClass = Class.forName(className);
                Method get = Arrays.stream(nbtClass.getMethods())
                        .filter(method -> method.getName().equals("get") && Modifier.isStatic(method.getModifiers())
                                && method.getParameterCount() == 1 && method.getParameterTypes()[0] == ItemStack.class)
                        .findFirst().orElse(null);
                if (get == null) continue;
                Object nbt = get.invoke(null, stack);
                Method getString = nbtClass.getMethod("getString", String.class);
                String type;
                try {
                    type = String.valueOf(nbtClass.getMethod("getType").invoke(nbt));
                } catch (ReflectiveOperationException noTypeMethod) {
                    type = String.valueOf(getString.invoke(nbt, "MMOITEMS_ITEM_TYPE"));
                }
                String id = String.valueOf(getString.invoke(nbt, "MMOITEMS_ITEM_ID"));
                if (!type.isBlank() && !type.equals("null") && !id.isBlank() && !id.equals("null")) return type + ":" + id;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Try the other known MythicLib/MMOItems package.
            }
        }
        return null;
    }

    public String compatibilitySummary() {
        List<String> enabled = new ArrayList<>();
        for (String name : List.of("MMOItems", "ItemsAdder", "CraftEngine")) {
            enabled.add(name + "=" + (plugin.getServer().getPluginManager().isPluginEnabled(name) ? "ON" : "OFF"));
        }
        return String.join(", ", enabled);
    }
}
