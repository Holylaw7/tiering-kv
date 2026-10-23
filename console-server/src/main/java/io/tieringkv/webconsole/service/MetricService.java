package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.TrendPoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 指标读写服务。 */
@Service
public class MetricService {

    private final MetricPointRepository repository;

    public MetricService(MetricPointRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MetricPoint save(Long instanceId, String infoText, LocalDateTime at) {
        return repository.save(collect(instanceId, infoText, at));
    }

    public MetricPoint collect(Long instanceId, String infoText, LocalDateTime at) {
        Map<String, Map<String, String>> sections = InfoParser.parse(infoText);
        return MetricMapper.toPoint(instanceId, sections, at);
    }

    public Optional<MetricPoint> latest(Long instanceId) {
        return repository.findFirstByInstanceIdOrderByCollectedAtDesc(instanceId);
    }

    public List<TrendPoint> trends(Long instanceId, int minutes) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusMinutes(Math.max(1, Math.min(24 * 60, minutes)));
        List<MetricPoint> points = repository
                .findByInstanceIdAndCollectedAtBetweenOrderByCollectedAtAsc(instanceId, from, to);
        List<TrendPoint> result = new ArrayList<>(points.size());
        for (MetricPoint point : points) {
            result.add(new TrendPoint(point.getCollectedAt().toString(),
                    value(point.getQps()), value(point.getAvgLatencyMs()),
                    intValue(point.getConnections()), longValue(point.getUsedMemory()),
                    longValue(point.getTotalKeys()), longValue(point.getErrors()),
                    longValue(point.getMaxMemory())));
        }
        return result;
    }

    public Optional<DbInstance> noop() {
        return Optional.empty();
    }

    private static double value(Double raw) {
        return raw == null ? 0 : raw;
    }

    private static long longValue(Long raw) {
        return raw == null ? 0 : raw;
    }

    private static int intValue(Integer raw) {
        return raw == null ? 0 : raw;
    }
}
