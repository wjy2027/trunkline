/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.console.ConsoleExtensionStore;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 组织维额度配置存储（用户 / 部门 / 租户三层）。
 *
 * <p>遵守零 DDL 红线：不建新表、不改 V1 baseline、不加 Flyway，统一复用
 * {@code config_data}（{@link StoreManager}）以独立 configKey 承载。三个 configKey
 * ({@link #USERS_KEY} / {@link #DEPARTMENTS_KEY} / {@link #TENANTS_KEY}) 已登记为
 * 全局共享键（{@code GlobalConfigKeys.GLOBAL_EXACT}），写入 {@code __shared__} 哨兵租户，
 * 所有租户看到同一份限额定义——这与「额度定义是平台级策略、用量按维度独立记账」的口径一致。</p>
 *
 * <p><b>热路径缓存</b>：{@code resolve(scopeId)} 在每请求每维调用，直接走
 * {@link StoreManager#getConfig(String)} 会是每请求 3~6 次 DB 读。本类用 Caffeine 缓存
 * 整张「scopeId → QuotaLimitSet」映射（TTL 60s + 写操作主动失效），命中路径零 DB。</p>
 *
 * <p><b>并发</b>：写操作走 {@link ConsoleExtensionStore#upsert}/{@code delete}，其内部
 * {@link StoreManager#mutateConfig} 是原子读改写；写完本进程主动 {@link #invalidate} 清缓存，
 * 多副本场景依赖 TTL 兜底（TTL 内可能有短暂陈旧，与账本 fail-open 口径一致）。</p>
 *
 * @author Trunkline Team
 */
@Service
public class ScopeQuotaStore {

    public static final String USERS_KEY = "quota.scope.users";
    public static final String DEPARTMENTS_KEY = "quota.scope.departments";
    public static final String TENANTS_KEY = "quota.scope.tenants";

    /** 配置行主键字段名。 */
    public static final String SCOPE_ID = "scopeId";

    /** REST 路径 scope 段 → configKey；未知 scope 返回 null（端点返回 400）。 */
    private static final Map<String, String> SCOPE_KEYS = Map.of(
            "users", USERS_KEY,
            "departments", DEPARTMENTS_KEY,
            "tenants", TENANTS_KEY);

    /**
     * 把 REST scope 段（users/departments/tenants）映射到 configKey。
     *
     * @param scope scope 段
     * @return configKey；未知 scope 返回 {@code null}
     */
    public static String configKeyFor(final String scope) {
        return scope == null ? null : SCOPE_KEYS.get(scope.toLowerCase());
    }

    private static final long CACHE_TTL_SECONDS = 60L;
    private static final long MAX_CACHE_ENTRIES = 2048L;

    private final ConsoleExtensionStore store;
    private final ObjectMapper objectMapper;
    private final Cache<String, Map<String, QuotaLimitSet>> cache;

    public ScopeQuotaStore(final ConsoleExtensionStore store,
                           final ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .maximumSize(MAX_CACHE_ENTRIES)
                .build();
    }

    /**
     * 解析指定 scope 的限额集合。
     *
     * @param configKey 三个 configKey 之一
     * @param scopeId   scope 标识（holder / dept 名 / tenantId）
     * @return 限额集合；未配置或解析异常时返回 {@link Optional#empty()}（按无限制放行、仅记账）
     */
    public Optional<QuotaLimitSet> resolve(final String configKey, final String scopeId) {
        if (configKey == null || scopeId == null || scopeId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(cache.get(configKey, key -> loadAll(key)).get(scopeId));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 列出某类 scope 的全部配置行（管理端点用，返回原始 Map）。 */
    public List<Map<String, Object>> list(final String configKey) {
        return store.list(configKey);
    }

    /**
     * 新增或覆盖一条 scope 额度配置。
     *
     * @param configKey 三个 configKey 之一
     * @param item      配置行（须含 {@link #SCOPE_ID}）
     * @return true=新增，false=覆盖
     */
    public boolean upsert(final String configKey, final Map<String, Object> item) {
        final boolean added = store.upsert(configKey, SCOPE_ID, item);
        invalidate(configKey);
        return added;
    }

    /** 删除一条 scope 额度配置；不存在也幂等成功。 */
    public void delete(final String configKey, final String scopeId) {
        store.delete(configKey, SCOPE_ID, scopeId);
        invalidate(configKey);
    }

    /** 主动失效某类 scope 的缓存（写操作后调用）。 */
    public void invalidate(final String configKey) {
        if (configKey != null) {
            cache.invalidate(configKey);
        }
    }

    /** 把配置行转成对外展示的普通 Map（避免序列化 JPA 代理）。 */
    public Map<String, Object> plain(final Map<String, Object> source) {
        return store.plain(source);
    }

    /** 从 config_data 加载整张映射（缓存 miss 时调用）。 */
    private Map<String, QuotaLimitSet> loadAll(final String configKey) {
        final Map<String, QuotaLimitSet> result = new LinkedHashMap<>();
        for (final Map<String, Object> row : store.list(configKey)) {
            final Object id = row.get(SCOPE_ID);
            if (!(id instanceof String scopeId) || scopeId.isEmpty()) {
                continue;
            }
            result.put(scopeId, toLimitSet(row));
        }
        return result;
    }

    /** 配置行（7 个数字字段）→ QuotaLimitSet；缺失/非法字段按 0（不限）处理。 */
    private QuotaLimitSet toLimitSet(final Map<String, Object> row) {
        return new QuotaLimitSet(
                number(row, "minuteRequests"),
                number(row, "hourRequests"),
                number(row, "hourTokens"),
                number(row, "dayRequests"),
                number(row, "dayTokens"),
                number(row, "monthRequests"),
                number(row, "monthTokens"));
    }

    private static long number(final Map<String, Object> row, final String key) {
        final Object value = row.get(key);
        if (value instanceof Number n) {
            return Math.max(0L, n.longValue());
        }
        if (value instanceof String s) {
            try {
                return Math.max(0L, Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 供测试构造配置行：把 QuotaLimitSet 展成 config_data 行。 */
    public static Map<String, Object> toRow(final String scopeId, final QuotaLimitSet limit) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put(SCOPE_ID, scopeId);
        row.put("minuteRequests", limit.minuteRequests());
        row.put("hourRequests", limit.hourRequests());
        row.put("hourTokens", limit.hourTokens());
        row.put("dayRequests", limit.dayRequests());
        row.put("dayTokens", limit.dayTokens());
        row.put("monthRequests", limit.monthRequests());
        row.put("monthTokens", limit.monthTokens());
        return row;
    }
}
