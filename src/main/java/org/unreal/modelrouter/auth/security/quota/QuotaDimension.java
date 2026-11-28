package org.unreal.modelrouter.auth.security.quota;

/**
 * 配额账本维度（租户 / API Key / 用户 / 服务类型 / 模型 / 分组）。
 *
 * <p>账本按上述六维聚合统计请求数与 token 数。任一维度未知时统一折叠为哨兵值
 * 而不是 {@code null}，原因有两点：</p>
 * <ul>
 *   <li>数据库维度列声明为 NOT NULL，空串哨兵值避免每次落库前做空值判断；</li>
 *   <li>{@code null} 与空串在账本中语义相同（均为“未指定”），归一化后
 *       {@link #equals(Object)} / {@link #hashCode()} 保持稳定，避免出现
 *       两份内容相同但 key 不同的内存槽位。</li>
 * </ul>
 *
 * <p>分组维度（{@code groupId}）用于「模型分组（池 / pool）」级独立配额：请求 {@code model}
 * 命中池时 {@code groupId = 池名}、{@code model} 维度留空（分组桶）；选实例后补记一行
 * {@code groupId = 池名, model = 实例名}（成员桶）。单线治理下分组与模型<b>并存</b>——
 * {@code groupId} 是治理主键、{@code model} 是组内观测明细；Key 维度恒生效。</p>
 *
 * @param tenantId    租户 ID，未指定时缺省租户 {@link #TENANT_SENTINEL}
 * @param apiKeyId    API Key ID，未指定时为空串
 * @param userId      用户 ID，未指定时为空串
 * @param serviceType 服务类型（chat / embedding / rerank / tts / stt），未指定时为空串
 * @param model       模型名称（已接入模型 / 实例名），未指定时为空串
 * @param groupId     分组（模型分组 / 池）名称，未指定时为空串
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
public record QuotaDimension(String tenantId, String apiKeyId, String userId, String serviceType, String model, String groupId) {

    /** 非租户维度缺省哨兵值（空串），与数据库维度列的 NOT NULL 约束配套使用 */
    public static final String SENTINEL = "";

    /**
     * 部门桶前缀（{@code dept:}）：部门额度借用 {@code userId} 列时的编码前缀。
     *
     * <p>2026-11-27（中风险整改）：前缀从 {@link #ofDepartment(String)} 的局部字面量提升为
     * 公共常量，消费侧（账本查询 / 报表）判断「某 userId 值是不是部门桶」必须走
     * {@link #isDepartmentBucket(String)}，禁止各自散写字面量。</p>
     */
    public static final String DEPARTMENT_PREFIX = "dept:";

    /**
     * 租户维度缺省哨兵值（{@code default}）。
     *
     * <p>历史账本曾用空串表示「未指定租户」，与其余 5 张业务表、JWT claim、
     * Redis 命名空间以及 Hibernate 行级过滤器的 {@code default} 口径冲突：
     * 打开多租户后过滤器按 {@code tenant_id='default'} 查询，账本却写在
     * {@code tenant_id=''}，导致账本整体「读不到」。故租户维度单独归一到
     * {@code default}，与 {@code TenantContextHolder} 回落值保持一致。
     * 非租户维度仍用 {@link #SENTINEL}，二者不可混用。</p>
     */
    public static final String TENANT_SENTINEL = "default";

    /**
     * 全局共享哨兵（{@code __shared__}）——用户 / 部门级跨租户归集桶的租户维度取值。
     *
     * <p>与 {@link #TENANT_SENTINEL}（{@code default}，表示「未指定租户」）语义不同：
     * {@code __shared__} 表示「跨所有租户共享」，是 Hibernate 行级过滤器
     * {@code TenantJpaFilter} 的可见集合常量（{@code [当前租户, __shared__]}）。</p>
     *
     * <p><b>为什么用户 / 部门桶必须用它</b>：用户额度和部门额度是「按人 / 按行政归属跨 Key、
     * 跨租户归集」的桶，不隶属于某个具体租户。若把这类桶写在 {@code tenant_id='default'}，
     * 打开多租户行级隔离后，非 default 租户的过滤器可见集合不含 default → 账本行写进去
     * 读不出来 → 读视图拿到空 → fail-open 放行，额度静默失效。用 {@code __shared__} 既在
     * 过滤器可见集合内、语义也对（全局共享），且 {@link #normalizeTenant(String)} 原样保留
     * 非空值、{@code @PrePersist} 只补空值，读写两侧一致。</p>
     *
     * <p>引用 {@code TenantJpaFilterDefinition.SHARED_TENANT_ID} 的字面值而不直接 import
     * 该类：{@code enterprise.config} 包在 OSS 单仓存在，但账本维度是基础设施类型，
     * 避免对过滤器实现类的编译期依赖。</p>
     */
    public static final String SHARED_SCOPE = "__shared__";

    /** 前缀混淆告警 logger（record 不便用 @Slf4j，静态字段即可）。 */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(QuotaDimension.class);

    /**
     * 判断账本 {@code userId} 列的值是否为部门桶编码。
     *
     * @param userIdValue 账本 userId 列取值
     * @return 以 {@link #DEPARTMENT_PREFIX} 开头时为 {@code true}
     */
    public static boolean isDepartmentBucket(final String userIdValue) {
        return userIdValue != null && userIdValue.startsWith(DEPARTMENT_PREFIX);
    }

    /**
     * 兼容构造器（不含分组维度）。
     *
     * <p>v3.4 之前维度只有五维，本构造器保留原签名并把 {@code groupId} 置为空串哨兵值，
     * 使既有调用点（与单测）语义不变——「未指定分组」与「显式空串」在账本中等价。</p>
     *
     * @param tenantId    租户 ID
     * @param apiKeyId    API Key ID
     * @param userId      用户 ID
     * @param serviceType 服务类型
     * @param model       模型名称
     */
    public QuotaDimension(final String tenantId,
                          final String apiKeyId,
                          final String userId,
                          final String serviceType,
                          final String model) {
        this(tenantId, apiKeyId, userId, serviceType, model, null);
    }

    /**
     * 规范化构造：非租户维度 {@code null} 折叠为 {@link #SENTINEL}，
     * 租户维度 {@code null}/{@code 空串} 折叠为 {@link #TENANT_SENTINEL}。
     */
    public QuotaDimension {
        tenantId = normalizeTenant(tenantId);
        apiKeyId = normalize(apiKeyId);
        userId = normalize(userId);
        serviceType = normalize(serviceType);
        model = normalize(model);
        groupId = normalize(groupId);
    }

    /**
     * 仅指定 API Key 的维度，其余维度取哨兵值。
     * 适用于尚不知道该请求租户 / 用户 / 模型 / 分组的查询。
     *
     * @param apiKeyId API Key ID，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofApiKey(final String apiKeyId) {
        return new QuotaDimension(null, apiKeyId, null, null, null, null);
    }

    /**
     * 指定 Key 与归属租户的维度（多租户账本打标）。
     *
     * <p>与 {@link #ofApiKey(String)} 的区别仅在于租户维度：本方法显式写入调用归属租户，
     * 使账本行带正确 {@code tenant_id}、能被行级过滤器按租户读到。传 {@code null}/空串
     * 仍归一为 {@link #TENANT_SENTINEL}（单租户 default）。</p>
     *
     * @param apiKeyId API Key ID
     * @param tenantId 归属租户（来自 TenantContextHolder 或 Key 绑定）
     * @return 归一化后的维度
     */
    public static QuotaDimension ofApiKey(final String apiKeyId, final String tenantId) {
        return new QuotaDimension(tenantId, apiKeyId, null, null, null, null);
    }

    /**
     * 用户维度桶（按 Key.holder 跨 Key 归集，统计该人全部用量）。
     *
     * <p>租户维度取 {@link #SHARED_SCOPE} 而非 {@link #TENANT_SENTINEL}：用户额度是跨租户
     * 归集的全局桶，用 default 会在多租户行级过滤下读不出来（见 {@link #SHARED_SCOPE} javadoc）。
     * 用户标识填 {@code userId} 列（V1 baseline 现成列，零 DDL）。</p>
     *
     * <p>2026-11-27（中风险整改）：<b>前缀混淆防御</b>——{@code userId} 列的 {@code dept:}
     * 前缀是部门桶的编码约定（见 {@link #ofDepartment(String)}）。若 holder 本身以
     * {@code dept:} 开头，用户桶会被 {@link #isDepartmentBucket(String)} 误判成部门桶、
     * 撞进别人的部门额度；此处告警提示数据面清理该 holder（告警不改变行为，
     * 不做静默剥离以免误伤真实用户名）。</p>
     *
     * @param holder 使用者标识（{@code ApiKey.holder}），null/空串归一为哨兵
     * @return 用户维度桶
     */
    public static QuotaDimension ofUser(final String holder) {
        if (holder != null && holder.startsWith(DEPARTMENT_PREFIX)) {
            LOGGER.warn("用户标识以部门前缀「{}」开头，其用户额度桶将被误认为部门桶，"
                    + "请清理该 holder 命名: holder={}", DEPARTMENT_PREFIX, holder);
        }
        return new QuotaDimension(SHARED_SCOPE, null, holder, null, null, null);
    }

    /**
     * 部门维度桶（按 holder 所属行政部门归集）。
     *
     * <p>V1 无 department 列、{@code groupId} 已被模型分组占用，采用前缀编码：把
     * {@link #DEPARTMENT_PREFIX} + department 填进 {@code userId} 列，与
     * {@link #ofUser(String)} 的纯用户名桶靠前缀区分（约定用户名不会以 {@code dept:} 开头）。
     * 租户维度同样取 {@link #SHARED_SCOPE}。</p>
     *
     * @param department 行政部门名，null/空串归一为哨兵（不产生有效桶）
     * @return 部门维度桶
     */
    public static QuotaDimension ofDepartment(final String department) {
        final String dept = department == null || department.isEmpty()
                ? SENTINEL : DEPARTMENT_PREFIX + department;
        return new QuotaDimension(SHARED_SCOPE, null, dept, null, null, null);
    }

    /**
     * 租户维度桶（按业务归属单元归集：产品线 / 项目组 / 虚拟团队等一切内部业务单元）。
     *
     * <p>租户 ID 取 Key 绑定的 {@code tenantId}（非 default 才生效）。与
     * {@link #ofApiKey(String, String)} 的区别：本方法只打租户标、Key 维留空，用于统计
     * 同一租户下所有 Key 的<b>总量</b>；前者是单 Key 维度的账本行。</p>
     *
     * @param tenantId 归属租户 ID，null/空串归一为 {@link #TENANT_SENTINEL}
     * @return 租户维度桶
     */
    public static QuotaDimension ofTenant(final String tenantId) {
        return new QuotaDimension(tenantId, null, null, null, null, null);
    }

    /**
     * 仅指定分组的维度（模型分组 / 池级配额）。
     *
     * @param groupId 分组（池）名称，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofGroup(final String groupId) {
        return new QuotaDimension(null, null, null, null, null, groupId);
    }

    /**
     * 「分组 + 实例」并存维度（v3.5 治理收口）。
     *
     * <p>单线治理下，账本明细行同时记录：</p>
     * <ul>
     *   <li>{@code groupId} —— 请求命中的模型分组（虚拟模型名，<b>治理主键</b>），
     *       分组级配额判定与「GROUP BY group_id」聚合都基于它；</li>
     *   <li>{@code model} —— 实际落到的已接入模型（实例名，<b>观测明细</b>），
     *       用于组内模型维度的用量下钻。</li>
     * </ul>
     *
     * <p><b>两行而非一行</b>：本维度是「组内成员桶」行，与预留期写入的「分组桶」行
     * （{@link #ofGroup(String)}，{@code model} 为空）<b>并列存在于账本中</b>，
     * 八列唯一键不同（{@code model} 列取值不同）。因此：</p>
     * <ul>
     *   <li><b>分组聚合</b>只统计 {@code model} 为空的分组桶行；</li>
     *   <li><b>组内明细</b>只统计本维度产生的成员桶行。</li>
     * </ul>
     * <p>两者<b>互斥、不可相加</b>——分组桶已包含该分组内全部请求，相加会让分组用量翻倍。</p>
     *
     * @param groupId 分组（池）名称，可为 {@code null}
     * @param model   实例名称，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofGroupAndModel(final String groupId, final String model) {
        return new QuotaDimension(null, null, null, null, model, groupId);
    }

    /**
     * 仅指定模型的维度（已接入模型 / 实例级配额）。
     *
     * @param model 模型（实例）名称，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofModel(final String model) {
        return new QuotaDimension(null, null, null, null, model, null);
    }

    /**
     * 全部维度取哨兵值的空维度。
     *
     * @return 空维度
     */
    public static QuotaDimension empty() {
        return new QuotaDimension(null, null, null, null, null, null);
    }

    /**
     * 把 {@code null} 归一化为空串哨兵值。
     *
     * @param value 原始维度值
     * @return 非 {@code null} 的维度值
     */
    private static String normalize(final String value) {
        return value == null ? SENTINEL : value;
    }

    /**
     * 租户维度归一：{@code null} 与历史空串都折叠为 {@link #TENANT_SENTINEL}。
     *
     * <p>空串一并归一，兼容此前落库的历史账本行（{@code tenant_id=''}）。
     * 项目尚未正式投产、无存量数据迁移脚本——重新初始化即按新口径落库。</p>
     */
    private static String normalizeTenant(final String value) {
        return value == null || value.isEmpty() ? TENANT_SENTINEL : value;
    }
}
