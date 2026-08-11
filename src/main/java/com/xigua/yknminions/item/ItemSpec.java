package com.xigua.yknminions.item;

import java.util.Locale;
import java.util.Objects;

public record ItemSpec(String descriptor) {
    public ItemSpec {
        descriptor = normalize(Objects.requireNonNull(descriptor, "descriptor"));
    }

    private static String normalize(String input) {
        String value = input.trim();
        int first = value.indexOf(':');
        if (first < 0) return "minecraft:" + value.toLowerCase(Locale.ROOT);
        String prefix = value.substring(0, first).toLowerCase(Locale.ROOT);
        String rest = value.substring(first + 1);
        if (prefix.equals("mmoitems")) return prefix + ":" + rest.toUpperCase(Locale.ROOT);
        return prefix + ":" + rest.toLowerCase(Locale.ROOT);
    }

    public String provider() {
        return descriptor.substring(0, descriptor.indexOf(':'));
    }

    public String value() {
        return descriptor.substring(descriptor.indexOf(':') + 1);
    }

    @Override
    public String toString() {
        return descriptor;
    }
}
