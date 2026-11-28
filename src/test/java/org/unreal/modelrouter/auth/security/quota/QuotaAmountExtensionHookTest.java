package org.unreal.modelrouter.auth.security.quota;

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
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织维金额扩展的 OSS 挂载契约测试（2026-11-27）。
 *
 * <p>覆盖三条 fail-closed 拒绝分支的双账回滚、恰一次结算回调，以及 5 参委托读取
 * holder / department 属性——没有这些断言，用户 / 部门金额额度会「静默不生效」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("金额扩展挂载契约测试")
class QuotaAmountExtensionHookTest {

    private static final String KEY_ID = "key-amount-1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0, 0);

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private QuotaCheckExtension extension;

    private QuotaProperties properties;
    private QuotaLedgerService ledgerService;
    private QuotaEnforcementService enforcementService;
    private ServerHttpRequest request;

    @BeforeEach
    void setUp() {
        final Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        final InMemoryQuotaLedgerRepository repository = InMemoryQuotaLedgerRepository.create();
        properties = new QuotaProperties();
        properties.setEnabled(true);
        when(apiKeyService.getApiKeyCache()).thenReturn(new HashMap<>());
        when(apiKeyService.getKeyIdIndex()).thenReturn(new HashMap<>());
        ledgerService = new QuotaLedgerService(repository.proxy(), properties, clock);
        enforcementService = new QuotaEnforcementService(ledgerService, properties, apiKeyService, clock);
        // @Autowired(required=false) 私有字段不会被 @InjectMocks 注入，须显式设置
        ReflectionTestUtils.setField(enforcementService, "extension", extension);
        request = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/chat/completions").build()).getRequest();
    }

    @Test
    @DisplayName("preCheck 拒绝：金额冲正 + token 回滚，原 violation 返回且 amountMinor=0")
    void denied_shouldRollbackBothLedgers() {
        final QuotaLimitViolation denied = new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_AMOUNT, QuotaWindow.MONTH, 100L, 120L, 3600L,
                "金额额度 TENANT", "TENANT", "cs-product");
        when(extension.preCheck(any())).thenReturn(Optional.of(denied));

        final Optional<QuotaLimitViolation> result = enforcementService.tryReserve(request, KEY_ID, 10L);

        assertTrue(result.isPresent());
        assertEquals(denied, result.get());
        assertEquals("TENANT", result.get().scopeType());
        assertEquals("cs-product", result.get().scopeId());
        // 2026-11-27 中风险整改：describe()（进 429 响应体）不再拼接 scopeType/scopeId，
        // 防内部标识外泄；完整诊断文本由 describeDetailed() 提供，仅供日志
        assertFalse(result.get().describe().contains("scopeType="));
        assertFalse(result.get().describe().contains("cs-product"));
        assertTrue(result.get().describeDetailed().contains("scopeType=TENANT"));
        assertTrue(result.get().describeDetailed().contains("cs-product"));
        verify(extension).onRolledBack(any(), org.mockito.ArgumentMatchers.eq(0L));
        assertEquals(0L, ledgerService.usageStrict(
                QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY).orElseThrow().requestCount());
    }

    @Test
    @DisplayName("preCheck 抛异常：中性 429 + 金额冲正 + token 回滚，不落回外层 fail-open")
    void extensionThrowing_shouldFailClosed() {
        when(extension.preCheck(any())).thenThrow(new IllegalStateException("ledger down"));

        final Optional<QuotaLimitViolation> result = enforcementService.tryReserve(request, KEY_ID, 10L);

        assertTrue(result.isPresent());
        assertEquals(QuotaLimitViolation.METRIC_AMOUNT_EXT_UNAVAILABLE, result.get().metric());
        verify(extension).onRolledBack(any(), org.mockito.ArgumentMatchers.eq(0L));
        assertEquals(0L, ledgerService.usageStrict(
                QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY).orElseThrow().requestCount());
    }

    @Test
    @DisplayName("金额冲正失败：返回中性 rollback violation，token 仍必须回滚")
    void rollbackFailure_shouldStillRollbackTokens() {
        final QuotaLimitViolation denied = new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_AMOUNT, QuotaWindow.MONTH, 1L, 2L, 1L, "金额额度", "USER", "zhangsan");
        when(extension.preCheck(any())).thenReturn(Optional.of(denied));
        doThrow(new RuntimeException("rollback failed"))
                .when(extension).onRolledBack(any(), anyLong());

        final Optional<QuotaLimitViolation> result = enforcementService.tryReserve(request, KEY_ID, 10L);

        assertTrue(result.isPresent());
        assertEquals(QuotaLimitViolation.METRIC_AMOUNT_EXT_UNAVAILABLE, result.get().metric());
        assertEquals("rollback", result.get().scopeId());
        assertEquals(0L, ledgerService.usageStrict(
                QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY).orElseThrow().requestCount());
    }

    @Test
    @DisplayName("放行：onReserved 通知、settle 恰一次 onCommitted、重复 settle 不重复回调")
    void success_shouldAttachCallbackAndSettleOnce() {
        when(extension.preCheck(any())).thenAnswer(invocation -> {
            ((QuotaCheckContext) invocation.getArgument(0)).amountMinor(1234L);
            return Optional.empty();
        });

        assertTrue(enforcementService.tryReserve(request, KEY_ID, 10L).isEmpty());
        verify(extension).onReserved(any(), org.mockito.ArgumentMatchers.eq(1234L));
        final QuotaReservation reservation = QuotaReservation.from(request);
        assertTrue(reservation != null);
        QuotaReservation.settleSuccess(reservation, 8L);
        QuotaReservation.settleSuccess(reservation, 8L);
        verify(extension, times(1)).onCommitted(any(), org.mockito.ArgumentMatchers.eq(1234L));
        QuotaReservation.settleFailure(reservation);
        verify(extension, times(1)).onCommitted(any(), anyLong());
        verify(extension, times(0)).onRolledBack(any(), anyLong());
    }

    @Test
    @DisplayName("5 参委托：从请求属性取 holder / department 写入上下文（用户/部门金额不静默失效）")
    void fiveArgOverload_shouldPassHolderAndDepartment() {
        request.getAttributes().put(QuotaEnforcementService.QUOTA_HOLDER_ATTRIBUTE, "zhangsan");
        request.getAttributes().put(QuotaEnforcementService.QUOTA_DEPARTMENT_ATTRIBUTE, "研发部");
        when(extension.preCheck(any())).thenAnswer(invocation -> {
            final QuotaCheckContext ctx = invocation.getArgument(0);
            assertEquals("zhangsan", ctx.holder());
            assertEquals("研发部", ctx.department());
            assertEquals(10L, ctx.estimatedTokens());
            assertEquals(0L, ctx.amountMinor());
            return Optional.empty();
        });

        assertTrue(enforcementService.tryReserve(request, KEY_ID, "", "", 10L).isEmpty());
        verify(extension).preCheck(any());
    }
}
