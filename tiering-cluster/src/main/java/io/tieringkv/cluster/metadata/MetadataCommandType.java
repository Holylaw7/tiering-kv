package io.tieringkv.cluster.metadata;

/** 元数据命令类型（ADR-0047）。 */
public enum MetadataCommandType {
    JOIN(1),
    LEAVE(2),
    CREATE_SHARD(3),
    UPDATE_LEADER(4),
    ASSIGN_SLOTS(5),
    MIGRATION_STATUS(6);

    private final int wireValue;

    MetadataCommandType(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static MetadataCommandType fromWire(int value) {
        for (MetadataCommandType type : values()) {
            if (type.wireValue == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown metadata command " + value);
    }
}
