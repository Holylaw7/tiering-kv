package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.DbInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 实例仓储。 */
public interface DbInstanceRepository extends JpaRepository<DbInstance, Long> {

    List<DbInstance> findAllByOrderByIdAsc();

    List<DbInstance> findByEnabledTrueOrderByIdAsc();

    Optional<DbInstance> findByName(String name);

    boolean existsByHostAndPort(String host, int port);
}
