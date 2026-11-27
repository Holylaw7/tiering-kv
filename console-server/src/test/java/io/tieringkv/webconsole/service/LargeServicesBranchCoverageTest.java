package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupSchedule;
import io.tieringkv.webconsole.domain.BackupScheduleMode;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.OpLog;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.BackupScheduleRepository;
import io.tieringkv.webconsole.repository.ConsoleLeaseRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.BackupScheduleRequest;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonArrAppendRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonNumIncrRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonSetRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesIncrByRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 2B（T2）：大类核心分支覆盖强化测试（MultiModelService & BackupService 异常与边界全覆盖）。
 */
class LargeServicesBranchCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 1. MultiModelService ====================

    @Test
    void multiModelServiceCoversVectorTimeSeriesJsonErrorAndEdgeBranches() throws Exception {
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        MultiModelService svc = new MultiModelService(instSvc, gateway, audit, MAPPER);

        DbInstance inst = sampleInstance(1L, "mm-node");
        when(instSvc.require(1L)).thenReturn(inst);

        // ---- Vector ----
        when(gateway.execute(inst, "VECTOR.LIST")).thenReturn(new RespReply.Error("ERR unknown command 'VECTOR.LIST'"));
        assertThat(svc.listVectorCollections(1L).supported()).isFalse();

        when(gateway.execute(inst, "VECTOR.LIST")).thenReturn(new RespReply.Error("ERR denied"));
        assertThatThrownBy(() -> svc.listVectorCollections(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("读取向量集合失败");

        doThrow(new IOException("reset")).when(gateway).execute(inst, "VECTOR.LIST");
        assertThatThrownBy(() -> svc.listVectorCollections(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("通信失败");

        doReturn(new RespReply.Array(List.of(
                new RespReply.Simple("bad"),
                new RespReply.Array(List.of(new RespReply.Simple("only-one"))),
                new RespReply.Array(List.of(new RespReply.Bulk(null), new RespReply.Integer(3))),
                new RespReply.Array(List.of(new RespReply.Simple("c1"), new RespReply.Simple("not-a-num"))),
                new RespReply.Array(List.of(new RespReply.Bulk("c2".getBytes(StandardCharsets.UTF_8)), new RespReply.Integer(5)))
        ))).when(gateway).execute(inst, "VECTOR.LIST");
        var cols = svc.listVectorCollections(1L);
        assertThat(cols.supported()).isTrue();
        assertThat(cols.collections()).hasSize(2);
        assertThat(cols.totalVectors()).isEqualTo(5L);

        // addVector: NaN / null element / default vs custom / unknown command error
        assertThatThrownBy(() -> svc.addVector(new VectorAddRequest(1L, null, "v1", List.of(Double.NaN))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("有限实数");
        assertThatThrownBy(() -> svc.addVector(new VectorAddRequest(1L, null, "v1", Arrays.asList(1.0, null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("有限实数");

        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Error("unsupported vector"));
        assertThatThrownBy(() -> svc.addVector(new VectorAddRequest(1L, " ", "v1", List.of(1.0))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不支持该多模型命令");

        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Simple("OK"));
        svc.addVector(new VectorAddRequest(1L, "custom", "v1", List.of(1.0, 2.0)));

        // searchVector: unknown / custom + null topK + mixed hits
        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Error("ERR unknown command"));
        assertThat(svc.searchVector(new VectorSearchRequest(1L, null, List.of(1.0), null)).supported()).isFalse();

        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Array(List.of(
                new RespReply.Simple("skip"),
                new RespReply.Array(List.of(new RespReply.Bulk(null), new RespReply.Simple("0.9"))),
                new RespReply.Array(List.of(new RespReply.Simple("h1"), new RespReply.Simple("not-double"))),
                new RespReply.Array(List.of(new RespReply.Simple("h2"), new RespReply.Integer(1)))
        )));
        var sr = svc.searchVector(new VectorSearchRequest(1L, "custom", List.of(1.0), 5));
        assertThat(sr.hits()).hasSize(2);
        assertThat(sr.hits().get(0).score()).isZero();
        assertThat(sr.hits().get(1).score()).isEqualTo(1.0);

        // deleteVector / dropVectorCollection / checkpointVector
        assertThatThrownBy(() -> svc.deleteVector(1L, null, "  ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.dropVectorCollection(1L, "  ")).isInstanceOf(BusinessException.class);
        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Integer(0));
        assertThat(svc.deleteVector(1L, "default", "v1")).isFalse();
        assertThat(svc.deleteVector(1L, "c2", "v1")).isFalse();
        assertThat(svc.dropVectorCollection(1L, "c2")).isFalse();
        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Array(null));
        assertThat(svc.checkpointVector(1L, null)).isEqualTo("OK");
        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Simple("SAVED"));
        assertThat(svc.checkpointVector(1L, "c2")).isEqualTo("SAVED");

        // ---- TimeSeries ----
        when(gateway.execute(inst, "TS.MRANGE", "-inf", "+inf"))
                .thenReturn(new RespReply.Error("ERR unknown command"));
        assertThat(svc.listTimeSeries(1L).supported()).isFalse();

        when(gateway.execute(inst, "TS.MRANGE", "-inf", "+inf")).thenReturn(new RespReply.Array(List.of(
                new RespReply.Simple("bad"),
                new RespReply.Array(List.of(new RespReply.Simple("ts:empty"), new RespReply.Array(List.of()))),
                new RespReply.Array(List.of(new RespReply.Simple("ts:ok"), new RespReply.Array(List.of(
                        new RespReply.Simple("bad-pt"),
                        new RespReply.Array(List.of(new RespReply.Simple("nan-ts"), new RespReply.Simple("1.0"))),
                        new RespReply.Array(List.of(new RespReply.Integer(1000L), new RespReply.Simple("2.5")))
                ))))
        )));
        var tsList = svc.listTimeSeries(1L);
        assertThat(tsList.series()).hasSize(2);
        assertThat(tsList.series().get(0).latestTimestamp()).isNull();
        assertThat(tsList.series().get(1).latestValue()).isEqualTo(2.5);

        // add / incr validation & null vs explicit ts
        assertThatThrownBy(() -> svc.addTimeSeriesPoint(new TimeSeriesAddRequest(1L, "k", null, Double.POSITIVE_INFINITY)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.incrTimeSeries(new TimeSeriesIncrByRequest(1L, "k", Double.NaN, null)))
                .isInstanceOf(BusinessException.class);
        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Simple("OK"));
        assertThat(svc.addTimeSeriesPoint(new TimeSeriesAddRequest(1L, "k", null, 3.0)).value()).isEqualTo(3.0);
        assertThat(svc.incrTimeSeries(new TimeSeriesIncrByRequest(1L, "k", 1.5, null))).isGreaterThan(0L);
        assertThat(svc.incrTimeSeries(new TimeSeriesIncrByRequest(1L, "k", 1.5, 5000L))).isEqualTo(5000L);

        // queryTimeSeriesRange: validation, unsupported, aggregation default bucket, TS.LEN & TS.REDUCE edge cases
        assertThatThrownBy(() -> svc.queryTimeSeriesRange(1L, " ", null, null, null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.queryTimeSeriesRange(1L, "k", null, null, "MEDIAN", null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("聚合函数");

        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Error("ERR unknown command"));
        assertThat(svc.queryTimeSeriesRange(1L, "k", null, null, "avg", 0L, 99999).supported()).isFalse();

        when(gateway.execute(eq(inst), eq("TS.RANGE"), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Array(null));
        doThrow(new IOException("len fail")).when(gateway).execute(inst, "TS.LEN", "k");
        when(gateway.execute(inst, "TS.REDUCE", "k", "AGGREGATION", "AVG"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Simple("AVG"), new RespReply.Simple("bad-num"))));
        var r1 = svc.queryTimeSeriesRange(1L, "k", "0", "100", "avg", -1L, 10);
        assertThat(r1.supported()).isTrue();
        assertThat(r1.bucketMs()).isEqualTo(60_000L);
        assertThat(r1.reducedValue()).isNull();

        doReturn(new RespReply.Integer(42)).when(gateway).execute(inst, "TS.LEN", "k");
        when(gateway.execute(inst, "TS.REDUCE", "k", "AGGREGATION", "SUM"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Simple("SUM"), new RespReply.Simple("99.5"))));
        var r2 = svc.queryTimeSeriesRange(1L, "k", null, null, "sum", 5000L, 50);
        assertThat(r2.totalLen()).isEqualTo(42L);
        assertThat(r2.reducedValue()).isEqualTo(99.5);

        // ---- JSON Path ----
        when(gateway.execute(inst, "JSON.TYPE", "__tk_json_probe__"))
                .thenReturn(new RespReply.Error("ERR unknown command"));
        assertThat(svc.listJsonKeys(1L, null, 1).supported()).isFalse();

        when(gateway.execute(inst, "JSON.TYPE", "__tk_json_probe__")).thenReturn(new RespReply.Bulk(null));
        when(gateway.execute(inst, "SCAN", "0", "MATCH", "*", "COUNT", "200"))
                .thenReturn(new RespReply.Simple("bad"));
        assertThat(svc.listJsonKeys(1L, " ", 999).keys()).isEmpty();

        when(gateway.execute(inst, "SCAN", "0", "MATCH", "j:*", "COUNT", "10"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Simple("0"), new RespReply.Array(List.of()))));
        assertThat(svc.listJsonKeys(1L, "j:*", 5).keys()).isEmpty();

        when(gateway.execute(inst, "SCAN", "0", "MATCH", "j:*", "COUNT", "10"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Simple("0"),
                        new RespReply.Array(List.of(new RespReply.Simple("j:1"), new RespReply.Simple("s:1"))))));
        doThrow(new IOException("batch io")).when(gateway).executeBatch(eq(inst), any());
        assertThatThrownBy(() -> svc.listJsonKeys(1L, "j:*", 10))
                .isInstanceOf(BusinessException.class).hasMessageContaining("探测 JSON 键类型失败");

        doReturn(List.of(new RespReply.Simple("json"), new RespReply.Simple("string")))
                .when(gateway).executeBatch(eq(inst), any());
        assertThat(svc.listJsonKeys(1L, "j:*", 10).keys()).containsExactly("j:1");

        // getJson: blank key / unknown / nil / object nested & flat OBJKEYS / prettyPrint fallback
        assertThatThrownBy(() -> svc.getJson(1L, " ", null)).isInstanceOf(BusinessException.class);
        when(gateway.execute(inst, "JSON.GET", "doc")).thenReturn(new RespReply.Error("ERR unknown command"));
        assertThat(svc.getJson(1L, "doc", null).supported()).isFalse();

        when(gateway.execute(inst, "JSON.GET", "doc")).thenReturn(new RespReply.Bulk(null));
        assertThat(svc.getJson(1L, "doc", "$").exists()).isFalse();

        when(gateway.execute(inst, "JSON.GET", "doc", "$.a"))
                .thenReturn(new RespReply.Bulk("{bad-json".getBytes(StandardCharsets.UTF_8)));
        when(gateway.execute(inst, "JSON.TYPE", "doc", "$.a"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Simple("object"))));
        when(gateway.execute(inst, "JSON.OBJKEYS", "doc", "$.a"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Array(List.of(new RespReply.Simple("k1"), new RespReply.Bulk(null))))));
        var jv = svc.getJson(1L, "doc", "$.a");
        assertThat(jv.exists()).isTrue();
        assertThat(jv.prettyJson()).isEqualTo("{bad-json");
        assertThat(jv.objectKeys()).containsExactly("k1");

        when(gateway.execute(inst, "JSON.OBJKEYS", "doc", "$.a"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Simple("flatKey"))));
        assertThat(svc.getJson(1L, "doc", "$.a").objectKeys()).containsExactly("flatKey");

        // setJson / deleteJson / numIncrJson / arrAppendJson
        assertThatThrownBy(() -> svc.setJson(new JsonSetRequest(1L, "doc", null, "{invalid")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("JSON 格式无效");
        assertThatThrownBy(() -> svc.setJson(new JsonSetRequest(1L, "doc", null, "   ")))
                .isInstanceOf(BusinessException.class);
        when(gateway.execute(inst, "JSON.SET", "doc", "$", "{\"a\":1}")).thenReturn(new RespReply.Simple("OK"));
        svc.setJson(new JsonSetRequest(1L, "doc", null, "{\"a\":1}"));

        assertThatThrownBy(() -> svc.deleteJson(1L, " ", null)).isInstanceOf(BusinessException.class);
        when(gateway.execute(inst, "JSON.DEL", "doc", "$")).thenReturn(new RespReply.Integer(1));
        assertThat(svc.deleteJson(1L, "doc", null)).isEqualTo(1L);

        assertThatThrownBy(() -> svc.numIncrJson(new JsonNumIncrRequest(1L, "doc", "$.a", Double.NaN)))
                .isInstanceOf(BusinessException.class);
        when(gateway.execute(inst, "JSON.NUMINCRBY", "doc", "$.a", "2.0")).thenReturn(new RespReply.Simple("[3]"));
        assertThat(svc.numIncrJson(new JsonNumIncrRequest(1L, "doc", "$.a", 2.0))).isEqualTo("[3]");

        when(gateway.execute(inst, "JSON.ARRAPPEND", "doc", "$", "1"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Integer(4))));
        assertThat(svc.arrAppendJson(new JsonArrAppendRequest(1L, "doc", null, "1"))).isEqualTo(4L);
        when(gateway.execute(inst, "JSON.ARRAPPEND", "doc", "$.arr", "2")).thenReturn(new RespReply.Integer(5));
        assertThat(svc.arrAppendJson(new JsonArrAppendRequest(1L, "doc", "$.arr", "2"))).isEqualTo(5L);
    }

    // ==================== 2. BackupService ====================

    @Test
    void backupServiceCoversExportRestoreVerifyPreviewDownloadAndCheckpointBranches(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        KeyspaceService ksSvc = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        BackupCapacityService capSvc = mock(BackupCapacityService.class);
        DbLeaseLockService leaseSvc = mock(DbLeaseLockService.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        props.getBackup().setCompress(false);
        LocalBackupStorageBackend storage = new LocalBackupStorageBackend(props);
        Executor direct = Runnable::run;

        BackupService svc = new BackupService(
                repo, instSvc, ksSvc, gateway, MAPPER, props, storage, direct, audit, capSvc, leaseSvc);
        svc.setMetricsBinder(binder);

        DbInstance inst = sampleInstance(1L, "bk-node");
        when(instSvc.require(1L)).thenReturn(inst);
        when(leaseSvc.tryAcquire(anyString(), any())).thenReturn(true);
        when(repo.save(any(BackupRecord.class))).thenAnswer(inv -> {
            BackupRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(100L);
            }
            return r;
        });

        // 1. export 冲突矩阵：跨副本锁失败 / 活跃 RESTORING / 活跃 RUNNING / 僵尸清理 + 配额预留失败
        when(leaseSvc.tryAcquire(anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("正在提交");

        when(leaseSvc.tryAcquire(anyString(), any())).thenReturn(true);
        BackupRecord freshRestoring = new BackupRecord();
        freshRestoring.setId(11L);
        freshRestoring.setRestoreStatus(RestoreStatus.RESTORING);
        freshRestoring.setRestoreStartedAt(LocalDateTime.now());
        when(repo.findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(1L, RestoreStatus.RESTORING))
                .thenReturn(Optional.of(freshRestoring));
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("正在恢复");

        // 僵尸 RESTORING + 活跃 RUNNING
        freshRestoring.setRestoreStartedAt(LocalDateTime.now().minusHours(2));
        BackupRecord freshRunning = new BackupRecord();
        freshRunning.setId(12L);
        freshRunning.setStatus(BackupStatus.RUNNING);
        freshRunning.setCreatedAt(LocalDateTime.now());
        when(repo.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(1L, BackupStatus.RUNNING))
                .thenReturn(Optional.of(freshRunning));
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已有备份任务");
        assertThat(freshRestoring.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);

        // 僵尸 RUNNING + capacityService.reserve 失败
        freshRunning.setCreatedAt(LocalDateTime.now().minusHours(3));
        doThrow(BusinessException.badRequest("磁盘空间不足")).when(capSvc).reserve(eq(1L), anyLong(), anyLong());
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("磁盘空间不足");
        assertThat(freshRunning.getStatus()).isEqualTo(BackupStatus.FAILED);

        // 2. runExport: 不存在记录 / SCAN 失败 / maxKeys 超限触发 FAILED
        when(repo.findById(999L)).thenReturn(Optional.empty());
        svc.runExport(999L);

        BackupRecord rec = new BackupRecord();
        rec.setId(20L);
        rec.setInstanceId(1L);
        rec.setFileName("exp-fail.jsonl");
        when(repo.findById(20L)).thenReturn(Optional.of(rec));
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Error("ERR scan failed"));
        svc.runExport(20L);
        assertThat(rec.getStatus()).isEqualTo(BackupStatus.FAILED);
        verify(binder).recordBackup("FAILED");

        // maxKeys=1 超限中止
        props.getBackup().setMaxKeys(1L);
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k1".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("k2".getBytes(StandardCharsets.UTF_8)))))));
        when(ksSvc.inspectBatch(eq(inst), any())).thenReturn(List.of(
                new KeyEntry("k1", "none", -2),
                new KeyEntry("k2", "string", -1)));
        when(ksSvc.readValuesBatch(eq(inst), any())).thenReturn(Arrays.asList(null, "v2"));
        svc.runExport(20L);
        assertThat(rec.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(rec.getMessage()).contains("超过配置上限");
        props.getBackup().setMaxKeys(0L);

        // 3. verify: 404 / 非 SUCCESS / 文件缺失 / 损坏条目 / SHA 不匹配 / 历史补录 / GZIP 通过
        when(repo.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.verify(404L)).isInstanceOf(BusinessException.class);

        BackupRecord failedRec = new BackupRecord();
        failedRec.setId(21L);
        failedRec.setStatus(BackupStatus.FAILED);
        when(repo.findById(21L)).thenReturn(Optional.of(failedRec));
        assertThatThrownBy(() -> svc.verify(21L)).isInstanceOf(BusinessException.class).hasMessageContaining("仅成功的备份");

        BackupRecord missingFile = new BackupRecord();
        missingFile.setId(22L);
        missingFile.setInstanceId(1L);
        missingFile.setStatus(BackupStatus.SUCCESS);
        missingFile.setFileName("no-such-file.jsonl");
        when(repo.findById(22L)).thenReturn(Optional.of(missingFile));
        assertThatThrownBy(() -> svc.verify(22L)).isInstanceOf(BusinessException.class).hasMessageContaining("不存在");

        // 写明文合法归档 + 缺字段损坏归档 + gzip 归档
        String validContent = "\n{\"key\":\"k1\",\"type\":\"string\",\"ttl\":60,\"value\":\"v1\"}\n";
        writePublished(storage, "ok.jsonl", validContent.getBytes(StandardCharsets.UTF_8));
        BackupRecord legacyOk = new BackupRecord();
        legacyOk.setId(23L);
        legacyOk.setInstanceId(1L);
        legacyOk.setStatus(BackupStatus.SUCCESS);
        legacyOk.setFileName("ok.jsonl");
        when(repo.findById(23L)).thenReturn(Optional.of(legacyOk));
        var legacyRes = svc.verify(23L);
        assertThat(legacyRes.status()).isEqualTo(BackupVerifyStatus.VERIFIED);
        assertThat(legacyOk.getSha256()).isNotBlank();

        // SHA 篡改检测
        legacyOk.setSha256("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(svc.verify(23L).status()).isEqualTo(BackupVerifyStatus.CORRUPTED);

        // 缺少 key/type 的损坏归档
        writePublished(storage, "bad.jsonl", "{\"key\":null,\"type\":\"\"}\n".getBytes(StandardCharsets.UTF_8));
        BackupRecord badRec = new BackupRecord();
        badRec.setId(24L);
        badRec.setInstanceId(1L);
        badRec.setStatus(BackupStatus.SUCCESS);
        badRec.setFileName("bad.jsonl");
        when(repo.findById(24L)).thenReturn(Optional.of(badRec));
        assertThat(svc.verify(24L).status()).isEqualTo(BackupVerifyStatus.CORRUPTED);

        // GZIP 归档 + 匹配 SHA
        ByteArrayOutputStream gzBuf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzBuf)) {
            gz.write(validContent.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzBytes = gzBuf.toByteArray();
        writePublished(storage, "ok.jsonl.gz", gzBytes);
        BackupRecord gzRec = new BackupRecord();
        gzRec.setId(25L);
        gzRec.setInstanceId(1L);
        gzRec.setStatus(BackupStatus.SUCCESS);
        gzRec.setFileName("ok.jsonl.gz");
        gzRec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gzBytes)));
        when(repo.findById(25L)).thenReturn(Optional.of(gzRec));
        assertThat(svc.verify(25L).status()).isEqualTo(BackupVerifyStatus.VERIFIED);

        // 4. previewRestore: 非法状态 / 过期+冲突+未知类型 / CORRUPTED 告警 / 管线数量不匹配
        assertThatThrownBy(() -> svc.previewRestore(21L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.previewRestore(22L)).isInstanceOf(BusinessException.class);

        String previewData = "\n"
                + "{\"key\":\"k1\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v1\"}\n"
                + "{\"key\":\"k2\",\"type\":\"\",\"ttl\":-1,\"value\":\"v2\"}\n"
                + "{\"key\":\"exp\",\"type\":\"string\",\"ttl\":-2,\"value\":\"gone\"}\n";
        writePublished(storage, "prev.jsonl", previewData.getBytes(StandardCharsets.UTF_8));
        BackupRecord prevRec = new BackupRecord();
        prevRec.setId(26L);
        prevRec.setInstanceId(1L);
        prevRec.setStatus(BackupStatus.SUCCESS);
        prevRec.setVerifyStatus(BackupVerifyStatus.VERIFIED);
        prevRec.setFileName("prev.jsonl");
        when(repo.findById(26L)).thenReturn(Optional.of(prevRec));
        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of(new RespReply.Integer(1), new RespReply.Integer(0)));
        var pv = svc.previewRestore(26L);
        assertThat(pv.archiveReadable()).isTrue();
        assertThat(pv.conflictKeys()).isEqualTo(1L);
        assertThat(pv.expiredKeys()).isEqualTo(1L);
        assertThat(pv.warning()).contains("同名键");

        prevRec.setVerifyStatus(BackupVerifyStatus.CORRUPTED);
        assertThat(svc.previewRestore(26L).warning()).contains("CORRUPTED");

        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of()); // mismatch -> archiveReadable=false
        assertThat(svc.previewRestore(26L).archiveReadable()).isFalse();

        // 5. runRestore: 全数据类型（string/hash/list/set/zset/json/unknown/null/ttl>0）+ 批次失败
        String multiTypeData = "\n"
                + "{\"key\":null,\"type\":\"string\",\"ttl\":-1,\"value\":\"skip\"}\n"
                + "{\"key\":\"s1\",\"type\":\"string\",\"ttl\":30,\"value\":\"v1\"}\n"
                + "{\"key\":\"h1\",\"type\":\"hash\",\"ttl\":-1,\"value\":{\"f\":\"1\"}}\n"
                + "{\"key\":\"h0\",\"type\":\"hash\",\"ttl\":-1,\"value\":{}}\n"
                + "{\"key\":\"l1\",\"type\":\"list\",\"ttl\":-1,\"value\":[\"a\"]}\n"
                + "{\"key\":\"st1\",\"type\":\"set\",\"ttl\":-1,\"value\":[\"b\"]}\n"
                + "{\"key\":\"z1\",\"type\":\"zset\",\"ttl\":-1,\"value\":[{\"member\":\"m\",\"score\":\"1.5\"}]}\n"
                + "{\"key\":\"j1\",\"type\":\"json\",\"ttl\":-1,\"value\":{\"x\":1}}\n"
                + "{\"key\":\"u1\",\"type\":\"unknown\",\"ttl\":-1,\"value\":\"ignored\"}\n";
        writePublished(storage, "multi.jsonl", multiTypeData.getBytes(StandardCharsets.UTF_8));
        BackupRecord mr = new BackupRecord();
        mr.setId(27L);
        mr.setInstanceId(1L);
        mr.setFileName("multi.jsonl");
        when(repo.findById(27L)).thenReturn(Optional.of(mr));
        when(gateway.executeBatch(eq(inst), any())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            return cmds.stream().map(ignored -> (RespReply) new RespReply.Simple("OK")).toList();
        });
        assertThat(svc.runRestore(27L)).isEqualTo(9L);
        assertThat(mr.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);

        // mid-batch RESP Error -> BatchRestoreFailure
        when(gateway.executeBatch(eq(inst), any())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            List<RespReply> reps = new java.util.ArrayList<>();
            for (int i = 0; i < cmds.size(); i++) {
                reps.add(i == 2 ? new RespReply.Error("ERR oom") : new RespReply.Simple("OK"));
            }
            return reps;
        });
        svc.runRestore(27L);
        assertThat(mr.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
        assertThat(mr.getRestoreMessage()).contains("ERR oom");

        // 6. openDownload & checkpoint / checkpointStatus 全分支
        assertThatThrownBy(() -> svc.openDownload(404L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.openDownload(22L)).isInstanceOf(BusinessException.class);
        try (var dl = svc.openDownload(23L).inputStream()) {
            assertThat(dl.readAllBytes()).isNotEmpty();
        }

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Simple("Background saving started"));
        when(gateway.execute(inst, "LASTSAVE")).thenReturn(new RespReply.Integer(1700000000L));
        assertThat(svc.triggerCheckpoint(1L).triggered()).isTrue();

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Error("ERR Background save already in progress"));
        assertThat(svc.triggerCheckpoint(1L).triggered()).isFalse();

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Error("ERR unknown command 'BGSAVE'"));
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L)).isInstanceOf(BusinessException.class);

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Error("ERR disk full"));
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L)).isInstanceOf(BusinessException.class);

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Integer(1));
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L)).isInstanceOf(BusinessException.class);

        doThrow(new IOException("io")).when(gateway).execute(inst, "BGSAVE");
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L)).isInstanceOf(BusinessException.class);

        doReturn(new RespReply.Error("ERR unknown command")).when(gateway).execute(inst, "LASTSAVE");
        assertThat(svc.checkpointStatus(1L).supported()).isFalse();

        doReturn(new RespReply.Error("ERR auth")).when(gateway).execute(inst, "LASTSAVE");
        assertThatThrownBy(() -> svc.checkpointStatus(1L)).isInstanceOf(BusinessException.class);

        doThrow(new IOException("io")).when(gateway).execute(inst, "LASTSAVE");
        assertThatThrownBy(() -> svc.checkpointStatus(1L)).isInstanceOf(BusinessException.class);

        doReturn(new RespReply.Integer(100L)).when(gateway).execute(inst, "LASTSAVE");
        when(gateway.execute(inst, "INFO", "persistence")).thenReturn(new RespReply.Bulk((
                "# Persistence\r\nrdb_bgsave_in_progress:1\r\nrdb_last_save_time:200\r\n"
                        + "rdb_last_bgsave_status:ok\r\nrdb_last_bgsave_time_sec:bad\r\n"
                        + "rdb_last_bgsave_duration_ms:150\r\n").getBytes(StandardCharsets.UTF_8)));
        var cs = svc.checkpointStatus(1L);
        assertThat(cs.supported()).isTrue();
        assertThat(cs.inProgress()).isTrue();
        assertThat(cs.lastSaveTime()).isEqualTo(200L);
        assertThat(cs.lastDurationSeconds()).isEqualTo(-1L);

        when(gateway.execute(inst, "INFO", "persistence")).thenThrow(new IOException("ignored"));
        assertThat(svc.checkpointStatus(1L).lastSaveTime()).isEqualTo(100L);
    }

    // ==================== 3. KeyspaceAnalyzerService ====================

    @Test
    void keyspaceAnalyzerServiceCoversScanErrorsAllTypesJsonFallbackAndRecommendations() throws Exception {
        InstanceService instSvc = mock(InstanceService.class);
        KeyspaceService ksSvc = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        DbInstance inst = sampleInstance(1L, "ana-node");
        when(instSvc.require(1L)).thenReturn(inst);

        KeyspaceAnalyzerService svc = new KeyspaceAnalyzerService(instSvc, ksSvc, gateway, 2, 10_000L);

        // 1. SCAN Error / 畸形结构 / IOException
        when(gateway.execute(inst, "DBSIZE")).thenThrow(new IOException("dbsize err"));
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Error("ERR scan denied"));
        assertThatThrownBy(() -> svc.analyze(1L, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("SCAN 诊断失败");

        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Simple("bad"));
        assertThatThrownBy(() -> svc.analyze(1L, new KeyspaceAnalyzerService.AnalyzeRequest(" ", 50, "", 3)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("返回结构异常");

        doThrow(new IOException("net down")).when(gateway)
                .execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> svc.analyze(1L, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("扫描失败");

        // 2. 空结果 + 各类型尺寸探测 + JSON.STRLEN 降级 JSON.GET + TTL 四分桶 + 大键与主导前缀建议
        doReturn(new RespReply.Integer(100L)).when(gateway).execute(inst, "DBSIZE");
        doReturn(new RespReply.Array(List.of(
                new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                new RespReply.Array(List.of(
                        new RespReply.Bulk("app:str".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:hash".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:list".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:set".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:zset".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:stream".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:json".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("app:jsonarr".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("rootkey".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("gone".getBytes(StandardCharsets.UTF_8))
                ))))).when(gateway).execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any());

        when(ksSvc.inspectBatch(eq(inst), any())).thenReturn(List.of(
                new KeyEntry("app:str", "string", -1L),
                new KeyEntry("app:hash", "hash", 100L),
                new KeyEntry("app:list", "list", 7200L),
                new KeyEntry("app:set", "set", 100_000L),
                new KeyEntry("app:zset", "zset", -1L),
                new KeyEntry("app:stream", "stream", -1L),
                new KeyEntry("app:json", "json", -1L),
                new KeyEntry("app:jsonarr", "json", -1L),
                new KeyEntry("rootkey", "weird", -1L),
                new KeyEntry("gone", "none", -2L)
        ));
        // 第 1 次 executeBatch：7 个标准 + JSON.STRLEN，其中 app:json 返回 Error 触发降级 JSON.GET，app:jsonarr 返回 Array[Integer]
        when(gateway.executeBatch(eq(inst), any())).thenReturn(
                List.of(
                        new RespReply.Integer(70_000L),
                        new RespReply.Integer(1_500L),
                        new RespReply.Integer(10L),
                        new RespReply.Integer(5L),
                        new RespReply.Integer(8L),
                        new RespReply.Integer(3L),
                        new RespReply.Error("ERR unknown command 'JSON.STRLEN'"),
                        new RespReply.Array(List.of(new RespReply.Integer(50L), new RespReply.Integer(30L)))
                ),
                List.of(new RespReply.Bulk("{\"big\":true}".getBytes(StandardCharsets.UTF_8)))
        );

        var view = svc.analyze(1L, new KeyspaceAnalyzerService.AnalyzeRequest("app:*", 200, ":", 5));
        assertThat(view.scannedKeys()).isEqualTo(9L);
        assertThat(view.bigKeys()).hasSize(5);
        assertThat(view.ttlDistribution().under1h()).isEqualTo(1L);
        assertThat(view.ttlDistribution().between1hAnd24h()).isEqualTo(1L);
        assertThat(view.ttlDistribution().over24h()).isEqualTo(1L);
        assertThat(view.recommendations()).anyMatch(r -> r.contains("64 KB"));
        assertThat(view.recommendations()).anyMatch(r -> r.contains("1,000"));

        // 覆盖静态辅助分支：extractPrefix / unitLabelFor / buildRecommendations 全分支
        assertThat(KeyspaceAnalyzerService.extractPrefix("", ":")).isEqualTo("(empty)");
        assertThat(KeyspaceAnalyzerService.extractPrefix("plain", ":")).isEqualTo("(root)");
        assertThat(KeyspaceAnalyzerService.unitLabelFor("unknown")).isEqualTo("units");
        assertThat(KeyspaceAnalyzerService.buildRecommendations(0,
                new KeyspaceAnalyzerService.TtlDistribution(0, 0, 0, 0), List.of(), List.of()))
                .anyMatch(s -> s.contains("未扫描到"));
        assertThat(KeyspaceAnalyzerService.buildRecommendations(60,
                new KeyspaceAnalyzerService.TtlDistribution(50, 5, 3, 2),
                List.of(new KeyspaceAnalyzerService.PrefixStat("hot:*", 50, 1000, 83.33)),
                List.of())).hasSize(2);
        assertThat(KeyspaceAnalyzerService.buildRecommendations(10,
                new KeyspaceAnalyzerService.TtlDistribution(2, 3, 3, 2), List.of(), List.of()))
                .anyMatch(s -> s.contains("分布均衡"));
    }

    // ==================== 4. AuditService, BackupScheduleService & DbLeaseLockService ====================

    @Test
    void auditScheduleAndLeaseServicesCoverRemainingEdgeBranches() {
        // ---- AuditService ----
        OpLogRepository opRepo = mock(OpLogRepository.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);
        AuditService audit = new AuditService(opRepo, binder);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            when(opRepo.save(any(OpLog.class))).thenThrow(new RuntimeException("db hiccup"));
            audit.record("TEST_ACT", "tgt", "det", false); // 吞并 save 异常不影响主流程
            verify(binder).recordAudit("TEST_ACT", false);

            req.removeHeader("X-Forwarded-For");
            req.setRemoteAddr("192.0.2.9");
            doReturn(new OpLog()).when(opRepo).save(any(OpLog.class));
            audit.record("TEST_OK", "tgt", "det", true);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        OpLog log1 = new OpLog();
        log1.setCreatedAt(LocalDateTime.now());
        log1.setUsername("admin");
        log1.setAction("ACT");
        log1.setTarget("a,b");
        log1.setDetail("line1\n\"quoted\"");
        log1.setIp(null);
        log1.setSuccess(false);
        log1.setRequestId("req-1");
        OpLog log2 = new OpLog();
        log2.setCreatedAt(null);
        log2.setSuccess(true);
        when(opRepo.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(log1)));
        when(opRepo.findByActionContainingOrderByCreatedAtDesc(eq("ACT"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log1)));
        assertThat(audit.page(" ", 0, 500).getContent()).hasSize(1);
        assertThat(audit.page("ACT", 1, 10).getContent()).hasSize(1);

        when(opRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log1, log2)));
        String csv = audit.exportCsv("ACT", "admin", true, "req-1",
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
        assertThat(csv).contains("\"a,b\"").contains("\"line1\n\"\"quoted\"\"\"").contains("失败").contains("成功");
        assertThat(audit.search(null, null, null, null, null, 1, 20).getContent()).hasSize(2);

        // ---- BackupScheduleService ----
        BackupScheduleRepository schedRepo = mock(BackupScheduleRepository.class);
        DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        BackupService bkSvc = mock(BackupService.class);
        BackupRetentionService retSvc = mock(BackupRetentionService.class);
        ConsoleProperties props = new ConsoleProperties();
        DbLeaseLockService leaseSvc = mock(DbLeaseLockService.class);
        BackupScheduleService schedSvc = new BackupScheduleService(
                schedRepo, instRepo, instSvc, bkSvc, retSvc, audit, props, leaseSvc);

        DbInstance inst = sampleInstance(1L, "s-node");
        when(instSvc.require(1L)).thenReturn(inst);
        when(instRepo.findAll()).thenReturn(List.of(inst));
        when(schedRepo.findAllByOrderByIdDesc()).thenReturn(List.of());
        when(schedRepo.findByInstanceIdOrderByIdDesc(1L)).thenReturn(List.of());
        assertThat(schedSvc.list(null)).isEmpty();
        assertThat(schedSvc.list(1L)).isEmpty();

        // cron 校验分支：空 / 4 段 / 语法错
        assertThatThrownBy(() -> BackupScheduleService.normalizeCron(" ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BackupScheduleService.normalizeCron("0 0 * *")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BackupScheduleService.parseCron("99 99 99 99 99")).isInstanceOf(BusinessException.class);
        assertThat(BackupScheduleService.normalizeCron("0 2 * * *")).isEqualTo("0 0 2 * * *");
        assertThat(BackupScheduleService.normalizeCron("0 0 2 * * *")).isEqualTo("0 0 2 * * *");

        // create / update 冲突与禁用
        when(schedRepo.existsByName("dup")).thenReturn(true);
        assertThatThrownBy(() -> schedSvc.create(new BackupScheduleRequest(1L, "dup", "0 2 * * *", null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");
        when(schedRepo.existsByName("ok-s")).thenReturn(false);
        when(schedRepo.save(any(BackupSchedule.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(schedSvc.create(new BackupScheduleRequest(1L, "ok-s", "0 2 * * *", null, null, false)).enabled())
                .isFalse();

        BackupSchedule existing = new BackupSchedule();
        existing.setId(5L);
        existing.setInstanceId(1L);
        existing.setName("ok-s");
        existing.setCronExpression("0 0 2 * * *");
        existing.setMode(BackupScheduleMode.CHECKPOINT);
        existing.setRetentionCount(3);
        existing.setEnabled(true);
        when(schedRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(schedRepo.existsByNameAndIdNot("taken", 5L)).thenReturn(true);
        assertThatThrownBy(() -> schedSvc.update(5L, new BackupScheduleRequest(1L, "taken", "0 2 * * *", null, null, null)))
                .isInstanceOf(BusinessException.class);

        // scheduledTick: disabled / 未抢到租约 / 到期非法 cron + CHECKPOINT SKIPPED + LOGICAL 异常
        props.getBackup().getSchedule().setEnabled(false);
        schedSvc.scheduledTick();
        props.getBackup().getSchedule().setEnabled(true);
        when(leaseSvc.tryAcquire(anyString(), any())).thenReturn(false);
        schedSvc.scheduledTick();

        BackupSchedule badCron = new BackupSchedule();
        badCron.setId(6L);
        badCron.setName("bad-cron");
        badCron.setCronExpression("invalid");
        badCron.setEnabled(true);
        BackupSchedule logical = new BackupSchedule();
        logical.setId(7L);
        logical.setInstanceId(1L);
        logical.setName("log-s");
        logical.setCronExpression("0 2 * * *");
        logical.setMode(BackupScheduleMode.LOGICAL);
        logical.setRetentionCount(2);
        logical.setEnabled(true);

        when(leaseSvc.tryAcquire(anyString(), any())).thenReturn(true);
        when(schedRepo.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(any()))
                .thenReturn(List.of(badCron, existing, logical));
        when(bkSvc.triggerCheckpoint(1L)).thenReturn(new BackupService.CheckpointResult(false, "", 0L));
        when(bkSvc.export(1L)).thenThrow(new RuntimeException("export crash"));
        schedSvc.scheduledTick();
        assertThat(badCron.isEnabled()).isFalse();
        assertThat(existing.getLastStatus()).isEqualTo("SKIPPED");
        assertThat(logical.getLastStatus()).isEqualTo("FAILED");

        // runNow 手工触发遇到 BusinessException 向上抛出
        when(bkSvc.triggerCheckpoint(1L)).thenThrow(BusinessException.badRequest("manual fail"));
        assertThatThrownBy(() -> schedSvc.runNow(5L)).isInstanceOf(BusinessException.class);

        // ---- DbLeaseLockService ----
        ConsoleLeaseRepository leaseRepo = mock(ConsoleLeaseRepository.class);
        PlatformTransactionManager noopTx = mock(PlatformTransactionManager.class);
        when(noopTx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        DbLeaseLockService realLease = new DbLeaseLockService(leaseRepo, noopTx, "x".repeat(80), null);
        assertThat(realLease.ownerId()).hasSize(64);
        assertThatThrownBy(() -> realLease.tryAcquire(" ", java.time.Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);

        when(leaseRepo.tryAcquireOrRenew(anyString(), anyString(), any(), any())).thenReturn(0);
        when(leaseRepo.existsById("k1")).thenReturn(false);
        when(leaseRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("race"));
        assertThat(realLease.tryAcquire("k1", java.time.Duration.ZERO)).isFalse();

        realLease.release(null);
        realLease.release(" ");
        doThrow(new RuntimeException("release err")).when(leaseRepo).release(eq("k1"), anyString());
        realLease.release("k1"); // 吞并异常仅 WARN
    }

    private static void writePublished(LocalBackupStorageBackend storage, String name, byte[] data) throws IOException {
        try (OutputStream out = storage.openTempOutput(name)) {
            out.write(data);
        }
        storage.publish(name);
    }

    private static DbInstance sampleInstance(Long id, String name) {
        DbInstance inst = new DbInstance();
        inst.setId(id);
        inst.setName(name);
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        inst.setEnabled(true);
        return inst;
    }
}
