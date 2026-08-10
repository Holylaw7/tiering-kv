package io.tieringkv.cluster.region;

/** 旧纪元请求（ADR-0057）：路由/写入携带的 epoch 已过期。 */
public final class StaleRegionEpochException extends RuntimeException {

    public StaleRegionEpochException(RegionId regionId,
                                     RegionEpoch expected,
                                     RegionEpoch actual) {
        super("stale epoch for region " + regionId
                + ": request=" + expected + " current=" + actual);
    }
}
