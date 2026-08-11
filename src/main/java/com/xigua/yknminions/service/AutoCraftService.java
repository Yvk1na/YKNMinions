package com.xigua.yknminions.service;

import com.xigua.Main;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.model.MinionStorage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

public final class AutoCraftService {
    private final Main plugin;
    private final ItemResolver resolver;
    private List<ConfiguredRule> configuredRules = List.of();
    private List<BukkitRule> bukkitRules = List.of();
    private long lastBukkitRefresh;

    public AutoCraftService(Main plugin, ItemResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "auto-craft-recipes.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("recipes");
        List<ConfiguredRule> loaded = new ArrayList<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null || !section.getBoolean("enabled", true)) continue;
                try {
                    int inputAmount = section.getInt("input-amount");
                    int outputAmount = section.getInt("output-amount", 1);
                    if (inputAmount < 2 || outputAmount < 1 || outputAmount >= inputAmount) throw new IllegalArgumentException("invalid amounts");
                    loaded.add(new ConfiguredRule(new ItemSpec(Objects.requireNonNull(section.getString("input"))), inputAmount,
                            new ItemSpec(Objects.requireNonNull(section.getString("output"))), outputAmount));
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "自动合成配置无效: " + id, exception);
                }
            }
        }
        configuredRules = List.copyOf(loaded);
        refreshBukkitRecipes();
    }

    public void compact(MinionStorage storage, ItemStack ignoredTrigger) {
        if (System.currentTimeMillis() - lastBukkitRefresh > 60_000L) refreshBukkitRecipes();
        for (int pass = 0; pass < 64; pass++) {
            boolean crafted = false;
            for (ItemStack sample : storage.snapshot()) {
                CraftOperation operation = findOperation(sample);
                if (operation == null || operation.inputAmount < 2) continue;
                Optional<String> canonical = resolver.canonicalKey(sample);
                if (canonical.isEmpty()) continue;
                ItemSpec inputSpec = new ItemSpec(canonical.get());
                if (storage.count(inputSpec, resolver) < operation.inputAmount) continue;
                if (!storage.remove(inputSpec, operation.inputAmount, resolver)) continue;
                if (!storage.canFit(operation.output)) {
                    restoreInput(storage, sample, operation.inputAmount);
                    return;
                }
                storage.add(operation.output.clone());
                crafted = true;
                break;
            }
            if (!crafted) return;
        }
    }

    private CraftOperation findOperation(ItemStack input) {
        for (ConfiguredRule rule : configuredRules) {
            if (!resolver.matches(input, rule.input)) continue;
            ItemStack output = resolver.create(rule.output, 1);
            if (output != null) {
                output.setAmount(rule.outputAmount);
                return new CraftOperation(rule.inputAmount, output);
            }
        }
        for (BukkitRule rule : bukkitRules) {
            if (rule.matches(input)) return new CraftOperation(rule.inputAmount, rule.output.clone());
        }
        return findCraftEngineOperation(input);
    }

    private void restoreInput(MinionStorage storage, ItemStack sample, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            ItemStack restore = sample.clone();
            int part = Math.min(restore.getMaxStackSize(), remaining);
            restore.setAmount(part);
            storage.add(restore);
            remaining -= part;
        }
    }

    private void refreshBukkitRecipes() {
        List<BukkitRule> rules = new ArrayList<>();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            List<RecipeChoice> choices = choices(recipe);
            ItemStack result = recipe.getResult();
            // Automatic discovery is deliberately limited to 3x3 compression recipes. Smaller homogeneous
            // recipes include unrelated outputs such as crafting tables; admins can opt those in explicitly.
            if (choices.size() < 9 || result == null || result.getType().isAir() || result.getAmount() >= choices.size()) continue;
            rules.add(new BukkitRule(choices.size(), List.copyOf(choices), result.clone()));
        }
        bukkitRules = List.copyOf(rules);
        lastBukkitRefresh = System.currentTimeMillis();
    }

    private List<RecipeChoice> choices(Recipe recipe) {
        List<RecipeChoice> result = new ArrayList<>();
        if (recipe instanceof ShapelessRecipe shapeless) {
            result.addAll(shapeless.getChoiceList());
        } else if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> map = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char symbol : row.toCharArray()) {
                    if (symbol == ' ') continue;
                    RecipeChoice choice = map.get(symbol);
                    if (choice != null) result.add(choice);
                }
            }
        }
        return result;
    }

    /** Reads CraftEngine's public recipe API reflectively so CraftEngine remains an optional dependency. */
    private CraftOperation findCraftEngineOperation(ItemStack input) {
        String canonical = resolver.canonicalKey(input).orElse("");
        if (!canonical.startsWith("craftengine:")) return null;
        String itemId = canonical.substring("craftengine:".length());
        try {
            Class<?> engineClass = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine");
            Object engine = engineClass.getMethod("instance").invoke(null);
            Object recipeManager = engineClass.getMethod("recipeManager").invoke(engine);
            Class<?> keyClass = Class.forName("net.momirealms.craftengine.core.util.Key");
            Object key = keyClass.getMethod("of", String.class).invoke(null, itemId);
            @SuppressWarnings("unchecked")
            List<Object> recipes = (List<Object>) recipeManager.getClass().getMethod("recipeByIngredient", keyClass).invoke(recipeManager, key);
            for (Object recipe : recipes) {
                if (hasConditionalResult(recipe)) continue;
                List<Object> ingredients = expandedCraftEngineIngredients(recipe);
                int required = 0;
                boolean allMatch = !ingredients.isEmpty();
                for (Object ingredient : ingredients) {
                    @SuppressWarnings("unchecked")
                    List<Object> accepted = (List<Object>) ingredient.getClass().getMethod("items").invoke(ingredient);
                    if (accepted.stream().noneMatch(value -> itemId.equals(value.toString()))) {
                        allMatch = false;
                        break;
                    }
                    required += ((Number) ingredient.getClass().getMethod("count").invoke(ingredient)).intValue();
                }
                if (!allMatch || required < 9) continue;
                Method resultMethod = Arrays.stream(recipe.getClass().getMethods())
                        .filter(method -> method.getName().equals("result") && method.getParameterCount() == 0)
                        .findFirst().orElse(null);
                if (resultMethod == null) continue;
                Object result = resultMethod.invoke(recipe);
                Class<?> contextClass = Class.forName("net.momirealms.craftengine.core.item.ItemBuildContext");
                Object context = contextClass.getMethod("empty").invoke(null);
                Object built = result.getClass().getMethod("buildItem", contextClass).invoke(result, context);
                Method bukkitItem = Arrays.stream(built.getClass().getMethods())
                        .filter(method -> method.getName().equals("getBukkitItem") && method.getParameterCount() == 0)
                        .findFirst().orElse(null);
                if (bukkitItem == null) continue;
                ItemStack output = ((ItemStack) bukkitItem.invoke(built)).clone();
                if (output.getAmount() >= required) continue;
                return new CraftOperation(required, output);
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            // CraftEngine is absent, still loading, or its experimental API changed. Configured rules remain available.
        }
        return null;
    }

    private List<Object> expandedCraftEngineIngredients(Object recipe) throws ReflectiveOperationException {
        try {
            Object pattern = recipe.getClass().getMethod("pattern").invoke(recipe);
            String[] rows = (String[]) pattern.getClass().getMethod("pattern").invoke(pattern);
            @SuppressWarnings("unchecked")
            Map<Character, Object> bySymbol = (Map<Character, Object>) pattern.getClass().getMethod("ingredients").invoke(pattern);
            List<Object> expanded = new ArrayList<>();
            for (String row : rows) {
                for (char symbol : row.toCharArray()) {
                    if (symbol == ' ') continue;
                    Object ingredient = bySymbol.get(symbol);
                    if (ingredient != null) expanded.add(ingredient);
                }
            }
            if (!expanded.isEmpty()) return expanded;
        } catch (NoSuchMethodException ignored) {
            // Shapeless and non-crafting recipes expose the already-expanded ingredient list.
        }
        @SuppressWarnings("unchecked")
        List<Object> ingredients = (List<Object>) recipe.getClass().getMethod("ingredientsInUse").invoke(recipe);
        return ingredients;
    }

    private boolean hasConditionalResult(Object recipe) {
        try {
            Method method = recipe.getClass().getMethod("hasCondition");
            return Boolean.TRUE.equals(method.invoke(recipe));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private record ConfiguredRule(ItemSpec input, int inputAmount, ItemSpec output, int outputAmount) {}
    private record CraftOperation(int inputAmount, ItemStack output) {}
    private record BukkitRule(int inputAmount, List<RecipeChoice> choices, ItemStack output) {
        boolean matches(ItemStack input) {
            ItemStack test = input.clone();
            test.setAmount(1);
            for (RecipeChoice choice : choices) if (!choice.test(test)) return false;
            return true;
        }
    }
}
