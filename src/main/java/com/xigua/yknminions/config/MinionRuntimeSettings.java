package com.xigua.yknminions.config;

import java.util.List;
import java.util.Optional;

/** Read-only settings needed by a running minion, kept narrow for deterministic simulation tests. */
public interface MinionRuntimeSettings {
    Optional<MinionType> minionType(String id);
    List<FuelType> fuels();
    Optional<FuelType> fuel(String id);
    int storageSlots();
    int storageSlots(int level);
    List<SpreadSettings> spreads();
}
