package io.tieringkv.compliance;

import io.tieringkv.compliance.RegulationMapper.Control;

import java.util.Set;

/** 法规版本（ADR-0159）：生效时间 + 控制项快照。 */
public final class RegulationVersion {

    private RegulationVersion() {
    }

    /** 版本快照：法规 + 版本号 + 生效时间 + 控制项。 */
    public record Version(String regulation, String versionId,
                          long effectiveFromMillis,
                          Set<Control> controls) {

        public Version {
            if (regulation == null || regulation.isBlank()) {
                throw new IllegalArgumentException(
                        "regulation required");
            }
            if (versionId == null || versionId.isBlank()) {
                throw new IllegalArgumentException(
                        "versionId required");
            }
            if (effectiveFromMillis < 0) {
                throw new IllegalArgumentException(
                        "effective time must be non-negative");
            }
            controls = Set.copyOf(controls);
        }

        public boolean isEffective(long nowMillis) {
            return nowMillis >= effectiveFromMillis;
        }
    }
}
