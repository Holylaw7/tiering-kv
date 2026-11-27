package io.tieringkv.webconsole.domain;

/** 实例被删除后发布的本地清理事件（由 InstanceReconcileService 订阅，避免服务间循环依赖）。 */
public record InstanceDeletedEvent(Long instanceId) {
}
