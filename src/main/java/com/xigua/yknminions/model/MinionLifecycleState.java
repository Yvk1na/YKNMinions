package com.xigua.yknminions.model;

/** Runtime state of a minion relative to its world and offline-production cursor. */
public enum MinionLifecycleState {
    ACTIVE,
    SUSPENDED,
    CATCHING_UP
}
