package io.tieringkv.security;

/** 权限域（ADR-0106）：READ / WRITE / ADMIN / BACKUP / CDC。 */
public enum Permission {
    READ,
    WRITE,
    ADMIN,
    BACKUP,
    CDC
}
