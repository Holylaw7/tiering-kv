package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.TerminalHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 终端历史仓储。 */
public interface TerminalHistoryRepository extends JpaRepository<TerminalHistory, Long> {

    Page<TerminalHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<TerminalHistory> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);
}
