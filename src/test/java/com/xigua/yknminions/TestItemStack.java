package com.xigua.yknminions;

import org.bukkit.Material;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Minimal ItemStack double that does not require a running Paper registry. */
@SerializableAs("YknMinionsTestItemStack")
public final class TestItemStack extends ItemStack {
    private Material type;
    private int amount;

    public TestItemStack(Material type, int amount) {
        super();
        this.type = Objects.requireNonNull(type, "type");
        this.amount = amount;
    }

    @Override
    public Material getType() {
        return type;
    }

    @Override
    public void setType(Material type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public boolean isSimilar(ItemStack stack) {
        return stack != null && type == stack.getType();
    }

    @Override
    public TestItemStack clone() {
        return new TestItemStack(type, amount);
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", type.name());
        values.put("amount", amount);
        return values;
    }

    public static TestItemStack deserialize(Map<String, Object> values) {
        return new TestItemStack(Material.valueOf(String.valueOf(values.get("type"))),
                ((Number) values.get("amount")).intValue());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemStack stack
                && amount == stack.getAmount() && isSimilar(stack);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, amount);
    }
}
