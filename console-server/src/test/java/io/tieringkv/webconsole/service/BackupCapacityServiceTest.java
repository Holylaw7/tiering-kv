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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G0-4 / ADR-0369：容量预检与预留。
 *
 * <p>这些用例只覆盖协调逻辑与拒绝语义；真实磁盘耗尽的验证在 E2E 中单独记录，
 * 不能用 Mock 抛 IOException 冒充。
 */
class BackupCapacityServiceTest {

    @TempDir
    Path dir;

    private final List<BackupReservation> reservations = new ArrayList<>();
    private BackupReservationRepository reservationRepository;
    private BackupRecordRepository recordRepository;
    private ConsoleProperties properties;
    private BackupCapacityService service;
    private long used = 0;

    @BeforeEach
    void setUp() {
        reservationRepository = mock(BackupReservationRepository.class);
        BackupCapacityRepository capacityRepository = mock(BackupCapacityRepository.class);
        recordRepository = mock(BackupRecordRepository.class);
        properties = new ConsoleProperties();
        properties.getBackup().setDir(dir.toString());
        properties.getBackup().getCapacity().setMinFreeBytes(0);
        properties.getBackup().getCapacity().setEstimateFloorBytes(1024);

        when(capacityRepository.lockByKey(anyString())).thenReturn(Optional.empty());
        when(capacityRepository.saveAndFlush(any(BackupCapacity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(capacityRepository.save(any(BackupCapacity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepository.sumReserved(anyString())).thenAnswer(inv -> reservations.stream()
                .filter(r -> "RESERVED".equals(r.getStatus()))
                .mapToLong(BackupReservation::getReservedBytes).sum());
        when(reservationRepository.sumAllReserved()).thenAnswer(inv -> reservations.stream()
                .filter(r -> "RESERVED".equals(r.getStatus()))
                .mapToLong(BackupReservation::getReservedBytes).sum());
        when(reservationRepository.save(any(BackupReservation.class))).thenAnswer(inv -> {
            BackupReservation r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId((long) (reservations.size() + 1));
            }
            reservations.add(r);
            return r;
        });
        when(reservationRepository.findFirstByBackupRecordIdAndStatusOrderByIdDesc(anyLong(), anyString()))
                .thenAnswer(inv -> reservations.stream()
                        .filter(r -> inv.getArgument(0).equals(r.getBackupRecordId()))
                        .filter(r -> inv.getArgument(1).equals(r.getStatus()))
                        .findFirst());
        when(reservationRepository.findByStatusAndExpiresAtBefore(anyString(), any())).thenReturn(List.of());

        service = new BackupCapacityService(reservationRepository, capacityRepository,
                recordRepository, properties);
    }

    @Test
    void 空间充足时预留成功() {
        BackupReservation r = service.reserve(1L, 10L, 1024);
        assertThat(r.getStatus()).isEqualTo("RESERVED");
        assertThat(r.getReservedBytes()).isEqualTo(1024);
    }

    @Test
    void 保留余量导致空间不足时拒绝并给出原因() {
        properties.getBackup().getCapacity().setMinFreeBytes(Long.MAX_VALUE / 4);
        assertThatThrownBy(() -> service.reserve(1L, 10L, 1024))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("备份空间不足");
        BusinessException ex = (BusinessException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.reserve(1L, 11L, 1024));
        assertThat(ex.getStatus()).isEqualTo(507);
    }

    @Test
    void 已有预留会占用可用空间() {
        service.reserve(1L, 10L, 1024);
        properties.getBackup().getCapacity().setEstimateFloorBytes(1024);
        // 预留后再请求一个极大值，必然超出可用空间
        assertThatThrownBy(() -> service.reserve(1L, 11L, Long.MAX_VALUE / 4))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("备份空间不足");
    }

    @Test
    void 释放预留后不再计入占用() {
        service.reserve(1L, 10L, 1024);
        service.release(10L, 512);
        assertThat(reservations.get(0).getStatus()).isEqualTo("RELEASED");
        assertThat(reservations.get(0).getUsedBytes()).isEqualTo(512);
        assertThat(service.view().reservedBytes()).isEqualTo(0);
    }

    @Test
    void 估算优先使用最近成功备份大小并乘系数() {
        BackupRecord last = new BackupRecord();
        last.setSizeBytes(1000);
        last.setStatus(BackupStatus.SUCCESS);
        when(recordRepository.findFirstByInstanceIdAndStatusOrderByFinishedAtDesc(anyLong(), any()))
                .thenReturn(Optional.of(last));
        properties.getBackup().getCapacity().setEstimateFactor(1.25);
        assertThat(service.estimate(1L)).isEqualTo(1250);
    }

    @Test
    void 没有历史备份时使用估算下限() {
        when(recordRepository.findFirstByInstanceIdAndStatusOrderByFinishedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        properties.getBackup().getCapacity().setEstimateFloorBytes(4096);
        assertThat(service.estimate(1L)).isEqualTo(4096);
    }

    @Test
    void 容量视图包含可用空间与预留总量() {
        service.reserve(1L, 10L, 2048);
        var view = service.view();
        assertThat(view.reservedBytes()).isEqualTo(2048);
        assertThat(view.usableBytes()).isGreaterThan(0);
        assertThat(view.storageKey()).isEqualTo(dir.toString());
    }
}
