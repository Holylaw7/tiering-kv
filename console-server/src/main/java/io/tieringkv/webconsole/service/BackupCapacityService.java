package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupCapacity;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupReservation;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.repository.BackupCapacityRepository;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.BackupReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 备份容量保护（ADR-0369）。
 *
 * <p>两层判定缺一不可：
 * <ul>
 *   <li>数据库预留：用悲观锁串行化同一存储路径的判定，避免两个实例重复使用同一份剩余空间；</li>
 *   <li>文件系统查询：读取真实可用空间，避免数据库里的估算与磁盘脱节。</li>
 * </ul>
 */
@Service
public class BackupCapacityService {

    private static final Logger log = LoggerFactory.getLogger(BackupCapacityService.class);

    /** 预留有效期：超时视为进程崩溃遗留，由下一轮预留回收。 */
    private static final long RESERVATION_TTL_MINUTES = 30;

    private final BackupReservationRepository reservationRepository;
    private final BackupCapacityRepository capacityRepository;
    private final BackupRecordRepository recordRepository;
    private final ConsoleProperties properties;
    private final Path directory;

    public BackupCapacityService(BackupReservationRepository reservationRepository,
                                 BackupCapacityRepository capacityRepository,
                                 BackupRecordRepository recordRepository,
                                 ConsoleProperties properties) {
        this.reservationRepository = reservationRepository;
        this.capacityRepository = capacityRepository;
        this.recordRepository = recordRepository;
        this.properties = properties;
        this.directory = Paths.get(properties.getBackup().getDir()).toAbsolutePath().normalize();
    }

    public record CapacityView(long usableBytes, long reservedBytes, long minFreeBytes,
                               long estimateFloorBytes, int activeReservations, String storageKey) {
    }

    /** 当前容量视图，供 GET /backups/capacity 使用。 */
    public CapacityView view() {
        ConsoleProperties.Capacity c = properties.getBackup().getCapacity();
        return new CapacityView(usableBytes(), reservationRepository.sumAllReserved(),
                c.getMinFreeBytes(), c.getEstimateFloorBytes(),
                reservationRepository.findByStatusAndExpiresAtBefore("RESERVED",
                        LocalDateTime.now().plusYears(100)).size(),
                directory.toString());
    }

    private long usableBytes() {
        try {
            Files.createDirectories(directory);
            FileStore store = Files.getFileStore(directory);
            return store.getUsableSpace();
        } catch (IOException ex) {
            log.warn("读取备份目录可用空间失败：{}", directory, ex);
            return 0L;
        }
    }

    /**
     * 预检并预留容量；空间不足抛 507。
     *
     * <p>悲观锁锚点保证同一存储路径的"读可用空间 + 累加预留 + 插入预留"是一个串行区间，
     * 两个控制面实例不会同时通过判定。
     */
    @Transactional
    public BackupReservation reserve(Long instanceId, Long recordId, long estimateBytes) {
        ConsoleProperties.Capacity cfg = properties.getBackup().getCapacity();
        long estimate = Math.max(estimateBytes, cfg.getEstimateFloorBytes());
        String key = directory.toString();

        reservationRepository.expireStale(LocalDateTime.now());

        BackupCapacity anchor = capacityRepository.lockByKey(key).orElseGet(() -> {
            BackupCapacity created = new BackupCapacity();
            created.setStorageKey(key);
            created.setUpdatedAt(LocalDateTime.now());
            return capacityRepository.saveAndFlush(created);
        });
        anchor.setUpdatedAt(LocalDateTime.now());
        capacityRepository.save(anchor);

        long usable = usableBytes();
        long reserved = reservationRepository.sumReserved(key);
        long available = usable - reserved - cfg.getMinFreeBytes();
        if (available < estimate) {
            throw BusinessException.insufficientStorage(String.format(
                    "备份空间不足：可用 %d MB，已预留 %d MB，需保留 %d MB，本次需要 %d MB",
                    usable / 1024 / 1024, reserved / 1024 / 1024,
                    cfg.getMinFreeBytes() / 1024 / 1024, estimate / 1024 / 1024));
        }

        BackupReservation r = new BackupReservation();
        r.setInstanceId(instanceId);
        r.setBackupRecordId(recordId);
        r.setStorageKey(key);
        r.setReservedBytes(estimate);
        r.setUsedBytes(0);
        r.setStatus("RESERVED");
        r.setCreatedAt(LocalDateTime.now());
        r.setExpiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES));
        return reservationRepository.save(r);
    }

    /** 释放预留；usedBytes 为真实写入字节数（失败时可为 0）。幂等。 */
    @Transactional
    public void release(Long recordId, long usedBytes) {
        reservationRepository.findFirstByBackupRecordIdAndStatusOrderByIdDesc(recordId, "RESERVED")
                .ifPresent(r -> {
                    r.setStatus("RELEASED");
                    r.setUsedBytes(usedBytes);
                    reservationRepository.save(r);
                });
    }

    /** 估算：优先用该实例最近一次成功备份的真实大小 × 1.25，否则用配置下限。 */
    public long estimate(Long instanceId) {
        ConsoleProperties.Capacity cfg = properties.getBackup().getCapacity();
        return recordRepository.findFirstByInstanceIdAndStatusOrderByFinishedAtDesc(
                        instanceId, BackupStatus.SUCCESS)
                .map(BackupRecord::getSizeBytes)
                .filter(size -> size > 0)
                .map(size -> (long) (size * cfg.getEstimateFactor()))
                .orElse(cfg.getEstimateFloorBytes());
    }
}
