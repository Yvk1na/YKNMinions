package com.xigua.yknminions.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemSpecTest {
    @Test
    void normalizesAllSupportedProviders() {
        assertEquals("minecraft:diamond", new ItemSpec("DIAMOND").descriptor());
        assertEquals("itemsadder:pack:item", new ItemSpec("ItemsAdder:Pack:Item").descriptor());
        assertEquals("craftengine:pack:item", new ItemSpec("CraftEngine:Pack:Item").descriptor());
        assertEquals("mmoitems:MATERIAL:ENCHANTED_SLIME", new ItemSpec("MMOItems:material:enchanted_slime").descriptor());
    }
}
