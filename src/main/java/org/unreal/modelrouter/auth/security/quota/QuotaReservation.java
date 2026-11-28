package org.unreal.modelrouter.auth.security.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次请求的配额预留凭据（v3.1 PR-2，请求属性载体）。
 *
 * <p>预留在实例选择之前完成，结算发生在链路末端（流式 / 非流式的 token 落库点，或异常 / 中断时），
 * 两处相距较远，因此凭据随请求传递——沿用仓库既有做法（{@code API_KEY_ID_ATTRIBUTE}、
 * {@code CACHE_KEY_ATTRIBUTE}）存放在 {@link ServerHttpRequest#getAttributes()} 中，
 * 由处理器在结算点取回。凭据本身持有账本引用，因此处理器无需额外注入协作方。</p>
 *
 * <p><b>多维度</b>（v3.4 配额多维度改造）：一笔请求可能同时占用 Key（总闸）/ 分组 / 模型
 * 三个独立预算，凭据持有多个维度，结算时对每个维度分别冲正。</p>
 *
 * <p><b>恰一次语义</b>：成功结算与失败回滚都可能被多条路径触发（成功回调、错误回调、取消回调），
 * 凭据用 {@link AtomicBoolean} 保证只有第一次调用生效，避免重复冲正。账本未启用时不会创建凭据，
 * 处理器取到 {@code null} 即天然空操作——这就是“{@code enabled=false} 零行为变更”的落点。</p>
 *
 * <p>所有静态入口对 {@code reservation == null} 均安全：账本未启用 / 未预扣的请求直接返回。</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
public final class QuotaReservation {

    /** 请求属性键：本次请求的配额预留凭据 */
    public static final String ATTRIBUTE = "TRUNKLINE_QUOTA_RESERVATION";

    private final QuotaLedgerService ledgerService;
    private final List<QuotaDimension> dimensions;
    private final long estimatedTokens;
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);

    /**
     * 金额扩展上下文与实例（2026-11-27）：未装配企业扩展时两者均为 null，
     * 结算链路零行为变化。context 持有跨异步边界的金额槽（AtomicLong）。
     */
    private final QuotaCheckContext context;
    private final QuotaCheckExtension extension;

    /**
     * 追加的维度（v3.5 治理收口：选实例后才确定的「实例」维度）。
     *
     * <p>预留发生在实例选择之前，此时只知道分组（虚拟模型名），不知道最终落到哪个实例。
     * 选实例完成后由处理器把实例维度追加进来，使账本明细行同时带
     * {@code group_id}（治理主键）与 {@code model}（观测明细）。</p>
     *
     * <p>用独立列表而非直接改 {@code dimensions}，因为 {@code dimensions} 在构造时
     * 已被 {@code List.copyOf} 冻结。</p>
     */
    private final List<QuotaDimension> appendedDimensions = new ArrayList<>();

    /**
     * 构造预留凭据（单维度兼容）。
     *
     * @param ledgerService   账本服务
     * @param dimension       账本维度
     * @param estimatedTokens 预留时使用的估算 token 数
     */
    public QuotaReservation(final QuotaLedgerService ledgerService,
                            final QuotaDimension dimension,
                            final long estimatedTokens) {
        this(ledgerService, List.of(dimension), estimatedTokens, null, null);
    }

    /**
     * 构造预留凭据（多维度）。
     *
     * @param ledgerService   账本服务
     * @param dimensions      账本维度列表（Key / 分组 / 模型）
     * @param estimatedTokens 预留时使用的估算 token 数
     */
    public QuotaReservation(final QuotaLedgerService ledgerService,
                            final List<QuotaDimension> dimensions,
                            final long estimatedTokens) {
        this(ledgerService, dimensions, estimatedTokens, null, null);
    }

    /**
     * 主构造：企业金额扩展随凭据一起传递，保证结算回调恰一次。
     *
     * @param ledgerService   账本服务
     * @param dimensions      账本维度列表（Key / 组织维 / 分组 / 模型）
     * @param estimatedTokens 预留时使用的估算 token 数
     * @param context         金额扩展上下文，可为 {@code null}
     * @param extension       金额扩展实例，可为 {@code null}
     */
    public QuotaReservation(final QuotaLedgerService ledgerService,
                            final List<QuotaDimension> dimensions,
                            final long estimatedTokens,
                            final QuotaCheckContext context,
                            final QuotaCheckExtension extension) {
        this.ledgerService = ledgerService;
        this.dimensions = dimensions == null || dimensions.isEmpty()
            ? List.of(QuotaDimension.empty()) : List.copyOf(dimensions);
        this.estimatedTokens = Math.max(0L, estimatedTokens);
        this.context = context;
        this.extension = extension;
    }

    /**
     * 把凭据挂载到请求属性（供链路末端的结算点取回）。
     *
     * <p>请求为 {@code null} 或属性不可写时静默忽略：配额是旁路能力，不得影响主链路。</p>
     *
     * @param request     请求，可为 {@code null}
     * @param reservation 凭据，{@code null} 时不做任何处理
     */
    public static void attach(final ServerHttpRequest request, final QuotaReservation reservation) {
        if (request == null || reservation == null) {
            return;
        }
        try {
            request.getAttributes().put(ATTRIBUTE, reservation);
        } catch (Exception e) {
            log.warn("配额预留凭据挂载失败（忽略，结算将回退为不冲正）: error={}", e.toString());
        }
    }

    /**
     * 从请求属性取回预留凭据。
     *
     * @param request 请求，可为 {@code null}
     * @return 预留凭据；未预扣（账本未启用 / 缓存命中 / 属性缺失）时返回 {@code null}
     */
    public static QuotaReservation from(final ServerHttpRequest request) {
        if (request == null) {
            return null;
        }
        try {
            final Object reservation = request.getAttributes().get(ATTRIBUTE);
            return reservation instanceof QuotaReservation quotaReservation ? quotaReservation : null;
        } catch (Exception e) {
            log.debug("读取配额预留凭据失败: error={}", e.toString());
            return null;
        }
    }

    /**
     * 按实际用量结算（token 冲正量 = 实际 − 估算；请求数不变）。
     *
     * @param reservation  预留凭据，{@code null} 时空操作
     * @param actualTokens 实际消耗 token 数（负数按 0 处理）
     */
    public static void settleSuccess(final QuotaReservation reservation, final long actualTokens) {
        if (reservation != null) {
            reservation.settle(actualTokens, false);
        }
    }

    /**
     * 回滚整笔预留（token 冲正量 = −估算，请求数冲正量 = −1）。
     *
     * @param reservation 预留凭据，{@code null} 时空操作
     */
    public static void settleFailure(final QuotaReservation reservation) {
        if (reservation != null) {
            reservation.settle(0L, true);
        }
    }

    /**
     * 预留时使用的估算 token 数。
     *
     * @return 估算 token 数
     */
    public long estimatedTokens() {
        return estimatedTokens;
    }

    /**
     * 账本维度列表（含预留时确定与后续追加的维度）。
     *
     * @return 维度列表
     */
    public List<QuotaDimension> dimensions() {
        if (appendedDimensions.isEmpty()) {
            return dimensions;
        }
        final List<QuotaDimension> merged = new ArrayList<>(dimensions);
        merged.addAll(appendedDimensions);
        return merged;
    }

    /**
     * 追加一个在选实例后才确定的维度（实例维度）。
     *
     * <p>追加时<b>立即按「不限额」方式记账</b>（与 {@code reserve} 的无限额分支一致），
     * 使组内模型维度的用量从此刻起连续；结算时该维度同样参与冲正。</p>
     *
     * <p>已结算的凭据不再接受追加（结算已发生，追加会导致计数与冲正不配对）。</p>
     *
     * @param dimension 待追加的维度（{@code null} 时忽略）
     */
    public void appendDimension(final QuotaDimension dimension) {
        if (dimension == null || settled.get()) {
            return;
        }
        if (appendedDimensions.contains(dimension)) {
            return;
        }
        try {
            ledgerService.reserve(QuotaRequest.of(dimension, estimatedTokens));
            appendedDimensions.add(dimension);
        } catch (Exception e) {
            log.warn("配额明细维度追加失败（忽略，不影响主链路）: scope={}, error={}",
                    dimension, e.toString());
        }
    }

    /**
     * 追加一个<b>带限额判定</b>的维度（厂商模型维度）。
     *
     * <p>与 {@link #appendDimension(QuotaDimension)} 的差别：后者只按「不限额」记账，
     * 本方法走 {@link QuotaLedgerService#reserveWithLimits(QuotaRequest, QuotaLimitSet)}
     * 的 CAS 预扣——<b>判定与记账一次完成</b>，超限时该维度的增量已被账本内部回滚，
     * 本方法不再登记该维度（不记账 = 不冲正 = 不留残量）。</p>
     *
     * <p>限额各窗口取值 {@code <= 0}（{@link QuotaLimitSet#UNLIMITED}）时，
     * CAS 预扣退化为普通累加（只记账、不判定），与 {@code appendDimension} 行为一致——
     * 这样「未配置限额的模型」也能保持维度用量连续。</p>
     *
     * @param dimension 待追加的维度（{@code null} 时忽略）
     * @param limits    该维度的限额集合（{@code null} 视为不限额）
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> appendDimensionWithLimits(final QuotaDimension dimension,
                                                                  final QuotaLimitSet limits) {
        if (dimension == null || settled.get()) {
            return Optional.empty();
        }
        if (appendedDimensions.contains(dimension)) {
            return Optional.empty();
        }
        try {
            final QuotaLimitSet effective = limits == null ? QuotaLimitSet.UNLIMITED : limits;
            final Optional<QuotaLimitViolation> violation =
                    ledgerService.reserveWithLimits(QuotaRequest.of(dimension, estimatedTokens), effective);
            if (violation.isPresent()) {
                // CAS 已回滚本次增量：不登记维度，避免结算时对不存在的计数冲正
                return violation;
            }
            appendedDimensions.add(dimension);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("配额限额维度追加失败（忽略，不影响主链路）: scope={}, error={}",
                    dimension, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 账本维度（兼容旧调用，取列表首元素）。
     *
     * @return 首个维度
     */
    public QuotaDimension dimension() {
        return dimensions.get(0);
    }

    /**
     * 是否已完成结算（成功或回滚）。
     *
     * @return 已结算返回 {@code true}
     */
    public boolean isSettled() {
        return settled.get();
    }

    /**
     * 结算是否为失败回滚。
     *
     * @return 已按失败回滚返回 {@code true}
     */
    public boolean isFailed() {
        return failed.get();
    }

    /**
     * 执行恰一次结算（内部实现，异常被吞掉——账本是旁路能力，不影响请求结果）。
     *
     * @param actualTokens 实际 token 数（失败回滚时忽略）
     * @param isFailed     true = 回滚整笔预留
     */
    private void settle(final long actualTokens, final boolean isFailed) {
        if (!settled.compareAndSet(false, true)) {
            log.debug("配额结算重复调用（忽略）: apiKeyId={}", dimension().apiKeyId());
            return;
        }
        failed.set(isFailed);
        // 金额回调：放在 compareAndSet 之后、结算循环之外——不被 ledgerService.settle()
        // 的 catch-all 吞掉；自身 try-catch，失败只告警不影响主链路。
        if (context != null && extension != null) {
            try {
                final long amount = context.amountMinor();
                if (isFailed) {
                    extension.onRolledBack(context, amount);
                } else {
                    extension.onCommitted(context, amount);
                }
            } catch (RuntimeException e) {
                log.error("金额结算回调失败（需告警）: failed={}, error={}",
                        isFailed, e.toString());
            }
        }
        // 结算需覆盖「预留时维度 + 追加维度」（追加维度在选实例后才确定）
        final List<QuotaDimension> allDimensions = dimensions();
        final List<QuotaSettlement> settlements = new ArrayList<>(allDimensions.size());
        for (final QuotaDimension dim : allDimensions) {
            settlements.add(new QuotaSettlement(dim, estimatedTokens, actualTokens, isFailed));
        }
        try {
            for (final QuotaSettlement settlement : settlements) {
                ledgerService.settle(settlement);
            }
        } catch (Exception e) {
            log.warn("配额结算异常（忽略，不影响请求结果）: apiKeyId={}, failed={}, error={}",
                dimension().apiKeyId(), isFailed, e.toString());
        }
    }
}
