package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 告警规则仓储。 */
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findAllByOrderByIdAsc();

    List<AlertRule> findByEnabledTrue();

    boolean existsByName(String name);
}
