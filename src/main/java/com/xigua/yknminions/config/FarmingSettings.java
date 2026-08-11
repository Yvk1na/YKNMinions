package com.xigua.yknminions.config;

import org.bukkit.Material;

public record FarmingSettings(Crop crop) {
    public enum Crop {
        CACTUS(Material.CACTUS, Material.SAND),
        CARROT(Material.CARROTS, Material.FARMLAND),
        RED_MUSHROOM(Material.RED_MUSHROOM, Material.MYCELIUM),
        BROWN_MUSHROOM(Material.BROWN_MUSHROOM, Material.MYCELIUM),
        NETHER_WART(Material.NETHER_WART, Material.SOUL_SAND),
        POTATO(Material.POTATOES, Material.FARMLAND),
        SUGAR_CANE(Material.SUGAR_CANE, Material.SAND),
        WHEAT(Material.WHEAT, Material.FARMLAND),
        MELON(Material.MELON_STEM, Material.FARMLAND),
        PUMPKIN(Material.PUMPKIN_STEM, Material.FARMLAND);

        private final Material plant;
        private final Material ground;

        Crop(Material plant, Material ground) {
            this.plant = plant;
            this.ground = ground;
        }

        public Material plant() { return plant; }
        public Material ground() { return ground; }
        public boolean isStem() { return this == MELON || this == PUMPKIN; }
        public boolean isColumnPlant() { return this == CACTUS || this == SUGAR_CANE; }
        public boolean needsCenterWater() {
            return this == CARROT || this == POTATO || this == WHEAT || this == MELON || this == PUMPKIN;
        }
        public Material produceBlock() {
            return this == MELON ? Material.MELON : this == PUMPKIN ? Material.PUMPKIN : null;
        }
    }
}
