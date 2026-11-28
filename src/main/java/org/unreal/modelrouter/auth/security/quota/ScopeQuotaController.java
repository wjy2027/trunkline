/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组织维额度管理控制器（用户 / 部门 / 租户三层 token 额度，OSS 基础能力）。
 *
 * <p>路径用独立的 {@code /api/config/quota-scope/**}，不放在 {@code /api/config/quota/**} 下：
 * 后者已被 {@code ModelQuotaController} 的 {@code PUT/DELETE /{modelName}} 与
 * {@code QuotaConfigController} 的根路径占用，子路径会被厂商模型通配吞掉并触发歧义映射。</p>
 *
 * <p>三层 scope 共用一套端点，{@code {scope}} 段取 {@code users} / {@code departments} /
 * {@code tenants}，映射到 {@link ScopeQuotaStore} 的三个 configKey。配置走 {@code config_data}，
 * 零 DDL；写入后主动清 Caffeine 缓存。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/config/quota-scope")
@Tag(name = "组织维额度配置", description = "用户 / 部门 / 租户三层 token 额度的查询与配置")
public class ScopeQuotaController {

    private final ScopeQuotaStore store;

    public ScopeQuotaController(final ScopeQuotaStore store) {
        this.store = store;
    }

    /** 查询某类 scope 的全部额度配置。 */
    @GetMapping("/{scope}")
    @Operation(summary = "查询组织维额度列表", description = "scope 取 users/departments/tenants")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> list(
            @PathVariable final String scope) {
        final String configKey = ScopeQuotaStore.configKeyFor(scope);
        if (configKey == null) {
            return badScope(scope);
        }
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final Map<String, Object> row : store.list(configKey)) {
            rows.add(store.plain(row));
        }
        return ResponseEntity.ok(RouterResponse.success(rows));
    }

    /** 新增或覆盖一条 scope 额度配置。 */
    @PutMapping("/{scope}/{scopeId}")
    @Operation(summary = "配置组织维额度", description = "按 scopeId 写入用户/部门/租户额度，不存在则新建")
    public ResponseEntity<RouterResponse<Map<String, Object>>> upsert(
            @PathVariable final String scope,
            @PathVariable final String scopeId,
            @RequestBody final ScopeQuotaUpsertRequest request) {
        final String configKey = ScopeQuotaStore.configKeyFor(scope);
        if (configKey == null) {
            return badScope(scope);
        }
        if (scopeId == null || scopeId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(RouterResponse.error("scopeId 不能为空", "INVALID_REQUEST"));
        }
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(RouterResponse.error("请求体不能为空", "INVALID_REQUEST"));
        }

        final List<String> invalid = new ArrayList<>();
        final QuotaLimitSet limit = new QuotaLimitSet(
                nonNegative(request.minuteRequests, "minuteRequests", invalid),
                nonNegative(request.hourRequests, "hourRequests", invalid),
                nonNegative(request.hourTokens, "hourTokens", invalid),
                nonNegative(request.dayRequests, "dayRequests", invalid),
                nonNegative(request.dayTokens, "dayTokens", invalid),
                nonNegative(request.monthRequests, "monthRequests", invalid),
                nonNegative(request.monthTokens, "monthTokens", invalid));
        if (!invalid.isEmpty()) {
            return ResponseEntity.badRequest().body(RouterResponse.error(
                    "以下字段不能为负数: " + String.join(", ", invalid), "INVALID_REQUEST"));
        }
        final Map<String, Object> row = ScopeQuotaStore.toRow(scopeId.trim(), limit);
        store.upsert(configKey, row);
        log.info("组织维额度已保存: scope={}, scopeId={}", scope, scopeId);
        return ResponseEntity.ok(RouterResponse.success(store.plain(row), "额度已保存"));
    }

    /** 删除一条 scope 额度配置（该 scope 回到不限额）。 */
    @DeleteMapping("/{scope}/{scopeId}")
    @Operation(summary = "删除组织维额度", description = "删除后该 scope 不再参与额度判定")
    public ResponseEntity<RouterResponse<Void>> delete(
            @PathVariable final String scope,
            @PathVariable final String scopeId) {
        final String configKey = ScopeQuotaStore.configKeyFor(scope);
        if (configKey == null) {
            return badScope(scope);
        }
        store.delete(configKey, scopeId);
        log.info("组织维额度已删除: scope={}, scopeId={}", scope, scopeId);
        return ResponseEntity.ok(RouterResponse.success(null, "额度已删除"));
    }

    private static long nonNegative(final Long value, final String field, final List<String> invalid) {
        if (value == null) {
            return 0L;
        }
        if (value < 0L) {
            invalid.add(field);
            return 0L;
        }
        return value;
    }

    private static <T> ResponseEntity<RouterResponse<T>> badScope(final String scope) {
        return ResponseEntity.badRequest().body(RouterResponse.error(
                "未知的组织维 scope: " + scope + "，可选 users/departments/tenants", "INVALID_SCOPE"));
    }

    /** 组织维额度 upsert 请求体（7 个窗口限额，缺失按 0=不限）。 */
    public static class ScopeQuotaUpsertRequest {
        public Long minuteRequests;
        public Long hourRequests;
        public Long hourTokens;
        public Long dayRequests;
        public Long dayTokens;
        public Long monthRequests;
        public Long monthTokens;
    }
}
