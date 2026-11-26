package io.tieringkv.webconsole.service;

import tools.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G0-1 / ADR-0366：恢复互斥与状态机。
 *
 * <p>每个用例对应一条不变量，而不是"覆盖率凑数"：
 * 并发唯一执行权、恢复与备份互斥、失败不污染备份状态、失败后可重试、僵尸状态可回收。
 */
class BackupServiceRestoreTest {

    @TempDir
    Path dir;
    private final BackupCapacityService capacity = mock(BackupCapacityService.class);


    private final Map<Long, BackupRecord> store = new ConcurrentHashMap<>();
    private BackupRecordRepository repository;
    private RespGateway gateway;
    private BackupService service;
    private DbInstance instance;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(BackupRecordRepository.class);
        gateway = mock(RespGateway.class);
        when(gateway.executeBatch(any(), any())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            List<RespReply> replies = new java.util.ArrayList<>(cmds.size());
            for (int i = 0; i < cmds.size(); i++) {
                replies.add(new RespReply.Simple("OK"));
            }
            return replies;
        });
        instance = new DbInstance();
        instance.setName("local");

        when(repository.save(any(BackupRecord.class))).thenAnswer(inv -> {
            BackupRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId((long) (store.size() + 1));
            }
            store.put(r.getId(), r);
            return r;
        });
        when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repository.findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(any(), any()))
                .thenAnswer(inv -> store.values().stream()
                        .filter(r -> r.getRestoreStatus() == inv.getArgument(1))
                        .findFirst());
        when(repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(any(), any()))
                .thenAnswer(inv -> store.values().stream()
                        .filter(r -> r.getStatus() == inv.getArgument(1))
                        .findFirst());

        InstanceService instanceService = mock(InstanceService.class);
        when(instanceService.require(any())).thenReturn(instance);
        KeyspaceService keyspace = mock(KeyspaceService.class);
        AuditService audit = mock(AuditService.class);
        ConsoleProperties properties = new ConsoleProperties();
        properties.getBackup().setDir(dir.toString());
        service = new BackupService(repository, instanceService, keyspace, gateway,
                new ObjectMapper(), properties, Runnable::run, audit, capacity);
    }

    /** 造一份可恢复的备份（两行 JSONL）与对应记录。 */
    private BackupRecord backupWith(String... lines) throws Exception {
        BackupRecord record = new BackupRecord();
        record.setId(100L);
        record.setInstanceId(1L);
        record.setFileName("backup-1-test.jsonl");
        record.setStatus(BackupStatus.SUCCESS);
        record.setKeyCount(lines.length);
        record.setCreatedAt(LocalDateTime.now());
        record.setFinishedAt(LocalDateTime.now());
        Files.write(dir.resolve(record.getFileName()),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        store.put(record.getId(), record);
        return record;
    }

    private static String entry(String key, String value) {
        return "{\"key\":\"" + key + "\",\"type\":\"string\",\"ttl\":0,\"value\":\"" + value + "\"}";
    }

    @Test
    void 两个并发恢复只有一个获得执行权() throws Exception {
        BackupRecord record = backupWith(entry("k1", "v1"), entry("k2", "v2"));
        CountDownLatch inReplay = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 第一次 restore 在管线批回放时阻塞，制造"恢复进行中"的窗口
        doAnswer(inv -> {
            inReplay.countDown();
            release.await(3, TimeUnit.SECONDS);
            List<?> cmds = inv.getArgument(1);
            List<RespReply> replies = new java.util.ArrayList<>(cmds.size());
            for (int i = 0; i < cmds.size(); i++) {
                replies.add(new RespReply.Simple("OK"));
            }
            return replies;
        }).when(gateway).executeBatch(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        Future<?> first = pool.submit(() -> {
            try { service.restore(record.getId()); ok.incrementAndGet(); }
            catch (BusinessException ex) {
                if (ex.getStatus() == 409) conflict.incrementAndGet(); else errors.add(ex);
            } catch (Throwable t) { errors.add(t); }
        });
        assertThat(inReplay.await(3, TimeUnit.SECONDS)).isTrue();

        Future<?> second = pool.submit(() -> {
            try { service.restore(record.getId()); ok.incrementAndGet(); }
            catch (BusinessException ex) {
                if (ex.getStatus() == 409) conflict.incrementAndGet(); else errors.add(ex);
            } catch (Throwable t) { errors.add(t); }
        });
        second.get(5, TimeUnit.SECONDS);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(errors).as("非预期异常：%s", errors).isEmpty();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
    }

    @Test
    void 备份进行中拒绝恢复() throws Exception {
        BackupRecord record = backupWith(entry("k1", "v1"));
        BackupRecord running = new BackupRecord();
        running.setId(200L);
        running.setInstanceId(1L);
        running.setStatus(BackupStatus.RUNNING);
        running.setCreatedAt(LocalDateTime.now());
        store.put(running.getId(), running);

        assertThatThrownBy(() -> service.restore(record.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在备份");
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.NONE);
    }

    @Test
    void 恢复进行中拒绝导出() throws Exception {
        BackupRecord restoring = backupWith(entry("k1", "v1"));
        restoring.setRestoreStatus(RestoreStatus.RESTORING);
        restoring.setRestoreStartedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.export(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在恢复");
    }

    @Test
    void 恢复失败不污染备份状态且记录已回放数() throws Exception {
        BackupRecord record = backupWith(entry("k1", "v1"), "{损坏的行}");

        BackupRecord submitted = service.restore(record.getId());

        assertThat(submitted.getId()).isEqualTo(record.getId());
        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
        assertThat(record.getRestoredKeys()).isEqualTo(1);
        assertThat(record.getRestoreMessage()).contains("已回放 1 个键");
        assertThat(record.getRestoreFinishedAt()).isNotNull();
    }

    @Test
    void 失败后可以重试并成功() throws Exception {
        BackupRecord record = backupWith("{损坏的行}");
        service.restore(record.getId());
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);

        Files.write(dir.resolve(record.getFileName()),
                String.join("\n", entry("k1", "v1"), entry("k2", "v2")).getBytes(StandardCharsets.UTF_8));
        BackupRecord retried = service.restore(record.getId());

        assertThat(retried.getId()).isEqualTo(record.getId());
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);
        assertThat(record.getRestoredKeys()).isEqualTo(2);
        assertThat(record.getRestoreMessage()).isNull();
    }

    @Test
    void 僵尸恢复状态可被回收并允许重新恢复() throws Exception {
        BackupRecord record = backupWith(entry("k1", "v1"));
        BackupRecord zombie = new BackupRecord();
        zombie.setId(300L);
        zombie.setInstanceId(1L);
        zombie.setStatus(BackupStatus.SUCCESS);
        zombie.setRestoreStatus(RestoreStatus.RESTORING);
        zombie.setRestoreStartedAt(LocalDateTime.now().minusMinutes(45));
        zombie.setCreatedAt(LocalDateTime.now().minusHours(1));
        store.put(zombie.getId(), zombie);

        BackupRecord result = service.restore(record.getId());

        assertThat(result.getId()).isEqualTo(record.getId());
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);
        assertThat(record.getRestoredKeys()).isEqualTo(1);
        assertThat(zombie.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
        assertThat(zombie.getRestoreMessage()).contains("超时");
    }

    @Test
    void 恢复异步提交立即返回RESTORING并在执行器中完成() throws Exception {
        BackupRecord record = backupWith(entry("k1", "v1"), entry("k2", "v2"));
        List<Runnable> queued = new java.util.ArrayList<>();
        InstanceService instanceService = mock(InstanceService.class);
        when(instanceService.require(any())).thenReturn(instance);
        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        BackupService asyncService = new BackupService(repository, instanceService,
                mock(KeyspaceService.class), gateway, new ObjectMapper(), props,
                queued::add, mock(AuditService.class), capacity);

        BackupRecord submitted = asyncService.restore(record.getId());

        assertThat(submitted.getRestoreStatus()).isEqualTo(RestoreStatus.RESTORING);
        assertThat(submitted.getRestoredKeys()).isEqualTo(0);
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);
        assertThat(record.getRestoredKeys()).isEqualTo(2);
    }

    @Test
    void 大批量回放按节流持久化中间恢复进度() throws Exception {
        String[] lines = new String[550];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = entry("k" + i, "v" + i);
        }
        BackupRecord record = backupWith(lines);
        List<Long> observedProgress = new java.util.ArrayList<>();
        when(repository.save(any(BackupRecord.class))).thenAnswer(inv -> {
            BackupRecord r = inv.getArgument(0);
            store.put(r.getId(), r);
            if (r.getRestoreStatus() == RestoreStatus.RESTORING && r.getRestoredKeys() > 0) {
                observedProgress.add(r.getRestoredKeys());
            }
            return r;
        });

        service.restore(record.getId());

        assertThat(observedProgress).contains(500L);
        assertThat(record.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);
        assertThat(record.getRestoredKeys()).isEqualTo(550L);
    }
}