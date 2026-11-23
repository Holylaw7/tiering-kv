package io.tieringkv.dr;

/** 容灾角色（ADR-0115）：主 / 备 / 仲裁只读。 */
public enum DrRole {
    PRIMARY,
    SECONDARY,
    OBSERVER
}
