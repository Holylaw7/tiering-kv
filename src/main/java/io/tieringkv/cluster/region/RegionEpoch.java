package io.tieringkv.cluster.region;

/**
 * Region 纪元（ADR-0057）：confVer 记录成员/leader 变更，
 * version 记录范围变更（split/merge），用于拒绝旧路由写入。
 */
public record RegionEpoch(int confVer, int version) {

    public static final RegionEpoch INITIAL = new RegionEpoch(1, 1);

    public RegionEpoch {
        if (confVer < 1 || version < 1) {
            throw new IllegalArgumentException(
                    "epoch must start at 1: confVer=" + confVer + " version=" + version);
        }
    }

    public RegionEpoch advanceVersion() {
        return new RegionEpoch(confVer, version + 1);
    }

    public RegionEpoch advanceConfVer() {
        return new RegionEpoch(confVer + 1, version);
    }

    /** 本纪元是否严格旧于 current（旧路由携带的纪元应被拒绝）。 */
    public boolean olderThan(RegionEpoch current) {
        return confVer < current.confVer()
                || (confVer == current.confVer() && version < current.version());
    }
}
