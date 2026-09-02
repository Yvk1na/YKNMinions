package com.xigua.yknminions.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PreparedClaim(UUID id, UUID recipientId, String operationId,
                            List<PreparedClaimLine> lines) {
    public PreparedClaim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipientId, "recipientId");
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("lines");
        lines = List.copyOf(lines);
    }

    public List<org.bukkit.inventory.ItemStack> items() {
        return lines.stream().map(PreparedClaimLine::item).toList();
    }
}
