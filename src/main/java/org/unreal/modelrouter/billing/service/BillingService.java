/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.billing.dto.BillDetailPageVO;
import org.unreal.modelrouter.billing.dto.BillDetailVO;
import org.unreal.modelrouter.billing.dto.MyBillsVO;
import org.unreal.modelrouter.billing.dto.MyRecentUsageVO;
import org.unreal.modelrouter.billing.dto.TeamUsageVO;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;
import org.unreal.modelrouter.console.ConsoleExtensionStore;
import org.unreal.modelrouter.console.GroupRoleMappingController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 账单聚合服务（v3.5 Trunkline UI 改造，「我的账单」/「团队用量」，§4.3）。
 *
 * <p>聚合口径：以 {@code token_usage} 的成功调用为唯一事实来源，按「用户 + 月份 +
 * 厂商模型」汇总 token 用量与成交金额。<b>所有金额一律只读落库时点固化的成交价快照列</b>
 * （{@code token_usage.price_*_snapshot}），永不回查价格表——商业版「给什么价」与
 * 开源版「账记得对不对」在此解耦（O1）。</p>
 *
 * <p><b>计价口径（O1 成交价快照 / O2 未定价 ≠ ¥0）</b>：「我的账单」「团队用量」
 * 共用同一套快照聚合口径：只有双列快照齐全且 {@code priceMissing=false} 的行金额才计入
 * 总额；落库时无价可固的用量（{@code priceMissing=true}）金额不入总额，经
 * {@code unpricedTokenTotal / hasUnpriced} 单列「待定价」，绝不静默按 ¥0。
 * 因此改单价不会影响任何历史月份金额，两个账单页面也不会出现两套数字。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final TokenUsageRepository tokenUsageRepository;
    private final JwtAccountRepository jwtAccountRepository;
    /** 稳定部门注册表（2026-11-28）：部门维度统计键由「部门文本」升级为稳定 deptId。 */
    private final ConsoleExtensionStore consoleExtensionStore;

    /** 明细 metadata 中缓存 token 解析用的静态 ObjectMapper（fail-soft，无状态线程安全）。 */
    private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper();

    /** yyyy-MM-dd 校验：非法日期串直接忽略，避免 JPQL 比较异常。 */
    private static final String DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}";

    /**
     * 聚合当前用户指定月份的账单。
     *
     * @param userId 用户名（JWT subject，服务端 principal 透传，严禁信任前端传参）
     * @param year   年份
     * @param month  月份（1-12）
     * @return 月度汇总 + 按厂商模型明细
     */
    @Transactional(readOnly = true)
    public MyBillsVO getMyBills(final String userId, final int year, final int month) {
        // 1. 按「用户 + 月份 + 模型」聚合 token 用量与成交金额（成功调用）
        //    O1 成交价快照：金额在 SQL 内按落库时点固化的快照单价求和，
        //    不再查当前 model_price——改价不影响历史账单金额。
        final List<Object[]> modelRows =
                tokenUsageRepository.aggregateByUserMonthModel(userId, year, month);

        // 2. 逐模型组装明细：有价行按快照金额；无价行单列「待定价」（O2：未定价 ≠ ¥0）
        final List<MyBillsVO.ModelBill> modelBills = new ArrayList<>(modelRows.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        long totalTokens = 0L, promptTokens = 0L, completionTokens = 0L, requestCount = 0L;

        for (final Object[] row : modelRows) {
            final String modelName = (String) row[0];
            final long prompt = ((Number) row[1]).longValue();
            final long completion = ((Number) row[2]).longValue();
            final long total = ((Number) row[3]).longValue();
            final long requests = ((Number) row[4]).longValue();
            final BigDecimal snapshotAmount = row.length > 6 && row[6] instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
            final long pricedCount = row.length > 7 && row[7] instanceof Number n2 ? n2.longValue() : 0L;
            final boolean priced = pricedCount > 0;

            modelBills.add(MyBillsVO.ModelBill.builder()
                    .modelName(modelName)
                    .promptTokens(prompt)
                    .completionTokens(completion)
                    .totalTokens(total)
                    .requestCount(requests)
                    // 快照计价下单价可能随版本变化，聚合行无单一单价可展示（前端据此不渲染单价列）
                    .priceInput(null)
                    .priceOutput(null)
                    .amount(priced ? snapshotAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .unpriced(!priced)
                    .build());

            if (priced) {
                totalAmount = totalAmount.add(snapshotAmount);
            }
            totalTokens += total;
            promptTokens += prompt;
            completionTokens += completion;
            requestCount += requests;
        }

        // 按金额降序（待定价 ¥0 排后）
        modelBills.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 3. 待定价用量单列（O2：不混入 ¥0 总额）
        final List<Object[]> unpricedRows =
                tokenUsageRepository.sumUnpricedByUserMonth(userId, year, month);
        final long unpricedTokens = !unpricedRows.isEmpty() && unpricedRows.get(0)[0] instanceof Number n
                ? n.longValue() : 0L;
        final long unpricedRequests = !unpricedRows.isEmpty() && unpricedRows.get(0)[1] instanceof Number n2
                ? n2.longValue() : 0L;

        // 4. 按天 × 虚拟模型的交叉表（v3.5：账单页按原型改为「按天汇总」）
        final List<MyBillsVO.DailyBill> dailyBills = buildDailyBills(userId, year, month);

        // 5. 上月同期（KPI 卡的「比上月省了 ¥x」参照）——同样只读快照
        final int prevYear = month == 1 ? year - 1 : year;
        final int prevMonth = month == 1 ? 12 : month - 1;
        final BigDecimal prevAmount = computeMonthAmount(userId, prevYear, prevMonth);
        final long prevRequests = countMonthRequests(userId, prevYear, prevMonth);

        return MyBillsVO.builder()
                .month(String.format("%04d-%02d", year, month))
                .currency("CNY")
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .totalTokens(totalTokens)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .requestCount(requestCount)
                .unpricedTokenTotal(unpricedTokens)
                .unpricedRequestCount(unpricedRequests)
                .hasUnpriced(unpricedTokens > 0 || unpricedRequests > 0)
                .previousMonthAmount(prevAmount.setScale(4, RoundingMode.HALF_UP))
                .previousMonthRequestCount(prevRequests)
                .byModel(modelBills)
                .byDay(dailyBills)
                .build();
    }

    // ========== 逐笔账单明细（「我的账单」下钻） ==========

    /** 当前登录用户的逐笔明细；归属由 Controller 从 principal 解析，不信任前端传参。 */
    @Transactional(readOnly = true)
    public BillDetailPageVO getMyBillDetails(final String userId, final int year, final int month,
                                              final int page, final int size) {
        return buildDetails(userId, year, month, page, size, null, null, null);
    }

    /** 当前登录用户的逐笔明细（带时间范围 / 虚拟模型筛选）。 */
    @Transactional(readOnly = true)
    public BillDetailPageVO getMyBillDetails(final String userId, final int year, final int month,
                                              final int page, final int size,
                                              final String startDate, final String endDate,
                                              final String virtualModel) {
        return buildDetails(userId, year, month, page, size, startDate, endDate, virtualModel);
    }

    /** 管理员视图：{@code userId} 为 null 时查全公司，否则按人筛选。 */
    @Transactional(readOnly = true)
    public BillDetailPageVO getBillDetails(final String userId, final int year, final int month,
                                            final int page, final int size) {
        return buildDetails(userId, year, month, page, size, null, null, null);
    }

    /** 管理员视图（带时间范围 / 用户 / 虚拟模型筛选）。 */
    @Transactional(readOnly = true)
    public BillDetailPageVO getBillDetails(final String userId, final int year, final int month,
                                            final int page, final int size,
                                            final String startDate, final String endDate,
                                            final String virtualModel) {
        return buildDetails(userId, year, month, page, size, startDate, endDate, virtualModel);
    }

    private BillDetailPageVO buildDetails(final String userId, final int year, final int month,
                                          final int page, final int size,
                                          final String startDate, final String endDate,
                                          final String virtualModel) {
        final int effPage = Math.max(page, 1);
        final int effSize = Math.min(Math.max(size, 1), 200);
        final Pageable pageable = PageRequest.of(effPage - 1, effSize, Sort.by("occurredAt").descending());
        final boolean allUsers = userId == null || userId.isBlank();

        final String effStart = validDate(startDate);
        final String effEnd = validDate(endDate);
        final String effVirtual = virtualModel == null || virtualModel.isBlank() ? null : virtualModel.trim();
        final boolean filtered = effStart != null || effEnd != null || effVirtual != null;

        final Page<TokenUsageEntity> rows;
        final List<Object[]> totalRows;
        final long unpriced;
        if (filtered) {
            // 前端筛选优先：一旦给出任一筛选条件，全部聚合（含金额 / 未计价数）都按同一条件口径
            final String filterUser = allUsers ? null : userId;
            rows = tokenUsageRepository.findDetailFiltered(
                    filterUser, effVirtual, effStart, effEnd, pageable);
            totalRows = tokenUsageRepository.sumPricedAmountFiltered(
                    filterUser, effVirtual, effStart, effEnd);
            unpriced = tokenUsageRepository.countUnpricedFiltered(
                    filterUser, effVirtual, effStart, effEnd);
        } else {
            rows = allUsers
                    ? tokenUsageRepository.findDetailByMonth(null, year, month, pageable)
                    : tokenUsageRepository.findDetailByUserMonth(userId, year, month, pageable);
            totalRows = allUsers
                    ? tokenUsageRepository.sumPricedAmountByMonth(null, year, month)
                    : tokenUsageRepository.sumPricedAmountByUserMonth(userId, year, month);
            unpriced = allUsers
                    ? tokenUsageRepository.countUnpricedByMonth(null, year, month)
                    : unpricedCountOf(userId, year, month);
        }

        final Map<String, String> userNames = accountNames();
        final List<BillDetailVO> items = rows.getContent().stream()
                .map(row -> toDetail(row, userNames)).toList();

        // 全量金额（不受分页影响），与「我的账单」月总额同口径，前端可断言两者一致
        BigDecimal totalAmount = BigDecimal.ZERO;
        if (!totalRows.isEmpty() && totalRows.get(0)[0] instanceof Number n) {
            totalAmount = BigDecimal.valueOf(n.doubleValue());
        }

        return BillDetailPageVO.builder()
                .items(items)
                .total(rows.getTotalElements())
                .page(effPage)
                .size(effSize)
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .requestCount(rows.getTotalElements())
                .unpricedCount(unpriced)
                .build();
    }

    /** 账号 username → 真实姓名映射（姓名可为空，前端回落显示 userId）。 */
    private Map<String, String> accountNames() {
        final Map<String, String> names = new HashMap<>();
        for (final JwtAccountEntity account : jwtAccountRepository.findAll()) {
            if (account.getUsername() != null && account.getName() != null
                    && !account.getName().isBlank()) {
                names.put(account.getUsername(), account.getName());
            }
        }
        return names;
    }

    private static String validDate(final String value) {
        return value != null && value.matches(DATE_PATTERN) ? value : null;
    }

    private long unpricedCountOf(final String userId, final int year, final int month) {
        final List<Object[]> rows = tokenUsageRepository.sumUnpricedByUserMonth(userId, year, month);
        if (rows.isEmpty() || !(rows.get(0)[1] instanceof Number n)) {
            return 0L;
        }
        return n.longValue();
    }

    /** 单行转明细：只读落库时点固化的成交价快照，与 SQL 聚合同一口径（O1/O2）。 */
    private BillDetailVO toDetail(final TokenUsageEntity row, final Map<String, String> userNames) {
        final boolean priced = !Boolean.TRUE.equals(row.getPriceMissing())
                && row.getPriceInputSnapshot() != null && row.getPriceOutputSnapshot() != null;
        BigDecimal amount = BigDecimal.ZERO;
        if (priced) {
            final BigDecimal prompt = BigDecimal.valueOf(nvl(row.getPromptTokens()));
            final BigDecimal completion = BigDecimal.valueOf(nvl(row.getCompletionTokens()));
            amount = prompt.multiply(row.getPriceInputSnapshot())
                    .add(completion.multiply(row.getPriceOutputSnapshot()))
                    .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        }
        return BillDetailVO.builder()
                .traceId(row.getTraceId())
                .occurredAt(row.getOccurredAt())
                .userId(row.getUserId())
                .userName(userNames.get(row.getUserId()))
                .virtualModel(row.getVirtualModel())
                .instanceName(row.getInstanceName() != null ? row.getInstanceName() : row.getModelName())
                .modelName(row.getModelName())
                .promptTokens(nvl(row.getPromptTokens()))
                .completionTokens(nvl(row.getCompletionTokens()))
                .totalTokens(nvl(row.getTotalTokens()))
                .cacheTokens(cacheTokensOf(row.getMetadata()))
                .amount(amount)
                .priced(priced)
                .priceInputSnapshot(row.getPriceInputSnapshot())
                .priceOutputSnapshot(row.getPriceOutputSnapshot())
                .priceVersion(row.getPriceVersion())
                .responseTimeMs(row.getResponseTimeMs())
                .build();
    }

    /**
     * 从落库 metadata 解析缓存命中 token（fail-soft）：优先 pricingDetail.cacheTokens，
     * 兼容旧键 cacheHitTokens / cacheTokens；无元数据或非 JSON 一律返回 0，不影响账单主流程。
     */
    private static long cacheTokensOf(final String metadata) {
        if (metadata == null || metadata.isBlank()) return 0L;
        try {
            final JsonNode root = DETAIL_MAPPER.readTree(metadata);
            if (root == null || !root.isObject()) return 0L;
            final JsonNode detail = root.get("pricingDetail");
            for (final String key : new String[]{"cacheTokens", "cacheHitTokens"}) {
                if (detail != null && detail.isObject() && detail.has(key)) {
                    return detail.get(key).asLong(0L);
                }
            }
            for (final String key : new String[]{"cacheTokens", "cacheHitTokens"}) {
                if (root.has(key)) return root.get(key).asLong(0L);
            }
        } catch (Exception ignored) {
            // metadata 非 JSON：历史数据允许，缓存 token 记 0
        }
        return 0L;
    }

    private static long nvl(final Number value) {
        return value == null ? 0L : value.longValue();
    }

    /**
     * 计算某月的总花费（¥）——用于「上月同期」对比。
     *
     * <p>O1 成交价快照：与 {@link #getMyBills} 同一计价口径——只读落库时点固化的
     * 快照单价，价格缺失的用量不计入（O2：未定价 ≠ ¥0）。</p>
     */
    private BigDecimal computeMonthAmount(final String userId, final int year, final int month) {
        final List<Object[]> rows =
                tokenUsageRepository.sumPricedAmountByUserMonth(userId, year, month);
        if (rows.isEmpty() || !(rows.get(0)[0] instanceof Number n)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(n.doubleValue());
    }

    /**
     * 统计某月的成功调用次数——用于「上月同期」对比。
     */
    private long countMonthRequests(final String userId, final int year, final int month) {
        final List<Object[]> rows = tokenUsageRepository.aggregateByUserMonth(userId, year, month);
        if (rows.isEmpty()) {
            return 0L;
        }
        final Object[] row = rows.get(0);
        // aggregateByUserMonth 返回 [totalTokens, promptTokens, completionTokens, requestCount]
        return ((Number) row[3]).longValue();
    }

    /**
     * 「我的额度」页的近期用量（v3.5 Trunkline UI 改造）。
     *
     * <p>聚合最近 {@code days} 天（默认 7 天）内该用户按<b>虚拟模型</b>分组的用量，
     * 金额按厂商模型单价算好后汇总到虚拟模型。</p>
     *
     * <p>与 {@link #buildDailyBills} 的月度按天聚合分开实现：本查询<b>跨自然月</b>——
     * 月初时「本周最常用」仍能算准，而按月聚合取不到上月末的数据。</p>
     *
     * @param userId 用户名
     * @param days   回溯天数（缺省 7）
     * @return 近期用量视图
     */
    public MyRecentUsageVO getMyRecentUsage(final String userId, final int days) {
        final int window = days > 0 ? Math.min(days, 90) : 7;
        final LocalDate end = LocalDate.now();
        final LocalDate start = end.minusDays(window - 1L);

        final List<Object[]> rows = tokenUsageRepository.aggregateByUserDateRangeVirtualModel(
                userId, start.toString(), end.toString());

        // 虚拟模型 → 累加器
        // O1 成交价快照：金额直接用 SQL 内按快照单价求和的列，不再查当前价格表
        final Map<String, long[]> counters = new LinkedHashMap<>();
        final Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        long totalTokens = 0L;
        long totalRequests = 0L;

        for (final Object[] row : rows) {
            final String rawVirtualModel = (String) row[0];
            final long total = ((Number) row[4]).longValue();
            final long requests = ((Number) row[5]).longValue();
            final BigDecimal amount = row.length > 6 && row[6] instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;

            final String virtualModel = rawVirtualModel != null && !rawVirtualModel.isBlank()
                    ? rawVirtualModel : TeamUsageVO.UNASSIGNED_VIRTUAL_MODEL;

            final long[] c = counters.computeIfAbsent(virtualModel, k -> new long[3]);
            c[0] += requests;
            c[1] += total;
            c[2] += 1;
            amounts.merge(virtualModel, amount, BigDecimal::add);

            totalAmount = totalAmount.add(amount);
            totalTokens += total;
            totalRequests += requests;
        }

        final List<MyRecentUsageVO.VirtualModelUsage> usages = new ArrayList<>(counters.size());
        for (final Map.Entry<String, long[]> entry : counters.entrySet()) {
            final BigDecimal amount = amounts.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            usages.add(MyRecentUsageVO.VirtualModelUsage.builder()
                    .virtualModel(entry.getKey())
                    .requestCount(entry.getValue()[0])
                    .totalTokens(entry.getValue()[1])
                    .amount(amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(totalAmount.signum() == 0 ? 0d
                            : amount.divide(totalAmount, 4, RoundingMode.HALF_UP).doubleValue())
                    .build());
        }
        // 按花费降序（「最常用」取第一个）
        usages.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        return MyRecentUsageVO.builder()
                .startDate(start.toString())
                .endDate(end.toString())
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .totalTokens(totalTokens)
                .requestCount(totalRequests)
                .byVirtualModel(usages)
                .build();
    }

    /**
     * 构建「按天 × 虚拟模型」交叉表（v3.5 Trunkline UI 改造）。
     *
     * <p><b>分组维度与计价维度分离</b>：行按日期、列按<b>虚拟模型</b>（员工视角，
     * 不该关心背后是哪个厂商），但金额必须按<b>厂商模型</b>单价算——
     * 同一个虚拟模型背后可能挂着多个厂商模型，各自单价不同。</p>
     *
     * <p>因此聚合查询同时返回 {@code virtualModel}（分组）与 {@code instanceName}（计价）：
     * 先按厂商模型匹配单价算出这一格的金额，再汇总到虚拟模型列。</p>
     *
     * <p>{@code virtualModel} 为空（未命中任何虚拟模型的历史数据）归入「未归类」，不丢数据。</p>
     *
     * @param userId     用户名
     * @param year       年份
     * @param month      月份
     * @return 按日期倒序的每日明细
     */
    private List<MyBillsVO.DailyBill> buildDailyBills(final String userId, final int year, final int month) {
        final List<Object[]> dayRows =
                tokenUsageRepository.aggregateByUserMonthDayVirtualModel(userId, year, month);

        // 日期 → 该日的虚拟模型金额累加器（保持插入顺序，便于前端稳定渲染列）
        // O1 成交价快照：金额直接用 SQL 内按快照单价求和的列，价格缺失行为 0（不入总额）
        final Map<String, Map<String, BigDecimal>> dayVirtualAmounts = new LinkedHashMap<>();
        final Map<String, Map<String, Long>> dayVirtualTokens = new LinkedHashMap<>();
        final Map<String, Long> dayRequests = new LinkedHashMap<>();
        final Map<String, Long> dayTokens = new LinkedHashMap<>();

        for (final Object[] row : dayRows) {
            final String usageDate = (String) row[0];
            final String rawVirtualModel = (String) row[1];
            final long total = ((Number) row[5]).longValue();
            final long requests = ((Number) row[6]).longValue();
            final BigDecimal amount = row.length > 7 && row[7] instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;

            final String virtualModel = rawVirtualModel != null && !rawVirtualModel.isBlank()
                    ? rawVirtualModel : TeamUsageVO.UNASSIGNED_VIRTUAL_MODEL;

            dayVirtualAmounts.computeIfAbsent(usageDate, k -> new LinkedHashMap<>())
                    .merge(virtualModel, amount, BigDecimal::add);
            dayVirtualTokens.computeIfAbsent(usageDate, k -> new LinkedHashMap<>())
                    .merge(virtualModel, total, Long::sum);
            dayRequests.merge(usageDate, requests, Long::sum);
            dayTokens.merge(usageDate, total, Long::sum);
        }

        final List<MyBillsVO.DailyBill> result = new ArrayList<>(dayVirtualAmounts.size());
        for (final Map.Entry<String, Map<String, BigDecimal>> entry : dayVirtualAmounts.entrySet()) {
            final Map<String, BigDecimal> byVirtual = entry.getValue();
            BigDecimal dayAmount = BigDecimal.ZERO;
            for (final BigDecimal v : byVirtual.values()) {
                dayAmount = dayAmount.add(v);
            }
            result.add(MyBillsVO.DailyBill.builder()
                    .usageDate(entry.getKey())
                    .amount(dayAmount.setScale(4, RoundingMode.HALF_UP))
                    .requestCount(dayRequests.get(entry.getKey()))
                    .totalTokens(dayTokens.get(entry.getKey()))
                    .byVirtualModel(byVirtual)
                    .byVirtualModelTokens(dayVirtualTokens.get(entry.getKey()))
                    .build());
        }
        return result;
    }

    /**
     * 团队用量聚合（全公司 / 按部门）。
     *
     * <p>事实来源与「我的账单」同源：{@code token_usage} 的成功调用。
     * 部门归属来自 {@code jwt_accounts.department}（用户名关联），账号不存在或未填部门的
     * 归入「未分配部门」——不丢数据。</p>
     *
     * @param dimension  维度：COMPANY（全公司，按人）/ DEPARTMENT（按部门）
     * @param year       年份
     * @param month      月份（1-12）
     * @param department 部门筛选（可选；缺省=全部部门）
     * @return 分组明细 + 按厂商模型明细 + 总计
     */
    @Transactional(readOnly = true)
    public TeamUsageVO getTeamUsage(final String dimension, final int year, final int month,
                                    final String department) {
        final boolean byDepartment = TeamUsageVO.DIMENSION_DEPARTMENT.equalsIgnoreCase(dimension);
        final String effDimension = byDepartment
                ? TeamUsageVO.DIMENSION_DEPARTMENT : TeamUsageVO.DIMENSION_COMPANY;

        // 1. 账号索引（用户名 → 部门/显示名）
        final Map<String, JwtAccountEntity> accounts = new HashMap<>();
        for (final JwtAccountEntity account : jwtAccountRepository.findAll()) {
            if (account.getUsername() != null) {
                accounts.put(account.getUsername(), account);
            }
        }

        // 1.5 稳定部门注册表（2026-11-28）：部门维度统计键从「账号 department 文本」升级为
        // 稳定 deptId——部门改名/重名不再拆分统计口径。登记表命中 → deptKey=deptId、
        // 展示名=登记名；未登记（历史自由文本）→ deptKey=文本本身，行为与旧口径一致不丢数据。
        final Map<String, String> deptNameToId = new HashMap<>();
        final Map<String, String> deptIdToName = new HashMap<>();
        try {
            for (final Map<String, Object> deptRow : consoleExtensionStore.list(GroupRoleMappingController.DEPT_KEY)) {
                final String id = String.valueOf(deptRow.get("deptId"));
                final String name = String.valueOf(deptRow.get("name"));
                if (id != null && !id.isBlank() && name != null && !name.isBlank()) {
                    deptNameToId.put(name, id);
                    deptIdToName.put(id, name);
                }
            }
        } catch (RuntimeException e) {
            log.warn("部门注册表加载失败，团队用量退回部门文本口径: {}", e.toString());
        }
        /** 部门文本 → 统计键（登记部门→deptId，未登记→原文本） */
        final java.util.function.UnaryOperator<String> deptKeyOf = text ->
                deptNameToId.getOrDefault(text, text);
        /** 统计键 → 展示名（deptId→登记名，其余原样） */
        final java.util.function.UnaryOperator<String> deptLabelOf = key ->
                deptIdToName.getOrDefault(key, key);
        // 部门筛选参数兼容两种口径：传登记名 → 转 deptId；传 deptId 或历史文本 → 原样
        final String deptFilterKey = department != null && !department.isBlank()
                ? deptKeyOf.apply(department.trim())
                : null;

        // 3. 逐人逐虚拟模型逐厂商模型聚合行（含失败调用，用于算成功率）
        final List<Object[]> rows =
                tokenUsageRepository.aggregateByMonthUserVirtualModel(year, month);

        // 4. 分组累加器
        final Map<String, Accumulator> groups = new LinkedHashMap<>();
        final Map<String, Accumulator> models = new LinkedHashMap<>();
        final Map<String, VirtualModelAccumulator> virtualModels = new LinkedHashMap<>();
        final Map<String, ServiceTypeAccumulator> serviceTypes = new LinkedHashMap<>();
        final Map<String, DepartmentAccumulator> departments = new LinkedHashMap<>();
        final Set<String> users = new LinkedHashSet<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        long totalTokens = 0L, promptTokens = 0L, completionTokens = 0L, requestCount = 0L;
        long successCount = 0L, failedCount = 0L;
        long unpricedTokens = 0L;
        boolean hasUnpriced = false;

        for (final Object[] row : rows) {
            final String userId = (String) row[0];
            final String rawVirtualModel = (String) row[1];
            final String modelName = (String) row[2];
            final String serviceType = (String) row[3];
            final long prompt = ((Number) row[4]).longValue();
            final long completion = ((Number) row[5]).longValue();
            final long total = ((Number) row[6]).longValue();
            final long requests = ((Number) row[7]).longValue();
            final long success = ((Number) row[8]).longValue();

            // 虚拟模型名：未命中任何分组（历史数据 / 内部路径）归入「未归类」，不丢数据
            final String virtualModel = rawVirtualModel != null && !rawVirtualModel.isBlank()
                    ? rawVirtualModel : TeamUsageVO.UNASSIGNED_VIRTUAL_MODEL;

            final JwtAccountEntity account = accounts.get(userId);
            final String deptText = account != null && account.getDepartment() != null
                    && !account.getDepartment().isBlank()
                    ? account.getDepartment().trim() : TeamUsageVO.UNASSIGNED_DEPARTMENT;
            // 统计键：登记部门→稳定 deptId；未登记→原文本（历史数据不丢）
            final String deptKey = deptKeyOf.apply(deptText);

            // 部门筛选（参数为登记名/deptId/历史文本均可，统一翻译成统计键比较）
            if (deptFilterKey != null && !deptFilterKey.equals(deptKey)) {
                continue;
            }

            // P0-3：与「我的账单」共用快照口径。聚合 SQL 追加的两列是
            // [9]=快照成交金额、[10]=双列快照齐全的行数；当前价不参与任何历史金额计算。
            final BigDecimal snapshotAmount = row.length > 9 && row[9] instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
            final long pricedCount = row.length > 10 && row[10] instanceof Number n2
                    ? n2.longValue() : 0L;
            final boolean priced = pricedCount > 0;
            final BigDecimal amount = priced
                    ? snapshotAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            // 未定价：token 照常计入总量，金额不计入总额，并汇总到待定价单列（O2）
            if (!priced) {
                unpricedTokens += total;
                hasUnpriced = true;
            }

            // 分组键：按部门 = 部门统计键（deptId/文本）；全公司 = 用户名
            final String groupKey = byDepartment ? deptKey : userId;
            final String groupLabel = byDepartment
                    ? deptLabelOf.apply(deptKey)
                    : (account != null ? account.getUsername() : userId);
            final Accumulator groupAcc = groups.computeIfAbsent(groupKey, k -> {
                final Accumulator acc = new Accumulator();
                acc.label = groupLabel;
                return acc;
            });
            groupAcc.add(prompt, completion, total, requests, amount);
            groupAcc.success += success;
            groupAcc.failed += (requests - success);
            if (byDepartment) {
                groupAcc.users.add(userId);
            }

            final Accumulator modelAcc = models.computeIfAbsent(modelName, k -> new Accumulator());
            modelAcc.add(prompt, completion, total, requests, amount);

            // 虚拟模型维度（主展示维度）—— 同时记录其下钻的厂商模型
            final VirtualModelAccumulator vmAcc =
                    virtualModels.computeIfAbsent(virtualModel, VirtualModelAccumulator::new);
            vmAcc.add(prompt, completion, total, requests, amount);
            final Accumulator vmModelAcc =
                    vmAcc.models.computeIfAbsent(modelName, k -> new Accumulator());
            vmModelAcc.add(prompt, completion, total, requests, amount);

            // 用途类型维度（聊天 / 嵌入 / 排序 / 语音 / 画图）
            final ServiceTypeAccumulator stAcc =
                    serviceTypes.computeIfAbsent(serviceType == null ? "chat" : serviceType,
                            key -> new ServiceTypeAccumulator());
            stAcc.add(prompt, completion, total, requests, amount);

            // 部门汇总卡（不受「维度 / 部门筛选」影响，始终全量）：键=统计键，展示=登记名
            final DepartmentAccumulator deptAcc =
                    departments.computeIfAbsent(deptKey, key -> new DepartmentAccumulator());
            deptAcc.add(prompt, completion, total, requests, amount);
            deptAcc.users.add(userId);

            users.add(userId);
            totalAmount = totalAmount.add(amount);
            totalTokens += total;
            promptTokens += prompt;
            completionTokens += completion;
            requestCount += requests;
            successCount += success;
            failedCount += (requests - success);
        }

        // 5. 组装分组明细（按花费降序）
        final List<TeamUsageVO.GroupUsage> groupList = new ArrayList<>(groups.size());
        for (final Map.Entry<String, Accumulator> entry : groups.entrySet()) {
            final Accumulator acc = entry.getValue();
            groupList.add(TeamUsageVO.GroupUsage.builder()
                    .key(entry.getKey())
                    .label(acc.label)
                    .userCount(byDepartment ? acc.users.size() : 1)
                    .requestCount(acc.requests)
                    .promptTokens(acc.prompt)
                    .completionTokens(acc.completion)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .build());
        }
        groupList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 6. 按厂商模型明细（按花费降序）
        final List<TeamUsageVO.ModelUsage> modelList = new ArrayList<>(models.size());
        for (final Map.Entry<String, Accumulator> entry : models.entrySet()) {
            final Accumulator acc = entry.getValue();
            modelList.add(TeamUsageVO.ModelUsage.builder()
                    .modelName(entry.getKey())
                    .requestCount(acc.requests)
                    .promptTokens(acc.prompt)
                    .completionTokens(acc.completion)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .build());
        }
        modelList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 7. 按虚拟模型明细（主展示维度，按花费降序）
        final List<TeamUsageVO.VirtualModelUsage> virtualModelList =
                new ArrayList<>(virtualModels.size());
        for (final Map.Entry<String, VirtualModelAccumulator> entry : virtualModels.entrySet()) {
            final VirtualModelAccumulator acc = entry.getValue();
            final List<TeamUsageVO.ModelUsage> subModels = new ArrayList<>(acc.models.size());
            for (final Map.Entry<String, Accumulator> modelEntry : acc.models.entrySet()) {
                final Accumulator modelAcc = modelEntry.getValue();
                subModels.add(TeamUsageVO.ModelUsage.builder()
                        .modelName(modelEntry.getKey())
                        .requestCount(modelAcc.requests)
                        .promptTokens(modelAcc.prompt)
                        .completionTokens(modelAcc.completion)
                        .totalTokens(modelAcc.total)
                        .amount(modelAcc.amount.setScale(4, RoundingMode.HALF_UP))
                        .build());
            }
            subModels.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));
            virtualModelList.add(TeamUsageVO.VirtualModelUsage.builder()
                    .virtualModel(entry.getKey())
                    .label(entry.getKey())
                    .modelCount(acc.models.size())
                    .requestCount(acc.requests)
                    .promptTokens(acc.prompt)
                    .completionTokens(acc.completion)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(ratio(acc.amount, totalAmount))
                    .models(subModels)
                    .build());
        }
        virtualModelList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 8. 按用途类型分布（按花费降序）
        final List<TeamUsageVO.ServiceTypeUsage> serviceTypeList =
                new ArrayList<>(serviceTypes.size());
        for (final Map.Entry<String, ServiceTypeAccumulator> entry : serviceTypes.entrySet()) {
            final ServiceTypeAccumulator acc = entry.getValue();
            serviceTypeList.add(TeamUsageVO.ServiceTypeUsage.builder()
                    .serviceType(entry.getKey())
                    .label(humanizeServiceType(entry.getKey()))
                    .requestCount(acc.requests)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(ratio(acc.amount, totalAmount))
                    .build());
        }
        serviceTypeList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 9. 部门汇总卡（第一张是「全公司」，其余按花费降序）
        final List<TeamUsageVO.DepartmentSummary> departmentList =
                new ArrayList<>(departments.size() + 1);
        departmentList.add(TeamUsageVO.DepartmentSummary.builder()
                .department(TeamUsageVO.COMPANY_LABEL)
                .userCount(users.size())
                .requestCount(requestCount)
                .totalTokens(totalTokens)
                .amount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .amountRatio(totalAmount.signum() > 0 ? 1.0d : 0.0d)
                .company(true)
                .build());
        for (final Map.Entry<String, DepartmentAccumulator> entry : departments.entrySet()) {
            final DepartmentAccumulator acc = entry.getValue();
            departmentList.add(TeamUsageVO.DepartmentSummary.builder()
                    .department(deptLabelOf.apply(entry.getKey()))
                    .userCount(acc.users.size())
                    .requestCount(acc.requests)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(ratio(acc.amount, totalAmount))
                    .company(false)
                    .build());
        }
        departmentList.sort((a, b) -> {
            // 「全公司」永远排第一，其余按花费降序
            if (a.isCompany() != b.isCompany()) {
                return a.isCompany() ? -1 : 1;
            }
            return b.getAmount().compareTo(a.getAmount());
        });

        return TeamUsageVO.builder()
                .month(String.format("%04d-%02d", year, month))
                .currency("CNY")
                .dimension(effDimension)
                .departmentFilter(department)
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .totalTokens(totalTokens)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .requestCount(requestCount)
                .userCount(users.size())
                .successCount(successCount)
                .failedCount(failedCount)
                .successRate(requestCount > 0 ? (double) successCount / requestCount : 0.0d)
                .unpricedTokenTotal(unpricedTokens)
                .hasUnpriced(hasUnpriced)
                .groups(groupList)
                .byModel(modelList)
                .byVirtualModel(virtualModelList)
                .byServiceType(serviceTypeList)
                .departments(departmentList)
                .build();
    }

    /**
     * 占比（0~1）。
     */
    private static double ratio(final BigDecimal part, final BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            return 0.0d;
        }
        return part.divide(total, 6, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 用途类型人话化（设计规格 §6.4：员工侧不出现技术词）。
     */
    private static String humanizeServiceType(final String serviceType) {
        if (serviceType == null) {
            return "聊天对话";
        }
        return switch (serviceType.toLowerCase()) {
            case "chat" -> "聊天对话";
            case "embedding" -> "知识库嵌入";
            case "rerank" -> "文档排序";
            case "tts" -> "语音合成";
            case "stt" -> "语音识别";
            case "imgGen", "imggen", "image" -> "图片生成";
            case "imgEdit", "imgedit" -> "图片编辑";
            default -> serviceType;
        };
    }

    /**
     * 全部部门列表（去重排序）。
     *
     * <p>供「团队用量」部门维度筛选下拉使用。2026-11-28 起以稳定部门注册表为准
     * （登记部门优先），并合并账号 department 里尚未登记的历史文本，保证旧数据
     * 仍可被筛选——登记后下次请求即按 deptId 口径聚合。</p>
     */
    @Transactional(readOnly = true)
    public List<String> getDepartments() {
        final java.util.TreeSet<String> departments = new java.util.TreeSet<>();
        try {
            for (final Map<String, Object> deptRow : consoleExtensionStore.list(GroupRoleMappingController.DEPT_KEY)) {
                final String name = String.valueOf(deptRow.get("name"));
                if (name != null && !name.isBlank()) {
                    departments.add(name);
                }
            }
        } catch (RuntimeException e) {
            log.warn("部门注册表加载失败，团队用量部门下拉退回账号文本口径: {}", e.toString());
        }
        for (final JwtAccountEntity account : jwtAccountRepository.findAll()) {
            final String dept = account.getDepartment();
            if (dept != null && !dept.isBlank()) {
                departments.add(dept.trim());
            }
        }
        return new ArrayList<>(departments);
    }

    /**
     * 分组累加器（内部用）。
     */
    private static final class Accumulator {
        private String label;
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private long success;
        private long failed;
        private BigDecimal amount = BigDecimal.ZERO;
        private final Set<String> users = new LinkedHashSet<>();

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 虚拟模型（模型分组）累加器，附带其下钻的厂商模型明细。
     */
    private static final class VirtualModelAccumulator {
        private final String name;
        private final Map<String, Accumulator> models = new LinkedHashMap<>();
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private BigDecimal amount = BigDecimal.ZERO;

        private VirtualModelAccumulator(final String name) {
            this.name = name;
        }

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 用途类型累加器。
     */
    private static final class ServiceTypeAccumulator {
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private BigDecimal amount = BigDecimal.ZERO;

        /** 类型名本身已作 Map 键，累加器无需再持有 */
        private ServiceTypeAccumulator() {
        }

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 部门汇总累加器。
     */
    private static final class DepartmentAccumulator {
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private BigDecimal amount = BigDecimal.ZERO;
        private final Set<String> users = new LinkedHashSet<>();

        /** 部门名本身已作 Map 键，累加器无需再持有 */
        private DepartmentAccumulator() {
        }

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }
}
