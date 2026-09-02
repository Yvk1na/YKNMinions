package com.xigua.yknminions.model;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

/** Persistent position which deliberately does not retain a Bukkit World instance. */
public record MinionPosition(String worldName, double x, double y, double z, float yaw) {
    public MinionPosition {
        worldName = Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) throw new IllegalArgumentException("worldName");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw)) {
            throw new IllegalArgumentException("non-finite minion position");
        }
    }

    public static MinionPosition from(Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location.world");
        return new MinionPosition(world.getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw());
    }

    public Location bind(World world) {
        Objects.requireNonNull(world, "world");
        if (!worldName.equals(world.getName())) {
            throw new IllegalArgumentException("world name mismatch: " + world.getName());
        }
        return new Location(world, x, y, z, yaw, 0.0f);
    }

    public int chunkX() { return ((int) Math.floor(x)) >> 4; }
    public int chunkZ() { return ((int) Math.floor(z)) >> 4; }

    public boolean isChunk(String candidateWorld, int candidateX, int candidateZ) {
        return worldName.equals(candidateWorld) && chunkX() == candidateX && chunkZ() == candidateZ;
    }

    public double distanceSquared(Location other) {
        if (other == null || other.getWorld() == null
                || !worldName.equals(other.getWorld().getName())) return Double.POSITIVE_INFINITY;
        double dx = x - other.getX();
        double dy = y - other.getY();
        double dz = z - other.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
