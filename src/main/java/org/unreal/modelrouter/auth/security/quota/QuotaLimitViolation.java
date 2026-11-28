package org.unreal.modelrouter.auth.security.quota;

/**
 * 配额超限判定结果（v3.1 PR-2）。
 *
 * <p>限额来自既有 {@code ApiKey} 配置，一次判定只报告“最先命中”的那一项限额（判定优先级：
 * 日请求数 → 日 token 数 → 每分钟请求数），因此本记录同时携带被命中的指标名与窗口，
 * 供 429 响应头（{@code X-Quota-Limit} / {@code X-Quota-Remaining} / {@code X-Quota-Window}）
 * 与日志使用。</p>
 *
 * @param metric            被命中的限额指标名，取值见 {@link #METRIC_DAILY_REQUESTS} 等常量
 * @param window            被命中的窗口（日限额对应 {@link QuotaWindow#DAY}，速率限额对应
 *                          {@link QuotaWindow#MINUTE}）
 * @param limit             该项限额的配置值（{@code > 0}）
 * @param used              判定时该窗口内的已用额度（{@link #METRIC_DAILY_REQUESTS} /
 *                          {@link #METRIC_RATE_PER_MINUTE} 为请求数，{@link #METRIC_DAILY_TOKENS}
 *                          为 token 数）
 * @param retryAfterSeconds 距离窗口重置（下一窗口边界）的秒数，最小为 1
 * @param scopeLabel        命中维度的可读标签（如 {@code API Key} / {@code 模型分组 xxx} /
 *                          {@code 厂商模型 xxx}）；为空时 {@link #describe()} 回退为通用文案。
 *                          2026-11-22：用于修正「池超限却报 API Key quota exceeded」的错标。
 * @param scopeType         命中主体类型（{@code API_KEY} / {@code USER} / {@code DEPARTMENT} /
 *                          {@code TENANT} / {@code AMOUNT} 等），为空表示旧调用未携带；
 *                          2026-11-27 金额层四元组新增
 * @param scopeId           命中主体标识（Key ID / 用户名 / 部门名 / 租户 ID），可为空
 * @author JAiRouter Team
 */
public record QuotaLimitViolation(String metric,
                                 QuotaWindow window,
                                 long limit,
                                 long used,
                                 long retryAfterSeconds,
                                 String scopeLabel,
                                 String scopeType,
                                 String scopeId) {

    /** 兼容旧 6 参构造（canonical 在 2026-11-27 扩展为 8 参，本构造保留）。 */
    public QuotaLimitViolation(final String metric, final QuotaWindow window,
                               final long limit, final long used, final long retryAfterSeconds,
                               final String scopeLabel) {
        this(metric, window, limit, used, retryAfterSeconds, scopeLabel, null, null);
    }

    /** 兼容旧 5 参构造：不带维度标签，describe() 回退为通用文案。 */
    public QuotaLimitViolation(final String metric, final QuotaWindow window,
                               final long limit, final long used, final long retryAfterSeconds) {
        this(metric, window, limit, used, retryAfterSeconds, null, null, null);
    }

    /** 金额维度超限（企业扩展，429 配额口径，不再使用 402）。 */
    public static final String METRIC_AMOUNT = "amount";

    /** 金额扩展不可用 / 冲正失败时的中性指标名（不甩锅给具体主体）。 */
    public static final String METRIC_AMOUNT_EXT_UNAVAILABLE = "amountExtensionUnavailable";

    /** 日请求数限额（{@code ApiKey.dailyRequestLimit}） */
    public static final String METRIC_DAILY_REQUESTS = "dailyRequestLimit";

    /** 日 token 限额（{@code ApiKey.dailyTokenLimit}） */
    public static final String METRIC_DAILY_TOKENS = "dailyTokenLimit";

    /** 每小时请求数限额（{@code ApiKey.hourlyRequestLimit}，对齐上游 3.4.0） */
    public static final String METRIC_HOURLY_REQUESTS = "hourlyRequestLimit";

    /** 每小时 token 限额（{@code ApiKey.hourlyTokenLimit}） */
    public static final String METRIC_HOURLY_TOKENS = "hourlyTokenLimit";

    /** 每月请求数限额（{@code ApiKey.monthlyRequestLimit}） */
    public static final String METRIC_MONTHLY_REQUESTS = "monthlyRequestLimit";

    /** 每月 token 限额（{@code ApiKey.monthlyTokenLimit}） */
    public static final String METRIC_MONTHLY_TOKENS = "monthlyTokenLimit";

    /** 每分钟请求限额（{@code ApiKey.rateLimitPerMinute}） */
    public static final String METRIC_RATE_PER_MINUTE = "rateLimitPerMinute";

    /**
     * 剩余额度（非负钳制）：已用额度超过配置值时返回 0。
     *
     * @return 剩余额度
     */
    public long remaining() {
        return Math.max(0L, limit - used);
    }

    /**
     * 生成 429 响应体中使用的说明文本（响应体本身仍由仓库既有错误响应惯例渲染）。
     *
     * <p>2026-11-22：前缀按 {@link #scopeLabel} 渲染，修正「池超限却报 API Key quota
     * exceeded」的错标；未带标签时回退为中性 {@code Quota exceeded}，不再默认甩锅给 API Key。</p>
     *
     * <p>2026-11-27（中风险整改）：本方法直接进入 429 响应体（{@code ResponseStatusException}
     * 的 reason），<b>不再拼接 {@code scopeType} / {@code scopeId}</b>——它们可能携带租户 ID、
     * Key ID、部门名等内部标识，外泄给客户端属于信息暴露。服务端日志诊断请用
     * {@link #describeDetailed()}。</p>
     *
     * @return 说明文本，如 {@code "模型分组 my-pool quota exceeded: metric=dailyRequestLimit, ..."}
     */
    public String describe() {
        final String prefix = (scopeLabel == null || scopeLabel.isBlank())
                ? "Quota exceeded" : scopeLabel + " quota exceeded";
        return prefix + ": metric=" + metric + ", window=" + window.name()
            + ", limit=" + limit + ", used=" + used;
    }

    /**
     * 服务端日志用的完整说明文本（在 {@link #describe()} 基础上追加
     * {@code scopeType} / {@code scopeId}），<b>仅限日志</b>，禁止进入任何响应体。
     *
     * @return 含命中主体标识的诊断文本
     */
    public String describeDetailed() {
        final String base = describe();
        if (scopeType == null && scopeId == null) {
            return base;
        }
        return base + ", scopeType=" + scopeType + ", scopeId=" + scopeId;
    }
}
