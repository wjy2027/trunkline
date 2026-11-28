/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.quota;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配额判定上下文（组织维金额扩展点的中性载体，2026-11-27）。
 *
 * <p>本类只承载 OSS 侧已知字段与一个 OSS 不解释语义的金额透传槽
 * （{@link #amountMinor()}），金额规则、账表与管理界面全部留在企业仓，
 * 契合 open-core 边界。企业扩展在 {@code preCheck} 中完成「判定 + 原子预占」，
 * 仅在预占成功后才写入 {@code amountMinor}；结算 / 冲正回调按该值读回。</p>
 *
 * <p><b>并发纪律</b>：{@code preCheck} 在认证线程写、{@code settle} 在
 * {@code boundedElastic} 链路末端读，跨异步边界，因此金额槽用 {@link AtomicLong}
 * 而非普通 long；回调签名仍传 long（由读方法取值）。</p>
 *
 * @author Trunkline Team
 */
public final class QuotaCheckContext {

    private final String tenantId;
    private final String apiKeyId;
    private final String holder;
    private final String department;
    private final String groupId;
    private final String model;
    private final long estimatedTokens;
    private final LocalDateTime evaluationTime;
    private final AtomicLong amountMinor = new AtomicLong(0L);

    /**
     * 构造判定上下文。
     *
     * @param tenantId        租户 ID
     * @param apiKeyId        API Key ID
     * @param holder          使用者（Key.holder），无则空串
     * @param department      holder 所属行政部门，无则空串
     * @param groupId         模型分组，无则空串
     * @param model           厂商模型 / 实例，无则空串
     * @param estimatedTokens 本次估算 token 数
     * @param evaluationTime  判定时点
     */
    public QuotaCheckContext(final String tenantId, final String apiKeyId, final String holder,
                             final String department, final String groupId, final String model,
                             final long estimatedTokens, final LocalDateTime evaluationTime) {
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.holder = holder;
        this.department = department;
        this.groupId = groupId;
        this.model = model;
        this.estimatedTokens = estimatedTokens;
        this.evaluationTime = evaluationTime;
    }

    public String tenantId() {
        return tenantId;
    }

    public String apiKeyId() {
        return apiKeyId;
    }

    public String holder() {
        return holder;
    }

    public String department() {
        return department;
    }

    public String groupId() {
        return groupId;
    }

    public String model() {
        return model;
    }

    public long estimatedTokens() {
        return estimatedTokens;
    }

    public LocalDateTime evaluationTime() {
        return evaluationTime;
    }

    /**
     * 读取企业扩展预占的金额（最小货币单位，失败 / 异常时恒 0）。
     *
     * @return 已预占金额
     */
    public long amountMinor() {
        return amountMinor.get();
    }

    /**
     * 写入预占金额（仅企业扩展调用，OSS 只透传不解释）。
     *
     * @param value 金额（最小货币单位）
     */
    public void amountMinor(final long value) {
        this.amountMinor.set(value);
    }
}
