package io.tieringkv.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 地域亲和写路由（ADR-0141）：key hash → 首选地域。 */
public final class RegionAffinityRouter {

    private final List<String> regions;

    public RegionAffinityRouter(List<String> regions) {
        this.regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("regions required");
        }
    }

    public String route(byte[] key) {
        String text = new String(key, StandardCharsets.UTF_8);
        return regions.get(Math.floorMod(text.hashCode(),
                regions.size()));
    }
}
