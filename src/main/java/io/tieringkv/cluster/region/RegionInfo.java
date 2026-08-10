package io.tieringkv.cluster.region;

/** INFO REGIONS（ADR-0056/0060）：region / leader / epoch / size。 */
public final class RegionInfo {

    private final RegionManager regions;
    private final RegionMetricsRegistry metrics;

    public RegionInfo(RegionManager regions, RegionMetricsRegistry metrics) {
        this.regions = regions;
        this.metrics = metrics;
    }

    public String sectionText() {
        StringBuilder builder = new StringBuilder("# Regions\r\n");
        for (Region region : regions.listRegions()) {
            builder.append("region:").append(region.regionId().id())
                    .append(':').append(region.leader() == null ? "" : region.leader())
                    .append(':').append(region.epoch().confVer())
                    .append('.').append(region.epoch().version())
                    .append(':').append(regions.regionSize(region.regionId()))
                    .append(':').append(region.state())
                    .append("\r\n");
        }
        builder.append(metrics.metricLines());
        return builder.toString();
    }
}
