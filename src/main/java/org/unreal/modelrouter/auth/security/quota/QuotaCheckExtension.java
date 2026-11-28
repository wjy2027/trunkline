/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.quota;

import java.util.Optional;

/**
 * 配额检查扩展点（组织维金额层的 open-core 契约，2026-11-27，SPI v3 兼容扩展）。
 *
 * <p>OSS 只提供默认空实现，未装配企业实现时行为零变化；企业侧用 {@code @Primary}
 * 覆盖装配金额判定。新增 default 方法不触发 SPI 版本 +1。</p>
 *
 * <p><b>三层职责（务必遵守）</b>：</p>
 * <ol>
 *   <li>{@link #preCheck(QuotaCheckContext)} = 判定 + 原子预占：一次 CAS 完成
 *       「判超限 + 写 reserved_amount」，返回 empty 表示放行；<b>拒绝一律用返回值表达，
 *       不得抛异常</b>（OSS tryReserve 的 catch-all 会把异常吞成放行）。必须幂等；
 *       仅预占成功后才写 {@code ctx.amountMinor}，拒绝 / 异常时保持 0；</li>
 *   <li>{@link #onReserved(QuotaCheckContext, long)} = 通知：只做告警 / 日志 / 埋点，
 *       不得写账；失败只告警，不影响主链路；</li>
 *   <li>{@link #onCommitted(QuotaCheckContext, long)} /
 *       {@link #onRolledBack(QuotaCheckContext, long)} = 结算通知：结算 / 冲正在此完成，
 *       依赖 {@link QuotaReservation} 的恰一次语义防重复；金额账与 token 账
 *       <b>不在同一事务</b>，失败告警 + 对账兜底，不承诺原子一致。</li>
 * </ol>
 *
 * @author Trunkline Team
 */
public interface QuotaCheckExtension {

    /**
     * 请求前判定并原子预占金额。
     *
     * @param ctx 判定上下文（含 OSS 中性字段与金额透传槽）
     * @return 超限返回违规结果（OSS 据此回滚 token 并返回 429）；放行返回 empty
     */
    default Optional<QuotaLimitViolation> preCheck(final QuotaCheckContext ctx) {
        return Optional.empty();
    }

    /**
     * 预占成功通知（不写账）。
     *
     * @param ctx        判定上下文
     * @param amountMinor 已预占金额（最小货币单位）
     */
    default void onReserved(final QuotaCheckContext ctx, final long amountMinor) {
        // Noop：未装配企业实现时零行为变化
    }

    /**
     * 结算通知（按实际用量结算金额账）。
     *
     * @param ctx        判定上下文
     * @param amountMinor 预占金额（最小货币单位）
     */
    default void onCommitted(final QuotaCheckContext ctx, final long amountMinor) {
        // Noop
    }

    /**
     * 冲正通知（回滚金额账；{@code amountMinor == 0} 时空操作）。
     *
     * @param ctx        判定上下文
     * @param amountMinor 预占金额（最小货币单位），0 表示从未预占
     */
    default void onRolledBack(final QuotaCheckContext ctx, final long amountMinor) {
        // Noop
    }
}
