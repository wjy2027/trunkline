/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.console.ConsoleExtensionStore;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 组织维额度（用户 / 部门 / 租户）落码测试：维度工厂 + ScopeQuotaStore + tryReserve 灰度门控。
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("组织维额度测试")
class OrgScopeQuotaTest {

    private static final String KEY_ID = "key-1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 14, 13, 45, 30);

    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private StoreManager storeManager;

    private QuotaProperties properties;
    private QuotaLedgerService ledgerService;
    private QuotaEnforcementService enforcementService;
    private Map<String, ApiKey> apiKeyCache;
    private Map<String, String> keyIdIndex;
    private ServerHttpRequest request;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        final Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        final InMemoryQuotaLedgerRepository repository = InMemoryQuotaLedgerRepository.create();
        properties = new QuotaProperties();
        properties.setEnabled(true);
        apiKeyCache = new HashMap<>();
        keyIdIndex = new HashMap<>();
        when(apiKeyService.getApiKeyCache()).thenReturn(apiKeyCache);
        when(apiKeyService.getKeyIdIndex()).thenReturn(keyIdIndex);
        ledgerService = new QuotaLedgerService(repository.proxy(), properties, clock);
        enforcementService = new QuotaEnforcementService(ledgerService, properties, apiKeyService, clock);
        request = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/chat/completions").build()).getRequest();
        registerApiKey();
    }

    // ==================== 维度工厂 ====================

    @Test
    @DisplayName("用户/部门桶用 __shared__ 哨兵，租户桶用显式 tenantId")
    void factories_useSharedSentinelForUserAndDepartment() {
        final QuotaDimension user = QuotaDimension.ofUser("zhangsan");
        assertEquals(QuotaDimension.SHARED_SCOPE, user.tenantId());
        assertEquals("zhangsan", user.userId());
        assertEquals("", user.apiKeyId());

        final QuotaDimension dept = QuotaDimension.ofDepartment("研发部");
        assertEquals(QuotaDimension.SHARED_SCOPE, dept.tenantId());
        assertEquals("dept:研发部", dept.userId());
        assertNotEquals(user, dept, "用户桶与部门桶必须隔离");

        final QuotaDimension tenant = QuotaDimension.ofTenant("proj-alpha");
        assertEquals("proj-alpha", tenant.tenantId());
        assertEquals("", tenant.userId());
        assertEquals("", tenant.apiKeyId());
    }

    @Test
    @DisplayName("用户桶跨 Key 归集：同一 holder 多把 Key 计入同一维度")
    void userBucket_aggregatesAcrossKeys() {
        final QuotaLimitSet limit = new QuotaLimitSet(0L, 0L, 0L, 2L, 0L, 0L, 0L);
        properties.setUserScopeEnabled(true);
        injectResolver("userLimitResolver", "zhangsan", limit);
        request.getAttributes().put(QuotaEnforcementService.QUOTA_HOLDER_ATTRIBUTE, "zhangsan");

        assertTrue(enforcementService.tryReserve(request, KEY_ID, "", "", 10L).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofUser("zhangsan"), QuotaWindow.DAY));
        assertEquals(1L, requestCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY));
    }

    @Test
    @DisplayName("灰度开关默认关：用户维度不参与判定也不记账")
    void userScopeDisabled_shouldSkipDimension() {
        injectResolver("userLimitResolver", "zhangsan", new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L));
        request.getAttributes().put(QuotaEnforcementService.QUOTA_HOLDER_ATTRIBUTE, "zhangsan");

        // 开关关：即便解析器返回日限额 1，第二次仍应放行（用户维未参与）
        assertTrue(enforcementService.tryReserve(request, KEY_ID, "", "", 10L).isEmpty());
        assertTrue(enforcementService.tryReserve(request, KEY_ID, "", "", 10L).isEmpty());
        assertEquals(0L, requestCount(QuotaDimension.ofUser("zhangsan"), QuotaWindow.DAY));
    }

    // ==================== ScopeQuotaStore ====================

    @Test
    @DisplayName("ScopeQuotaStore：配置行解析为 QuotaLimitSet，cache miss 走 config_data")
    void scopeQuotaStore_parsesConfigAndCaches() {
        final Map<String, Object> backing = new HashMap<>();
        when(storeManager.getConfig(ScopeQuotaStore.USERS_KEY)).thenAnswer(inv -> backing.isEmpty() ? null : new HashMap<>(backing));
        doAnswer(inv -> {
            @SuppressWarnings("unchecked") final java.util.function.UnaryOperator<Map<String, Object>> mutator = inv.getArgument(1);
            final Map<String, Object> result = mutator.apply(backing.isEmpty() ? null : new HashMap<>(backing));
            backing.clear();
            if (result != null) backing.putAll(result);
            return result;
        }).when(storeManager).mutateConfig(eq(ScopeQuotaStore.USERS_KEY), any());

        final ConsoleExtensionStore consoleStore = new ConsoleExtensionStore(storeManager, new ObjectMapper());
        final ScopeQuotaStore scopeStore = new ScopeQuotaStore(consoleStore, new ObjectMapper());
        scopeStore.upsert(ScopeQuotaStore.USERS_KEY,
                ScopeQuotaStore.toRow("lisi", new QuotaLimitSet(0L, 0L, 0L, 5L, 0L, 0L, 0L)));

        final Optional<QuotaLimitSet> resolved = scopeStore.resolve(ScopeQuotaStore.USERS_KEY, "lisi");
        assertTrue(resolved.isPresent());
        assertEquals(5L, resolved.get().dayRequests());
        assertTrue(scopeStore.resolve(ScopeQuotaStore.USERS_KEY, "unknown").isEmpty());
    }

    // ==================== helpers ====================

    private void registerApiKey() {
        apiKeyCache.put("hash-key-1", ApiKey.builder()
                .keyId(KEY_ID)
                .keyHash("hash-key-1")
                .dailyRequestLimit(0L)
                .dailyTokenLimit(0L)
                .rateLimitPerMinute(0)
                .enabled(true)
                .build());
        keyIdIndex.put(KEY_ID, "hash-key-1");
    }

    private void injectResolver(final String fieldName, final String scopeId, final QuotaLimitSet limits) {
        try {
            final Field field = QuotaEnforcementService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(enforcementService, (QuotaLimitResolver) id -> scopeId.equals(id)
                    ? Optional.of(limits) : Optional.empty());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入解析器失败: " + fieldName, e);
        }
    }

    private long requestCount(final QuotaDimension dimension, final QuotaWindow window) {
        return ledgerService.usageStrict(dimension, window).map(QuotaUsage::requestCount).orElse(0L);
    }
}
