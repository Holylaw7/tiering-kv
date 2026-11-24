package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.AlertChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertChannelRepository extends JpaRepository<AlertChannel, Long> {

    List<AlertChannel> findAllByOrderByIdAsc();

    List<AlertChannel> findByEnabledTrueOrderByIdAsc();

    Optional<AlertChannel> findByName(String name);
}
