package com.xigua.yknminions.config;

import java.util.Locale;

public record CollectionSettings(String provider, String id) {
    public CollectionSettings {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("collection provider must not be blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("collection id must not be blank");
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        id = id.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9._-]+(?::[a-z0-9/._-]+)?")) {
            throw new IllegalArgumentException("collection id has invalid characters");
        }
    }
}
