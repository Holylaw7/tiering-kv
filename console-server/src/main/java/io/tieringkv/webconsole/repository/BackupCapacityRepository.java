package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupCapacity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 容量锁锚点仓储：用悲观写锁串行化同一存储路径的预留判定。 */
public interface BackupCapacityRepository extends JpaRepository<BackupCapacity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BackupCapacity c where c.storageKey = :key")
    Optional<BackupCapacity> lockByKey(@Param("key") String key);
}
