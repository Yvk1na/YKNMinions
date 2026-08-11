package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;
import org.bukkit.Color;

public record ModelSettings(ItemSpec helmet, ItemSpec chestplate, ItemSpec leggings,
                            ItemSpec boots, ItemSpec tool, Color leatherColor) {
}
