package org.unreal.modelrouter.auth.security.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.event.ApiKeyAuditEvent;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配额限额判定与预留服务（v3.1 PR-2）。
 *
 * <p>把 PR-1 的旁路账本接入请求主链路：在实例选择之前判定“账本窗口用量 + 本次估算 token”
 * 是否越过既有 {@link ApiKey} 限额，未超限则记账预留（{@link QuotaLedgerService#reserve(QuotaRequest)}），
 * 结算由链路末端按实际用量冲正。</p>
 *
 * <p><b>限额映射</b>（限额来源是既有 API Key 配置，不新增配额字段）：</p>
 * <ul>
 *   <li>{@link QuotaWindow#DAY} 窗口 → {@code dailyRequestLimit}（请求数）与
 *       {@code dailyTokenLimit}（token 数）；</li>
 *   <li>{@link QuotaWindow#MINUTE} 窗口 → {@code rateLimitPerMinute}（请求数）。</li>
 * </ul>
 * <p>{@code 0} 表示不限制（与 {@link ApiKey} 既有语义一致）。判定顺序为
 * 日请求数 → 日 token 数 → 每分钟请求数，命中即返回（一次只报告一项，避免响应头自相矛盾）。</p>
 *
 * <p><b>维度取舍</b>：限额本身是 API Key 级配置，因此请求侧账本维度取 API Key 粒度
 * （{@link QuotaDimension#ofApiKey(String)}，其余维度留空），使 {@code usage(dimension, window)}
 * 既能命中账本内存热路径（不引入每请求一次聚合查询），又能按 Key 正确聚合窗口用量；
 * 服务类型 / 模型级细分留待后续 PR。</p>
 *
 * <p><b>不变量</b>：</p>
 * <ul>
 *   <li>账本未启用（{@code trunkline.quota.enabled=false}，默认）时本服务全部入口立即返回、
 *       不读写账本、不创建预留凭据——调用方行为与 v3.0.x 完全一致；</li>
 *   <li>判定 / 记账过程中的任何异常都按 fail-open 放行并打告警日志，绝不抛给调用方，
 *       因此配额链路不会产生 5xx；</li>
 *   <li><b>读不到用量不等于零用量</b>：窗口用量用 {@link QuotaLedgerService#usageStrict(QuotaDimension, QuotaWindow)}
 *       读取（读取失败直接抛异常），因此账本不可用时不会拿“默认 0 用量”去判定限额——那既可能凭空
 *       构造超限（错杀请求），也可能放过真实超限。此时统一按 fail-open 放行、不写任何 429 响应头；</li>
 *   <li>不调用也不修改既有限流组件（{@code RateLimitManager}），MINUTE 窗口判定是账本口径的
 *       独立视角，不存在双扣；</li>
 *   <li>限额对应的窗口未在 {@code trunkline.quota.windows} 中启用时，该项限额无法度量，
 *       按 fail-open 跳过（只记录 debug 日志）。</li>
 * </ul>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
@Service
public class QuotaEnforcementService {

    /**
     * 请求属性键：API Key 使用者（{@code ApiKey.holder}），供用户 / 部门额度判定使用。
     *
     * <p>由 {@code ServiceRequestHandler} 认证成功后写入（见其 {@code QUOTA_HOLDER_ATTRIBUTE}
     * 引用），常量归在读取方本类以避免跨类裸用；调用方写属性时直接引用本常量。</p>
     */
    public static final String QUOTA_HOLDER_ATTRIBUTE = "TRUNKLINE_QUOTA_HOLDER";

    /** 请求属性键：holder 所属行政部门（holder→jwt_accounts.department 解析结果）。 */
    public static final String QUOTA_DEPARTMENT_ATTRIBUTE = "TRUNKLINE_QUOTA_DEPARTMENT";

    /**
     * 请求属性键：调用归属租户（API Key 的 {@code tenantId}）。
     *
     * <p>与 holder / department 一样在认证成功后写入请求属性——<b>不能</b>只读
     * {@link TenantContextHolder} 的 ThreadLocal：配额判定在 boundedElastic 线程执行，
     * ThreadLocal 跨线程丢失会回落 default，导致租户维被 sentinel 静默跳过。</p>
     */
    public static final String QUOTA_TENANT_ATTRIBUTE = "TRUNKLINE_TENANT_ID";

    private final QuotaLedgerService ledgerService;
    private final QuotaProperties properties;
    private final ApiKeyService apiKeyService;
    private final Clock clock;

    /** 分组（模型分组 / 池）维度限额解析器（未装配时为 {@code null}，该维度按无限制放行） */
    @Autowired(required = false)
    @Qualifier("group")
    private QuotaLimitResolver groupLimitResolver;

    /** 模型（已接入模型 / 实例）维度限额解析器（未装配时为 {@code null}，该维度按无限制放行） */
    @Autowired(required = false)
    @Qualifier("model")
    private QuotaLimitResolver modelLimitResolver;

    /** 用户维度额度解析器（按 Key.holder 归集，未装配时该维度跳过） */
    @Autowired(required = false)
    @Qualifier("user")
    private QuotaLimitResolver userLimitResolver;

    /** 部门维度额度解析器（按 holder 所属部门归集，未装配时该维度跳过） */
    @Autowired(required = false)
    @Qualifier("department")
    private QuotaLimitResolver departmentLimitResolver;

    /** 租户维度额度解析器（按 Key 归属租户归集，未装配时该维度跳过） */
    @Autowired(required = false)
    @Qualifier("tenant")
    private QuotaLimitResolver tenantLimitResolver;

    /**
     * 组织维金额扩展（企业侧 {@code @Primary} 装配；OSS 单仓未装配时为 null，行为零变化）。
     *
     * <p>必须用字段注入而非构造器强注入，否则 OSS 单仓启动即缺 Bean 失败；
     * 与既有 resolver 的 fail-soft 范式一致。</p>
     */
    @Autowired(required = false)
    private QuotaCheckExtension extension;

    /**
     * 单进程内按 API Key 串行化「判定 + 预留」，消除 check-then-act 双花窗口。
     * 多实例分布式计数仍依赖预留后二次核对 + 回滚（见 {@link #tryReserve}）。
     */
    private final ConcurrentHashMap<String, Object> reserveLocks = new ConcurrentHashMap<>();

    /**
     * Spring 使用的主构造器。
     *
     * @param ledgerService 账本服务
     * @param properties    账本配置（读取启用的窗口）
     * @param apiKeyService API Key 服务（读取限额配置，未装配时为 {@code null}，此时按无限制放行）
     */
    @Autowired
    public QuotaEnforcementService(final QuotaLedgerService ledgerService,
                                   final QuotaProperties properties,
                                   @Autowired(required = false) final ApiKeyService apiKeyService) {
        this(ledgerService, properties, apiKeyService, Clock.systemDefaultZone());
    }

    /**
     * 可注入时钟的构造器，便于测试固定窗口边界与 {@code Retry-After}。
     *
     * @param ledgerService 账本服务
     * @param properties    账本配置
     * @param apiKeyService API Key 服务，可为 {@code null}
     * @param clock         时钟
     */
    public QuotaEnforcementService(final QuotaLedgerService ledgerService,
                                   final QuotaProperties properties,
                                   final ApiKeyService apiKeyService,
                                   final Clock clock) {
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.apiKeyService = apiKeyService;
        this.clock = clock;
    }

    /**
     * 配额能力是否启用（透传账本开关）。
     *
     * @return {@code trunkline.quota.enabled} 的值，默认 {@code false}
     */
    public boolean isEnabled() {
        return ledgerService.isEnabled();
    }

    /**
     * 判定本次请求是否已超限（只读，不记账、不挂载凭据）。
     *
     * <p>判定标准是 {@code 窗口已用量 + 本次估算 token > 限额}（严格大于：恰好用满不算超限）。
     * 窗口用量经 {@link QuotaLedgerService#usageStrict(QuotaDimension, QuotaWindow)} 读取，
     * 读不到用量（账本不可用）时不把缺失当成 0，而是直接降级放行。</p>
     *
     * <p>租户维度取 {@link TenantContextHolder} 的 ThreadLocal——<b>仅适合</b>测试或
     * 网关线程（租户上下文可靠）上的调用；boundedElastic 等工作线程上 ThreadLocal 已丢失
     * （回落 default），生产链路请用带显式租户参数的
     * {@link #evaluate(String, String, long)} 重载，或走 {@link #tryReserve}
     * （按「请求属性 &gt; ThreadLocal」解析租户）。</p>
     *
     * @param apiKeyId        API Key ID，{@code null} 或空串时直接放行
     * @param estimatedTokens 本次请求估算 token 数（负数按 0 处理）
     * @return 超限时返回被命中的限额；未超限、账本未启用、Key 未知、账本读不到用量或判定异常
     *         （fail-open）时返回 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> evaluate(final String apiKeyId, final long estimatedTokens) {
        return evaluate(apiKeyId, TenantContextHolder.currentTenantId(), estimatedTokens);
    }

    /**
     * 判定本次请求是否已超限（显式租户参数变体，跨线程安全）。
     *
     * <p>2026-11-27（中风险整改）：旧 {@link #evaluate(String, long)} 在工作线程上会因
     * ThreadLocal 丢失把账本维度落到 default 租户。本重载要求调用方显式传入租户 ID
     * （认证后写入的请求属性或 Key 绑定租户），传 {@code null} / 空串时按账本租户哨兵
     * {@code default} 归一（与 {@link QuotaDimension} 的归一口径一致）。</p>
     *
     * @param apiKeyId        API Key ID，{@code null} 或空串时直接放行
     * @param tenantId        调用归属租户（显式传入，不读 ThreadLocal）
     * @param estimatedTokens 本次请求估算 token 数（负数按 0 处理）
     * @return 超限时返回被命中的限额；未超限、账本未启用、Key 未知、账本读不到用量或判定异常
     *         （fail-open）时返回 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> evaluate(final String apiKeyId, final String tenantId,
                                                  final long estimatedTokens) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return Optional.empty();
        }
        try {
            final ApiKey apiKey = resolveApiKey(apiKeyId);
            if (apiKey == null) {
                log.debug("配额判定跳过：未找到 API Key 配置，按无限制放行: apiKeyId={}", apiKeyId);
                return Optional.empty();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            final List<QuotaWindow> windows = properties.enabledWindows();
            final QuotaDimension dimension = QuotaDimension.ofApiKey(apiKeyId, tenantId);
            final long tokens = Math.max(0L, estimatedTokens);

            // 判定顺序：HOUR → DAY → MONTH → MINUTE（对齐上游 3.4.0 的优先级说明）
            Optional<QuotaLimitViolation> violation = evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.HOUR, apiKey.getHourlyRequestLimit(), apiKey.getHourlyTokenLimit(),
                QuotaLimitViolation.METRIC_HOURLY_REQUESTS, QuotaLimitViolation.METRIC_HOURLY_TOKENS,
                tokens, now);
            if (violation.isPresent()) {
                return violation;
            }
            violation = evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.DAY, apiKey.getDailyRequestLimit(), apiKey.getDailyTokenLimit(),
                QuotaLimitViolation.METRIC_DAILY_REQUESTS, QuotaLimitViolation.METRIC_DAILY_TOKENS,
                tokens, now);
            if (violation.isPresent()) {
                return violation;
            }
            violation = evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.MONTH, apiKey.getMonthlyRequestLimit(), apiKey.getMonthlyTokenLimit(),
                QuotaLimitViolation.METRIC_MONTHLY_REQUESTS, QuotaLimitViolation.METRIC_MONTHLY_TOKENS,
                tokens, now);
            if (violation.isPresent()) {
                return violation;
            }
            return evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.MINUTE, apiKey.getRateLimitPerMinute(), 0L,
                QuotaLimitViolation.METRIC_RATE_PER_MINUTE, null, 0L, now);
        } catch (Exception e) {
            log.warn("配额限额判定异常，按 fail-open 放行: apiKeyId={}, error={}", apiKeyId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * N-2: 账本未启用时的日 Token 配额请求前预估兜底判定.
     *
     * <p>账本关闭({@code trunkline.quota.enabled=false},默认)时,主链路退化为
     * 「认证时按历史 {@code dailyTokenUsage} 校验 + 响应后入账」——单次请求的消耗
     * 在放行时点尚不可知,一次大请求即可把当日额度撑爆(实测:limit=224/已用 214,
     * 本次消耗 154 仍放行,累计 368 超限 65%,仅下一次才被拒)。</p>
     *
     * <p>本方法在请求转发前按 {@link QuotaTokenEstimator#estimateUpperBound(Object)}
     * （prompt 估算 + {@code max_tokens} 输出上限）判定:
     * {@code 当日已用 + 本次估算 > dailyTokenLimit} 即 429。不触碰账本、不创建预留,
     * 与启用态判定结果形态一致({@link QuotaLimitViolation} + Retry-After 到日窗口重置)。</p>
     *
     * <p>只覆盖 Token 维度:请求数({@code dailyRequestLimit})由认证链路实时判定,
     * 每分钟限流由 {@code RateLimitManager} 实时判定,均无「事后入账」时延;
     * N-6 修复后被拒请求也不再计入当日用量,请求数口径已闭合。</p>
     *
     * @param apiKeyId   API Key ID
     * @param requestDto 原始请求 DTO（用于估算上限），可为 {@code null}
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> checkEstimatedTokenQuota(final String apiKeyId,
                                                                  final Object requestDto) {
        if (apiKeyId == null || apiKeyId.isEmpty()) {
            return Optional.empty();
        }
        final long estimatedUpperBound = QuotaTokenEstimator.estimateUpperBound(requestDto);
        if (estimatedUpperBound <= 0L) {
            // 无估算信息(非 chat DTO / 空 messages / 无 max_tokens):无法预判,放行
            return Optional.empty();
        }
        try {
            final ApiKey apiKey = resolveApiKey(apiKeyId);
            if (apiKey == null || apiKey.getDailyTokenLimit() <= 0L) {
                return Optional.empty();
            }
            final String today = LocalDateTime.now(clock).toLocalDate().toString();
            long todayUsed = 0L;
            if (apiKey.getUsage() != null && apiKey.getUsage().getDailyTokenUsage() != null) {
                final Long used = apiKey.getUsage().getDailyTokenUsage().get(today);
                todayUsed = used != null ? used : 0L;
            }
            if (todayUsed + estimatedUpperBound > apiKey.getDailyTokenLimit()) {
                final LocalDateTime now = LocalDateTime.now(clock);
                final LocalDateTime nextDay = QuotaWindow.DAY.windowStart(now).plusDays(1L);
                final long millis = Math.max(0L, Duration.between(now, nextDay).toMillis());
                final long retryAfter = Math.max(1L, (millis + 999L) / 1000L);
                log.warn("N-2 预估 Token 配额超限，拒绝请求: apiKeyId={}, used={}, estimated={}, limit={}",
                        apiKeyId, todayUsed, estimatedUpperBound, apiKey.getDailyTokenLimit());
                return Optional.of(new QuotaLimitViolation(
                        QuotaLimitViolation.METRIC_DAILY_TOKENS,
                        QuotaWindow.DAY,
                        apiKey.getDailyTokenLimit(),
                        todayUsed,
                        retryAfter,
                        "API Key " + apiKeyId));
            }
        } catch (Exception e) {
            log.warn("N-2 Token 配额预估校验异常，按放行处理: apiKeyId={}, error={}", apiKeyId, e.toString());
        }
        return Optional.empty();
    }

    /**
     * 判定并在放行时完成预留（仅 API Key 维度，兼容既有调用点）。
     *
     * <p>等价于 {@link #tryReserve(ServerHttpRequest, String, String, String, long)} 且分组 / 模型
     * 维度均为空——即只受 Key 总闸约束。</p>
     *
     * @param request         请求（用于挂载结算凭据），可为 {@code null}
     * @param apiKeyId        API Key ID（总闸）
     * @param estimatedTokens 本次请求估算 token 数
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> tryReserve(final ServerHttpRequest request,
                                                    final String apiKeyId,
                                                    final long estimatedTokens) {
        return tryReserve(request, apiKeyId, "", "", estimatedTokens);
    }

    /**
     * 判定并在放行时完成预留（多维度判定 + 记账 + 挂载结算凭据）。
     *
     * <p>三维度（Key 总闸 / 分组 / 模型）各自独立预算，任一维度超限即返回 429，并回滚本请求已在
     * 其它维度成功预留的增量（照搬既有“多窗口回滚”思路，按维度逐条冲正）。Key 维度恒生效；
     * 分组与模型维度互斥（一次请求只走其一），对应解析器未装配时该维度按无限制放行。</p>
     *
     * <p>返回空表示“放行且已完成预留”（或账本未启用 / 判定异常降级放行）；返回超限结果表示
     * 调用方应拒绝本次请求（429），本方法此时不记账。</p>
     *
     * @param request         请求（用于挂载结算凭据），可为 {@code null}
     * @param apiKeyId        API Key ID（总闸）
     * @param groupId         分组（模型分组 / 池）名称，无则传空串
     * @param model           模型（已接入模型 / 实例）名称，无则传空串
     * @param estimatedTokens 本次请求估算 token 数
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> tryReserve(final ServerHttpRequest request,
                                                    final String apiKeyId,
                                                    final String groupId,
                                                    final String model,
                                                    final long estimatedTokens) {
        // 5 参存量调用点：从请求属性显式取 holder / department（认证后写入）。
        // 不在此处填死空串——否则用户 / 部门额度永远读不到归属、静默失效。
        return tryReserve(request, apiKeyId,
                attr(request, QUOTA_HOLDER_ATTRIBUTE),
                attr(request, QUOTA_DEPARTMENT_ATTRIBUTE),
                groupId, model, estimatedTokens);
    }

    /**
     * 判定并在放行时完成预留（多维度判定 + 记账 + 挂载结算凭据）。
     *
     * <p>在既有 Key / 分组 / 模型三维基础上，追加三层组织维（均由灰度开关控制、默认关）：</p>
     * <ul>
     *   <li><b>用户</b>：{@code holder} 非空且 {@code userScopeEnabled} 开时，按 holder 跨 Key 归集；</li>
     *   <li><b>部门</b>：{@code department} 非空且 {@code departmentScopeEnabled} 开时，按行政部门归集；</li>
     *   <li><b>租户</b>：当前租户非 default 且 {@code tenantScopeEnabled} 开时，按归属租户归集。</li>
     * </ul>
     *
     * <p>任一维度超限即返回 429，并回滚本请求已在其它维度成功预留的增量。维度可能并存
     * （Key / 用户 / 部门 / 租户 + 分组或模型），不再互斥。</p>
     *
     * @param request         请求（用于挂载结算凭据），可为 {@code null}
     * @param apiKeyId        API Key ID（总闸）
     * @param holder          使用者（ApiKey.holder），无则空串
     * @param department      holder 所属部门，无则空串
     * @param groupId         分组（模型分组 / 池）名称，无则传空串
     * @param model           模型（已接入模型 / 实例）名称，无则传空串
     * @param estimatedTokens 本次请求估算 token 数
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> tryReserve(final ServerHttpRequest request,
                                                    final String apiKeyId,
                                                    final String holder,
                                                    final String department,
                                                    final String groupId,
                                                    final String model,
                                                    final long estimatedTokens) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return Optional.empty();
        }
        final Object lock = reserveLocks.computeIfAbsent(apiKeyId, key -> new Object());
        synchronized (lock) {
            try {
                final long tokens = Math.max(0L, estimatedTokens);
                // 组装维度与各自限额：Key 恒生效；组织维 / 分组 / 模型按需
                final List<QuotaDimension> dimensions = new ArrayList<>();
                final List<QuotaLimitSet> limits = new ArrayList<>();
                // 租户来源优先级：请求属性（认证后写入，跨线程安全）> ThreadLocal 回退。
                // 配额判定在 boundedElastic 线程执行，TenantContextHolder 的 ThreadLocal
                // 在该线程已丢失（回落 default）——只读它会让租户维被 sentinel 静默跳过。
                String tenantId = attr(request, QUOTA_TENANT_ATTRIBUTE);
                if (tenantId.isEmpty()) {
                    tenantId = TenantContextHolder.currentTenantId();
                }
                dimensions.add(QuotaDimension.ofApiKey(apiKeyId, tenantId));
                limits.add(limitsOf(resolveApiKey(apiKeyId)));

                // 组织维三层：逐维灰度开关 + 归属非空才参与；与总开关 isEnabled() 是「与」关系
                if (properties.isUserScopeEnabled() && holder != null && !holder.isEmpty()) {
                    dimensions.add(QuotaDimension.ofUser(holder));
                    limits.add(resolveUserLimit(holder));
                }
                if (properties.isDepartmentScopeEnabled() && department != null && !department.isEmpty()) {
                    dimensions.add(QuotaDimension.ofDepartment(department));
                    limits.add(resolveDepartmentLimit(department));
                }
                // 租户维：Key 未绑非 default 租户时跳过（与行级过滤器开关无关）
                if (properties.isTenantScopeEnabled()
                        && tenantId != null && !tenantId.isEmpty()
                        && !QuotaDimension.TENANT_SENTINEL.equals(tenantId)) {
                    dimensions.add(QuotaDimension.ofTenant(tenantId));
                    limits.add(resolveTenantLimit(tenantId));
                }

                if (groupId != null && !groupId.isEmpty()) {
                    dimensions.add(QuotaDimension.ofGroup(groupId));
                    limits.add(resolveGroupLimit(groupId));
                }
                if (model != null && !model.isEmpty()) {
                    dimensions.add(QuotaDimension.ofModel(model));
                    limits.add(resolveModelLimit(model));
                }

                // 逐维度预留；超限则回滚已成功维度（避免少扣 / 多扣）
                final List<QuotaDimension> committed = new ArrayList<>();
                for (int i = 0; i < dimensions.size(); i++) {
                    final QuotaDimension dim = dimensions.get(i);
                    final QuotaLimitSet limit = limits.get(i);
                    if (limit == null || limit.equals(QuotaLimitSet.UNLIMITED)) {
                        // 该维度无有效限额：仅记账（不判定），保证计数连续
                        ledgerService.reserve(QuotaRequest.of(dim, tokens));
                        committed.add(dim);
                        continue;
                    }
                    final Optional<QuotaLimitViolation> violation = ledgerService.reserveWithLimits(
                            QuotaRequest.of(dim, tokens), limit);
                    if (violation.isPresent()) {
                        rollbackDimensions(committed, tokens);
                        log.warn("配额超限，拒绝请求: apiKeyId={}, scope={}, {}",
                                apiKeyId, dim, violation.get().describeDetailed());
                        return violation;
                    }
                    committed.add(dim);
                }
                // 组织维金额层（企业扩展）：token 逐维预留完成后才判金额；
                // 拒绝按三条契约处理——只用返回值、金额冲正 + token 回滚、异常兜底拒绝。
                // 租户取上面已解析的 tenantId（请求属性优先），不能让 buildContext 再读
                // ThreadLocal——boundedElastic 上会回落 default，租户金额账静默记错租户。
                final QuotaCheckContext ctx = buildContext(
                        tenantId, apiKeyId, holder, department, groupId, model, tokens);
                final Optional<QuotaLimitViolation> denied;
                try {
                    denied = extension == null ? Optional.empty() : extension.preCheck(ctx);
                } catch (RuntimeException e) {
                    // 原子 CAS 下不存在部分写：异常时要么已预占（amountMinor 已设，冲正有效），
                    // 要么未写账（amountMinor=0，onRolledBack 按纪律空操作），不会冲出负预占。
                    log.error("金额扩展异常，按 fail-closed 拒绝: apiKeyId={}, error={}",
                            apiKeyId, e.toString());
                    rollbackAmountQuietly(extension, ctx);
                    rollbackDimensions(committed, tokens);
                    return Optional.of(amountExtensionUnavailable());
                }
                if (denied.isPresent()) {
                    boolean amountRolledBack = true;
                    try {
                        if (extension != null) {
                            extension.onRolledBack(ctx, ctx.amountMinor());
                        }
                    } catch (RuntimeException e) {
                        log.error("金额预留冲正失败（需告警）: apiKeyId={}, error={}",
                                apiKeyId, e.toString());
                        amountRolledBack = false;
                    }
                    // 两条账独立：金额冲正失败也继续回滚 token，不能放任 token 留残量
                    rollbackDimensions(committed, tokens);
                    return amountRolledBack ? denied : Optional.of(amountRollbackFailed());
                }

                final QuotaReservation reservation =
                        new QuotaReservation(ledgerService, dimensions, tokens, ctx, extension);
                // 评审修复（中危）：挂载失败（属性表不可写等）若照旧放行，结算侧拿不到凭据
                // → 金额预占永不冲正/结算（泄漏）。挂载失败必须全量回滚（金额 + token）并拒绝。
                final boolean attached = QuotaReservation.attach(request, reservation);
                if (!attached || QuotaReservation.from(request) != reservation) {
                    log.error("配额预留凭据挂载失败（或回读不一致），全量回滚并拒绝: apiKeyId={}, "
                            + "amountMinor={}, tokens={}", apiKeyId, ctx.amountMinor(), tokens);
                    rollbackAmountQuietly(extension, ctx);
                    rollbackDimensions(committed, tokens);
                    return Optional.of(amountExtensionUnavailable());
                }
                // onReserved 只做通知，不写账；失败只告警，不影响放行
                if (extension != null) {
                    try {
                        extension.onReserved(ctx, ctx.amountMinor());
                    } catch (RuntimeException e) {
                        log.error("金额预占回调失败（需告警）: apiKeyId={}, error={}",
                                apiKeyId, e.toString());
                    }
                }
                return Optional.empty();
            } catch (Exception e) {
                log.warn("配额预留异常，按 fail-open 放行: apiKeyId={}, error={}", apiKeyId, e.toString());
                return Optional.empty();
            }
        }
    }

    /** 从请求属性读取字符串值，缺失或非字符串返回空串。 */
    private static String attr(final ServerHttpRequest request, final String key) {
        if (request == null) {
            return "";
        }
        final Object value = request.getAttributes().get(key);
        return value instanceof String s ? s : "";
    }

    /**
     * 构造金额扩展上下文（租户 / 归属 / 估算 token / 判定时点全部来自 OSS 中性字段）。
     *
     * <p>租户由调用方显式传入（tryReserve 已按「请求属性 &gt; ThreadLocal」解析过）；
     * 本方法绝不能再读 {@link TenantContextHolder}——配额判定在 boundedElastic 线程执行，
     * ThreadLocal 跨线程丢失会回落 default，企业金额账会把租户维记错租户。</p>
     */
    private QuotaCheckContext buildContext(final String tenantId, final String apiKeyId,
                                           final String holder, final String department,
                                           final String groupId, final String model,
                                           final long tokens) {
        return new QuotaCheckContext(tenantId, apiKeyId,
                holder, department, groupId, model, tokens, LocalDateTime.now(clock));
    }

    /** 金额扩展不可用时的中性拒绝结果（metric 不甩锅给具体主体）。 */
    private static QuotaLimitViolation amountExtensionUnavailable() {
        return new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_AMOUNT_EXT_UNAVAILABLE,
                QuotaWindow.MONTH, 0L, 1L, 3600L, "金额额度", "AMOUNT", "extension");
    }

    /** 金额冲正失败时的中性拒绝结果（fail-closed 不打折）。 */
    private static QuotaLimitViolation amountRollbackFailed() {
        return new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_AMOUNT_EXT_UNAVAILABLE,
                QuotaWindow.MONTH, 0L, 1L, 3600L, "金额额度", "AMOUNT", "rollback");
    }

    /** 金额冲正的异常兜底（自身 try-catch，失败只告警不阻断 token 回滚）。 */
    private static void rollbackAmountQuietly(final QuotaCheckExtension ext,
                                              final QuotaCheckContext ctx) {
        if (ext == null || ctx == null) {
            return;
        }
        try {
            ext.onRolledBack(ctx, ctx.amountMinor());
        } catch (RuntimeException e) {
            log.error("金额预留冲正失败（需告警）: error={}", e.toString());
        }
    }

    /**
     * 组织维（用户 / 部门）归属解析是否需要：总开关 + 至少一个组织维开关打开。
     *
     * <p>供认证时机门控 holder→department 解析——两个组织维开关全关时零开销、零 DB。</p>
     */
    public boolean isOrgScopeResolutionNeeded() {
        return isEnabled() && (properties.isUserScopeEnabled() || properties.isDepartmentScopeEnabled());
    }

    /** 部门额度开关是否打开（认证时机决定是否解析 department 用）。 */
    public boolean isDepartmentScopeEnabled() {
        return isEnabled() && properties.isDepartmentScopeEnabled();
    }

    /**
     * 回滚已成功预留的多个维度（按维度逐条冲正，失败只告警）。
     *
     * @param committed 已成功预留的维度列表
     * @param tokens    预留的估算 token 数
     */
    private void rollbackDimensions(final List<QuotaDimension> committed, final long tokens) {
        for (final QuotaDimension dim : committed) {
            try {
                ledgerService.settle(new QuotaSettlement(dim, tokens, tokens, true));
            } catch (Exception e) {
                log.warn("配额多维度预留回滚失败（残量留待窗口过期回收）: scope={}, error={}", dim, e.toString());
            }
        }
    }

    /**
     * 解析分组（模型分组 / 池）维度的限额集合。
     *
     * @param groupId 分组（池）名称
     * @return 限额集合；解析器缺失或解析异常时返回 {@code null}（按无限制放行）
     */
    private QuotaLimitSet resolveGroupLimit(final String groupId) {
        if (groupLimitResolver == null) {
            return null;
        }
        try {
            return groupLimitResolver.resolve(groupId).orElse(null);
        } catch (Exception e) {
            log.warn("解析分组配额限额失败，按无限制放行: groupId={}, error={}", groupId, e.toString());
            return null;
        }
    }

    /**
     * 解析用户维度的限额集合（未装配 / 解析异常时按无限制放行）。
     */
    private QuotaLimitSet resolveUserLimit(final String holder) {
        if (userLimitResolver == null) {
            return null;
        }
        try {
            return userLimitResolver.resolve(holder).orElse(null);
        } catch (Exception e) {
            log.warn("解析用户配额限额失败，按无限制放行: holder={}, error={}", holder, e.toString());
            return null;
        }
    }

    /**
     * 解析部门维度的限额集合（未装配 / 解析异常时按无限制放行）。
     */
    private QuotaLimitSet resolveDepartmentLimit(final String department) {
        if (departmentLimitResolver == null) {
            return null;
        }
        try {
            return departmentLimitResolver.resolve(department).orElse(null);
        } catch (Exception e) {
            log.warn("解析部门配额限额失败，按无限制放行: department={}, error={}", department, e.toString());
            return null;
        }
    }

    /**
     * 解析租户维度的限额集合（未装配 / 解析异常时按无限制放行）。
     */
    private QuotaLimitSet resolveTenantLimit(final String tenantId) {
        if (tenantLimitResolver == null) {
            return null;
        }
        try {
            return tenantLimitResolver.resolve(tenantId).orElse(null);
        } catch (Exception e) {
            log.warn("解析租户配额限额失败，按无限制放行: tenantId={}, error={}", tenantId, e.toString());
            return null;
        }
    }

    /**
     * 解析模型（已接入模型 / 实例）维度的限额集合。
     *
     * <p><b>对外可见</b>：厂商模型维度的额度/限速只有在选完实例后才可判定（预留阶段只有虚拟模型名），
     * 因此请求处理器需要在选实例后调用本方法取限额，再交给
     * {@link QuotaReservation#appendDimensionWithLimits(QuotaDimension, QuotaLimitSet)}
     * 做「判定 + 记账」一次完成。</p>
     *
     * @param model 模型（实例）名称
     * @return 限额集合；解析器缺失或解析异常时返回 {@code null}（按无限制放行）
     */
    public QuotaLimitSet resolveModelLimit(final String model) {
        if (modelLimitResolver == null) {
            return null;
        }
        try {
            return modelLimitResolver.resolve(model).orElse(null);
        } catch (Exception e) {
            log.warn("解析模型配额限额失败，按无限制放行: model={}, error={}", model, e.toString());
            return null;
        }
    }

    /**
     * 单个窗口的限额判定（请求数 + token）。
     *
     * <p>四个窗口（HOUR / DAY / MONTH / MINUTE）共用这一份逻辑，避免各写一遍后出现口径漂移：
     * 窗口未启用则跳过（无法度量 ⇒ fail-open）；两项限额都 {@code <= 0} 时不读账本直接放行；
     * 命中条件为 {@code 已用量 + 本笔 > 限额}（严格大于：恰好用满不算超限）。</p>
     *
     * @param apiKey            限额配置
     * @param dimension         账本维度
     * @param windows           启用的窗口
     * @param window            当前判定的窗口
     * @param maxRequests       该窗口请求数上限（{@code <= 0} 不限）
     * @param maxTokens         该窗口 token 上限（{@code <= 0} 不限）
     * @param requestMetricName 请求数超限时的指标名
     * @param tokenMetricName   token 超限时的指标名（无 token 限额的窗口传 {@code null}）
     * @param estimatedTokens   本次估算 token 数（MINUTE 窗口传 0）
     * @param now               当前时间
     * @return 超限结果，未超限或窗口未启用时为空
     * @throws IllegalStateException 账本读不到该窗口用量（由 {@link #evaluate} 统一按 fail-open 处理）
     */
    private Optional<QuotaLimitViolation> evaluateWindow(final ApiKey apiKey,
                                                         final QuotaDimension dimension,
                                                         final List<QuotaWindow> windows,
                                                         final QuotaWindow window,
                                                         final long maxRequests,
                                                         final long maxTokens,
                                                         final String requestMetricName,
                                                         final String tokenMetricName,
                                                         final long estimatedTokens,
                                                         final LocalDateTime now) {
        if (!windows.contains(window)) {
            log.debug("{} 窗口未启用，跳过限额判定: apiKeyId={}", window, apiKey.getKeyId());
            return Optional.empty();
        }
        if (maxRequests <= 0L && maxTokens <= 0L) {
            return Optional.empty();
        }
        final Optional<QuotaUsage> usage = ledgerService.usageStrict(dimension, window);
        final long requests = usage.map(QuotaUsage::requestCount).orElse(0L);
        final long tokens = usage.map(QuotaUsage::tokenCount).orElse(0L);

        if (maxRequests > 0L && requests + 1L > maxRequests) {
            return Optional.of(violation(requestMetricName, window, maxRequests, requests, now));
        }
        if (tokenMetricName != null && maxTokens > 0L && tokens + estimatedTokens > maxTokens) {
            return Optional.of(violation(tokenMetricName, window, maxTokens, tokens, now));
        }
        return Optional.empty();
    }

    /**
     * 从 API Key 配置打包限额集合（供 CAS 预留与判定共用，对齐上游 3.4.0）。
     *
     * <p>配置缺失（{@code apiKey == null}）时返回 {@link QuotaLimitSet#UNLIMITED}：
     * Key 维度仍然只记账、不判定，其余维度（分组 / 模型）的限额照常生效。</p>
     *
     * @param apiKey 限额配置，可为 {@code null}
     * @return 限额集合（各窗口 {@code <= 0} 表示不限）
     */
    private QuotaLimitSet limitsOf(final ApiKey apiKey) {
        if (apiKey == null) {
            return QuotaLimitSet.UNLIMITED;
        }
        return new QuotaLimitSet(
                apiKey.getRateLimitPerMinute(),
                apiKey.getHourlyRequestLimit(),
                apiKey.getHourlyTokenLimit(),
                apiKey.getDailyRequestLimit(),
                apiKey.getDailyTokenLimit(),
                apiKey.getMonthlyRequestLimit(),
                apiKey.getMonthlyTokenLimit());
    }

    /**
     * 构造超限结果（含距下一窗口边界的秒数）。
     *
     * @param metric 限额指标名
     * @param window 窗口
     * @param limit  限额配置值
     * @param used   已用额度
     * @param now    当前时间
     * @return 超限结果
     */
    private QuotaLimitViolation violation(final String metric,
                                          final QuotaWindow window,
                                          final long limit,
                                          final long used,
                                          final LocalDateTime now) {
        // 2026-11-22：本工厂仅用于 API Key 维度判定，显式传标签避免 describe() 回退为中性文案
        return new QuotaLimitViolation(metric, window, limit, used,
                retryAfterSeconds(window, now), "API Key");
    }

    /**
     * 计算距窗口重置的秒数（向上取整，最小 1 秒）。
     *
     * @param window 窗口
     * @param now    当前时间
     * @return 距下一窗口边界的秒数
     */
    private long retryAfterSeconds(final QuotaWindow window, final LocalDateTime now) {
        final LocalDateTime start = window.windowStart(now);
        final LocalDateTime next;
        switch (window) {
            case MINUTE:
                next = start.plusMinutes(1L);
                break;
            case HOUR:
                next = start.plusHours(1L);
                break;
            case MONTH:
                next = start.plusMonths(1L);
                break;
            default:
                next = start.plusDays(1L);
                break;
        }
        final long millis = Math.max(0L, Duration.between(now, next).toMillis());
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    /**
     * 按 API Key ID 解析限额配置（沿用管理侧 {@code ApiKeyQuotaService} 的索引方式）。
     *
     * @param apiKeyId API Key ID
     * @return 限额配置；索引缺失或读取异常时返回 {@code null}（调用方按无限制放行）
     */
    private ApiKey resolveApiKey(final String apiKeyId) {
        if (apiKeyService == null) {
            return null;
        }
        try {
            final String keyHash = apiKeyService.getKeyIdIndex().get(apiKeyId);
            if (keyHash == null) {
                return null;
            }
            return apiKeyService.getApiKeyCache().get(keyHash);
        } catch (Exception e) {
            log.warn("读取 API Key 限额配置失败，按无限制放行: apiKeyId={}, error={}", apiKeyId, e.toString());
            return null;
        }
    }

    /**
     * 按 Key 删除事件回收预留锁（D3）。
     *
     * <p>API Key 被手动删除（{@code REVOKED}）时，其对应的 {@code reserveLocks} 条目已无业务意义，
     * 若不回收会随 Key 数量增长而无限膨胀（{@code computeIfAbsent} 永不删除）。监听删除事件即时清理，
     * 避免锁表无上限。</p>
     *
     * <p>删除发生在缓存变更之后，此时该 Key 已不可达，回收其锁不会中断任何在途预留
     * （在途预留持有的是旧锁对象引用，不影响本请求的 {@code synchronized} 临界区）。</p>
     *
     * @param event API Key 审计事件
     */
    @EventListener
    public void onApiKeyRevoked(final ApiKeyAuditEvent event) {
        if (event == null || event.eventType() != ApiKeyAuditEvent.EventType.REVOKED) {
            return;
        }
        final String apiKeyId = event.keyId();
        if (apiKeyId == null || apiKeyId.isEmpty()) {
            return;
        }
        if (reserveLocks.remove(apiKeyId) != null) {
            log.debug("回收配额预留锁（API Key 已删除）: apiKeyId={}", apiKeyId);
        }
    }
}
