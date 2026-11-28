package org.unreal.modelrouter.auth.security.config.properties;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 统一的API Key数据模型
 * 合并了原来的ApiKeyProperties和ApiKeyInfo类
 * 
 * 安全改进：keyValue 使用 SHA-256 哈希存储（keyHash 字段）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKey {

    /**
     * API Key唯一标识符
     */
    private String keyId;

    /**
     * API Key值 - 仅在创建时返回，其他时候为null以确保安全
     *
     * @deprecated 明文存储 API Key 不安全，此字段仅为创建时返回给用户而保留。
     *             请使用 {@link #keyHash} 字段进行安全存储和验证。
     *             <p>安全说明：</p>
     *             <ul>
     *               <li>keyValue 是明文存储，存在安全风险</li>
     *               <li>keyHash 使用 SHA-256 + 盐值哈希存储，更安全</li>
     *               <li>验证时应使用 ApiKeyHashUtil.verifyKey() 方法</li>
     *             </ul>
     *             <p>迁移示例：</p>
     *             <pre>{@code
     *             // 旧代码 - 不安全
     *             if (apiKey.getKeyValue().equals(inputKey)) { ... }
     *             
     *             // 新代码 - 安全
     *             if (ApiKeyHashUtil.verifyKey(inputKey, apiKey.getKeyHash())) { ... }
     *             }</pre>
     *             <p>此字段将在 v3.0 版本中移除。</p>
     * @see #keyHash
     * @see org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil
     */
    @Deprecated(since = "2.5.2", forRemoval = true)
    @JsonProperty
    private String keyValue;

    /**
     * API Key 的哈希值（SHA-256 + 盐值）
     * 用于安全存储和验证
     */
    @JsonProperty
    private String keyHash;

    /**
     * API Key 的<b>确定性</b>查找哈希（HMAC-SHA256(serverPepper, keyValue)）。
     *
     * <p><b>C6 整改：</b>原 {@link #keyHash} 为随机盐 SHA-256，相同 {@code keyValue} 每次哈希结果
     * 都不同，无法据提交值反查，导致校验路径只能 O(n) 全表扫描。本字段是<b>确定性</b>派生值，
     * 凭客户端提交的 {@code keyValue} 即可算得，用作 O(1) 定位候选密钥的索引；实际真伪仍由
     * {@code keyHash} 的恒定时间比较完成。无明文 {@code keyValue} 的存量密钥（仅含随机盐哈希）
     * 该字段为 {@code null}，校验时回落 O(n) 扫描，并在首次成功使用时惰性补全（灰度迁移）。</p>
     *
     * <p>该值与 {@code keyHash} 同属敏感派生数据，<b>禁止</b>出现在任何对外响应中。</p>
     */
    @JsonProperty
    private String lookupHash;

    /**
     * API Key 前缀（如 "sk-"）
     * 用于识别密钥类型
     */
    private String keyPrefix;

    /**
     * 密钥脱敏展示串（如 {@code sk-9fK2********4aQz}：前 7 位 + 星号 + 末 4 位）。
     *
     * <p>C6 整改后<b>不再存储明文</b>，但「我的密钥」「全部密钥」页面需要一个可展示的
     * 脱敏串。因此只在<b>明文可得的那一刻</b>（创建 / 轮换 / 导入）调用 {@link #maskKey(String)}
     * 算好存下来；存量无明文密钥该字段为 {@code null}，前端显示「—」，不臆造。</p>
     *
     * <p>该串<b>不是完整密钥</b>，无法用于认证，可安全展示与持久化。</p>
     */
    private String keyMasked;

    /**
     * 密钥明文的加密存储（AES-256-GCM，密钥来源见 {@code ApiKeyPlaintextCipher}）。
     *
     * <p>2026-11 用户决策：员工需要在「我的密钥」页随时复制自己的密钥，而 C6 的
     * 「明文不落盘」导致创建后无法找回。改为在明文可得的那一刻（创建/轮换/导入）
     * 加密存一份，需要时经 {@code POST /api/auth/api-keys/mine/{keyId}/reveal}
     * 解密下发——该端点服务端校验归属（仅本人）。</p>
     *
     * <p><b>禁止</b>出现在任何列表 VO / 安全副本 / 导出格式中；该字段仅服务于
     * 归属人自助取回，不能替代 {@code keyHash} 的认证校验。</p>
     */
    private String keyEncrypted;

    /**
     * 生成密钥的脱敏展示串：保留前 7 位（含 {@code sk-} 前缀）、末 4 位，中间星号遮蔽。
     *
     * <p>必须在明文可得时调用并持久化结果（{@link #keyMasked}），明文随即丢弃。</p>
     *
     * @param keyValue 完整密钥明文
     * @return 脱敏串；入参为空时返回 {@code null}
     */
    public static String maskKey(final String keyValue) {
        if (keyValue == null || keyValue.isBlank()) {
            return null;
        }
        final String prefix = "sk-";
        if (keyValue.length() <= 12) {
            // 太短，无法安全地保留首尾——整体遮蔽
            return prefix + "****";
        }
        return keyValue.substring(0, 7)
                + "********"
                + keyValue.substring(keyValue.length() - 4);
    }

    /**
     * 允许使用的 IP 白名单
     * 为空表示不限制
     */
    private List<String> allowedIpAddresses;

    /**
     * 每日请求上限（0 表示无限制）
     */
    @Builder.Default
    private long dailyRequestLimit = 0L;

    /**
     * 每日 Token 使用上限（0 表示无限制）
     * 统计 prompt_tokens + completion_tokens 总和
     */
    @Builder.Default
    private long dailyTokenLimit = 0L;

    /**
     * 每小时请求上限（0 表示无限制）。对齐上游 3.4.0 TA3：多窗口配额。
     */
    @Builder.Default
    private long hourlyRequestLimit = 0L;

    /**
     * 每小时 Token 使用上限（0 表示无限制）
     */
    @Builder.Default
    private long hourlyTokenLimit = 0L;

    /**
     * 每月请求上限（0 表示无限制）
     */
    @Builder.Default
    private long monthlyRequestLimit = 0L;

    /**
     * 每月 Token 使用上限（0 表示无限制）
     */
    @Builder.Default
    private long monthlyTokenLimit = 0L;

    /**
     * 每分钟请求速率上限（0 表示无限制）
     * 使用滑动窗口算法实现
     */
    @Builder.Default
    private int rateLimitPerMinute = 0;

    /**
     * 配额告警阈值（0.0-1.0），达到此比例时触发告警
     * 默认 0.8 表示使用量达到 80% 时告警
     */
    @Builder.Default
    private double quotaAlertThreshold = 0.8;

    /**
     * API Key描述信息
     */
    private String description;

    /**
     * 权限列表
     */
    private List<String> permissions;

    /**
     * 可访问的模型分组（资源池 / pool）白名单（按分组名匹配）。
     *
     * <p><b>v3.5 治理收口：</b>对外调用的唯一入口是模型分组（虚拟模型名），
     * 因此本白名单是<b>唯一的模型级授权维度</b>——原先并存的 {@code allowedModels}
     * （已接入模型 / 实例名白名单）已移除，实例不再能绕过所属分组被直接调用。</p>
     *
     * <p>为空（null 或空集合）表示允许该服务类型下所有分组（兼容老 Key）。</p>
     */
    private List<String> allowedGroups;

    /**
     * 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime expiresAt;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime createdAt;

    /**
     * 创建者用户名
     */
    private String createdBy;

    /**
     * 密钥持有人（分配给谁）。
     *
     * <p>与 {@code createdBy} 的区别：{@code createdBy} 是<b>操作者</b>（谁在控制台点了创建），
     * {@code holder} 是<b>使用者</b>（这个密钥发给谁用）。原型「创建密钥」表单里的
     * 「分配给谁？」落到本字段；为空时回落为 {@code createdBy}。</p>
     *
     * <p>存储复用同一份 JSON store（{@code config_data}），无独立表结构，
     * 因此无需 Flyway 迁移脚本。</p>
     */
    private String holder;

    /**
     * 归属租户（多租户：API Key 的调用归属）。
     *
     * <p>用户 / 账号是平台级共享的，智能体凭 API Key 调用虚拟模型时不会带
     * {@code X-Tenant-Id} 请求头，因此调用归属必须<b>绑在 Key 上</b>而非用户上：
     * 签发时把当前租户写入本字段，鉴权成功后注入租户上下文，调用历史 / 用量 /
     * 额度账本据此打标并按租户行级隔离。空值 / 空串等价单租户的 {@code default}。</p>
     *
     * <p>存储复用同一份 JSON store（{@code config_data}），无独立表结构，
     * 因此无需 Flyway 迁移脚本；存量 Key 读不到该字段时回落 {@code default}。</p>
     */
    private String tenantId;

    /**
     * 创建者 IP 地址
     */
    private String creatorIpAddress;

    /**
     * 密钥轮换周期（天数），0 表示不自动轮换
     */
    @Builder.Default
    private int rotationPeriodDays = 0;

    /**
     * 上次轮换时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime lastRotatedAt;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 元数据信息
     */
    private Map<String, Object> metadata;

    /**
     * 使用统计信息
     */
    private UsageStatistics usage;

    /**
     * 检查API Key是否已过期
     *
     * @return 是否过期
     */
    @JsonProperty("expired")
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 用于反序列化expired字段的setter方法（无实际作用，仅用于兼容旧数据）
     */
    @JsonProperty("expired")
    public void setExpired(final boolean expired) {
        // 仅用于反序列化，实际值由expiresAt字段计算得出
    }

    /**
     * 检查API Key是否有效（启用且未过期）
     *
     * @return 是否有效
     */
    @JsonIgnore
    public boolean isValid() {
        return enabled && !isExpired();
    }

    /**
     * 检查是否具有指定权限（大小写不敏感）
     *
     * @param permission 权限名称
     * @return 是否具有权限
     */
    public boolean hasPermission(final String permission) {
        if (permissions == null || permission == null) {
            return false;
        }

        return permissions.stream()
                .anyMatch(p -> p != null && p.equalsIgnoreCase(permission));
    }

    /**
     * 检查指定 IP 是否在白名单中
     * 如果白名单为空，则允许所有 IP
     *
     * @param ipAddress IP 地址
     * @return 是否允许
     */
    public boolean isIpAllowed(final String ipAddress) {
        if (allowedIpAddresses == null || allowedIpAddresses.isEmpty()) {
            return true;
        }
        // 白名单非空却拿不到客户端 IP：一律拒绝（fail-closed，issue #693）。
        // 同时避免不可变集合（List.of）的 contains(null) 抛 NPE 把 401 变成 500。
        if (ipAddress == null) {
            return false;
        }
        return allowedIpAddresses.contains(ipAddress);
    }

    /**
     * 检查指定模型分组名是否在白名单内。
     * 白名单为空（null 或空集合）视为放行该服务类型下所有分组（兼容老 Key）。
     *
     * @param modelName 调用方请求中的 model 参数（v3.5 起必为模型分组名）
     * @return 是否允许调用
     */
    public boolean isModelAllowed(final String modelName) {
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            return true;
        }
        return allowedGroups.contains(modelName);
    }

    /**
     * 检查是否超过每日请求限制
     *
     * @return 是否超限
     */
    // #811/#715：派生判定值不参与 JSON 往返（无 setter，序列化后读回即未知字段）。
    @JsonIgnore
    public boolean isDailyLimitExceeded() {
        if (dailyRequestLimit <= 0) {
            return false;
        }
        if (usage == null) {
            return false;
        }
        String today = LocalDateTime.now().toLocalDate().toString();
        Long todayCount = usage.getDailyUsage() != null ? usage.getDailyUsage().get(today) : null;
        return todayCount != null && todayCount.longValue() >= dailyRequestLimit;
    }

    /**
     * 检查是否超过每日 Token 使用限制
     *
     * @return 是否超限
     */
    @JsonIgnore
    public boolean isDailyTokenLimitExceeded() {
        if (dailyTokenLimit <= 0) {
            return false;
        }
        if (usage == null || usage.getDailyTokenUsage() == null) {
            return false;
        }
        String today = LocalDateTime.now().toLocalDate().toString();
        Long todayTokens = usage.getDailyTokenUsage().get(today);
        return todayTokens != null && todayTokens.longValue() >= dailyTokenLimit;
    }

    // ==================== P0-A：小时 / 月窗口配额判定 ====================
    //
    // 此前 hourlyRequestLimit / hourlyTokenLimit / monthlyRequestLimit / monthlyTokenLimit
    // 四个限额字段「可配置、可展示、但不生效」——认证链只校验日配额，导致
    // 「以为配了月额度、实际上无限用」。这里补齐判定，口径与日配额完全一致：
    // 限额为 0 表示不限制；用量 >= 限额即视为超限。
    //
    // 【多副本一致性局限（P0-A 必读）】
    // 本组判定基于 UsageStatistics 的「单副本内存计数 + 持久化」用量统计，不是全局精确配额：
    //   ① 多副本部署时各副本各自计数，实际用量会被低估约 N 倍（N = 副本数），
    //      因此这里的拦截在多副本下是「尽力而为」的软拦截；
    //   ② 精确拦截必须启用配额账本（trunkline.quota.enabled=true）——
    //      账本路径（QuotaLedgerService / QuotaEnforcementService）的 QuotaLimitSet
    //      已包含 monthTokens 等月度额度字段，账本开启时月额度本就支持；
    //   ③ 本组判定解决的是「账本关闭（默认）时配了额度完全不生效」这个更严重的问题。
    // 详见 UsageStatistics 类注释（同段说明）与 ApiKeyValidator 的相应说明。

    /**
     * 检查是否超过每小时请求限制（P0-A）
     *
     * <p><b>判定口径</b>：自然小时窗口（本地时区、整点对齐，与配额账本
     * {@code QuotaWindow.HOUR} 同口径）；{@code hourlyRequestLimit <= 0} 表示不限制；
     * 本小时累计请求数 {@code >= } 限额即视为超限（与日配额口径一致）。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @return 是否超限
     */
    @JsonIgnore
    public boolean isHourlyRequestLimitExceeded() {
        if (hourlyRequestLimit <= 0) {
            return false;
        }
        return getCurrentHourRequestCount() >= hourlyRequestLimit;
    }

    /**
     * 检查是否超过每小时 Token 使用限制（P0-A）
     *
     * <p><b>判定口径</b>：自然小时窗口（本地时区、整点对齐，与配额账本
     * {@code QuotaWindow.HOUR} 同口径）；{@code hourlyTokenLimit <= 0} 表示不限制；
     * 本小时累计 Token 数 {@code >= } 限额即视为超限。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @return 是否超限
     */
    @JsonIgnore
    public boolean isHourlyTokenLimitExceeded() {
        if (hourlyTokenLimit <= 0) {
            return false;
        }
        return getCurrentHourTokenUsage() >= hourlyTokenLimit;
    }

    /**
     * 检查是否超过每月请求限制（P0-A）
     *
     * <p><b>判定口径</b>：自然月窗口（本地时区、当月 1 日 0 点，与配额账本
     * {@code QuotaWindow.MONTH} 同口径）；{@code monthlyRequestLimit <= 0} 表示不限制；
     * 本月累计请求数 {@code >= } 限额即视为超限（与日配额口径一致）。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @return 是否超限
     */
    @JsonIgnore
    public boolean isMonthlyRequestLimitExceeded() {
        if (monthlyRequestLimit <= 0) {
            return false;
        }
        return getCurrentMonthRequestCount() >= monthlyRequestLimit;
    }

    /**
     * 检查是否超过每月 Token 使用限制（P0-A）
     *
     * <p><b>判定口径</b>：自然月窗口（本地时区、当月 1 日 0 点，与配额账本
     * {@code QuotaWindow.MONTH} 同口径）；{@code monthlyTokenLimit <= 0} 表示不限制；
     * 本月累计 Token 数 {@code >= } 限额即视为超限。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @return 是否超限
     */
    @JsonIgnore
    public boolean isMonthlyTokenLimitExceeded() {
        if (monthlyTokenLimit <= 0) {
            return false;
        }
        return getCurrentMonthTokenUsage() >= monthlyTokenLimit;
    }

    /**
     * 获取当前小时窗口的请求数（P0-A）
     *
     * @return 当前自然小时窗口内的请求数
     */
    @JsonIgnore
    public long getCurrentHourRequestCount() {
        if (usage == null || usage.getHourlyUsage() == null) {
            return 0L;
        }
        return usage.getHourlyUsage().getOrDefault(UsageStatistics.currentHourKey(), 0L);
    }

    /**
     * 获取当前小时窗口的 Token 用量（P0-A）
     *
     * @return 当前自然小时窗口内的 Token 数
     */
    @JsonIgnore
    public long getCurrentHourTokenUsage() {
        if (usage == null || usage.getHourlyTokenUsage() == null) {
            return 0L;
        }
        return usage.getHourlyTokenUsage().getOrDefault(UsageStatistics.currentHourKey(), 0L);
    }

    /**
     * 获取当前月窗口的请求数（P0-A）
     *
     * @return 当前自然月窗口内的请求数
     */
    @JsonIgnore
    public long getCurrentMonthRequestCount() {
        if (usage == null || usage.getMonthlyUsage() == null) {
            return 0L;
        }
        return usage.getMonthlyUsage().getOrDefault(UsageStatistics.currentMonthKey(), 0L);
    }

    /**
     * 获取当前月窗口的 Token 用量（P0-A）
     *
     * @return 当前自然月窗口内的 Token 数
     */
    @JsonIgnore
    public long getCurrentMonthTokenUsage() {
        if (usage == null || usage.getMonthlyTokenUsage() == null) {
            return 0L;
        }
        return usage.getMonthlyTokenUsage().getOrDefault(UsageStatistics.currentMonthKey(), 0L);
    }

    /**
     * 获取今日 Token 使用量
     *
     * @return 今日 Token 使用量
     */
    @JsonIgnore
    public long getTodayTokenUsage() {
        if (usage == null || usage.getDailyTokenUsage() == null) {
            return 0L;
        }
        String today = LocalDateTime.now().toLocalDate().toString();
        return usage.getDailyTokenUsage().getOrDefault(today, 0L);
    }

    /**
     * 获取今日请求数
     *
     * @return 今日请求数
     */
    @JsonIgnore
    public long getTodayRequestCount() {
        if (usage == null || usage.getDailyUsage() == null) {
            return 0L;
        }
        String today = LocalDateTime.now().toLocalDate().toString();
        return usage.getDailyUsage().getOrDefault(today, 0L);
    }

    /**
     * 检查配额使用是否达到告警阈值
     *
     * @return 是否需要告警
     */
    @JsonIgnore
    public boolean isQuotaAlertTriggered() {
        if (quotaAlertThreshold <= 0 || quotaAlertThreshold >= 1.0) {
            return false;
        }
        if (dailyRequestLimit > 0) {
            long todayCount = getTodayRequestCount();
            if (todayCount >= dailyRequestLimit * quotaAlertThreshold) {
                return true;
            }
        }
        if (dailyTokenLimit > 0) {
            long todayTokens = getTodayTokenUsage();
            if (todayTokens >= dailyTokenLimit * quotaAlertThreshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否需要进行密钥轮换
     * 如果设置了轮换周期，且距离上次轮换已超过周期天数，则需要轮换
     *
     * @return 是否需要轮换
     */
    @JsonIgnore
    public boolean needsRotation() {
        if (rotationPeriodDays <= 0) {
            return false; // 不自动轮换
        }
        if (lastRotatedAt == null) {
            // 从未轮换过，检查创建时间
            if (createdAt == null) {
                return false;
            }
            long daysSinceCreation = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now());
            return daysSinceCreation >= rotationPeriodDays;
        }
        long daysSinceLastRotation = ChronoUnit.DAYS.between(lastRotatedAt, LocalDateTime.now());
        return daysSinceLastRotation >= rotationPeriodDays;
    }

    /**
     * 验证提供的 API Key 是否匹配存储的哈希值
     *
     * @param providedKey 用户提供的原始 API Key
     * @return 是否匹配
     */
    public boolean verifyKey(final String providedKey) {
        if (keyHash != null && !keyHash.isEmpty()) {
            return ApiKeyHashUtil.verifyApiKey(providedKey, keyHash);
        }
        // 兼容旧数据：如果只有 keyValue（明文存储），则直接比较
        // 这种情况应该尽快迁移到哈希存储
        if (keyValue != null && !keyValue.isEmpty()) {
            return keyValue.equals(providedKey);
        }
        return false;
    }

    /**
     * 创建一个不包含敏感信息的安全副本，用于API响应
     *
     * @return 安全的ApiKey副本
     */
    public ApiKey createSecureCopy() {
        return ApiKey.builder()
                .keyId(this.keyId)
                .keyValue(null)
                .keyHash(null)
                .lookupHash(null)
                .keyPrefix(this.keyPrefix)
                .keyMasked(this.keyMasked)
                .keyEncrypted(null)
                .allowedIpAddresses(this.allowedIpAddresses)
                .dailyRequestLimit(this.dailyRequestLimit)
                .dailyTokenLimit(this.dailyTokenLimit)
                .hourlyRequestLimit(this.hourlyRequestLimit)
                .hourlyTokenLimit(this.hourlyTokenLimit)
                .monthlyRequestLimit(this.monthlyRequestLimit)
                .monthlyTokenLimit(this.monthlyTokenLimit)
                .rateLimitPerMinute(this.rateLimitPerMinute)
                .quotaAlertThreshold(this.quotaAlertThreshold)
                .description(this.description)
                .permissions(this.permissions)
                .allowedGroups(this.allowedGroups)
                .expiresAt(this.expiresAt)
                .createdAt(this.createdAt)
                .createdBy(this.createdBy)
                .holder(this.holder)
                .creatorIpAddress(this.creatorIpAddress)
                // 归属租户是调用归属元数据（非敏感字段），必须随安全副本传递。
                // 认证成功后 CustomReactiveAuthenticationManager 从本副本读 tenantId 注入
                // ApiKeyAuthentication；缺失会让无 X-Tenant-Id 头的智能体调用回落 default，
                // 组织维「租户配额」随即被 sentinel 跳过（表现为租户限额不生效）。
                .tenantId(this.tenantId)
                .rotationPeriodDays(this.rotationPeriodDays)
                .lastRotatedAt(this.lastRotatedAt)
                .enabled(this.enabled)
                .metadata(this.metadata)
                .usage(this.usage)
                .build();
    }

    /**
     * 创建一个包含keyValue的副本，仅用于创建API Key时的响应
     * 注意：keyValue 仅在此方法中返回一次，其他任何时候都不应返回
     *
     * @param originalKeyValue 原始的未哈希的 keyValue（仅在创建时可用）
     * @return 包含keyValue的ApiKey副本
     */
    public ApiKey createCreationResponse(final String originalKeyValue) {
        return ApiKey.builder()
                .keyId(this.keyId)
                .keyValue(originalKeyValue)
                .keyHash(null)
                .lookupHash(null)
                .keyPrefix(this.keyPrefix)
                .keyMasked(this.keyMasked)
                .keyEncrypted(null)
                .allowedIpAddresses(this.allowedIpAddresses)
                .dailyRequestLimit(this.dailyRequestLimit)
                .dailyTokenLimit(this.dailyTokenLimit)
                .hourlyRequestLimit(this.hourlyRequestLimit)
                .hourlyTokenLimit(this.hourlyTokenLimit)
                .monthlyRequestLimit(this.monthlyRequestLimit)
                .monthlyTokenLimit(this.monthlyTokenLimit)
                .rateLimitPerMinute(this.rateLimitPerMinute)
                .quotaAlertThreshold(this.quotaAlertThreshold)
                .description(this.description)
                .permissions(this.permissions)
                .allowedGroups(this.allowedGroups)
                .expiresAt(this.expiresAt)
                .createdAt(this.createdAt)
                .createdBy(this.createdBy)
                .holder(this.holder)
                .creatorIpAddress(this.creatorIpAddress)
                .rotationPeriodDays(this.rotationPeriodDays)
                .lastRotatedAt(this.lastRotatedAt)
                .enabled(this.enabled)
                .metadata(this.metadata)
                .usage(this.usage)
                .build();
    }

    /**
     * 生成安全的API Key值
     *
     * @param prefix API Key前缀（如"sk-"）
     * @param length API Key长度（不包括前缀）
     * @return 生成的API Key
     */
    public static String generateApiKey(final String prefix, final int length) {
        // 使用局部变量避免修改 final 参数
        final String actualPrefix = prefix != null ? prefix : "sk-";
        final int actualLength = length > 0 ? length : 32;

        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(actualPrefix);
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }

        return sb.toString();
    }

    /**
     * 生成默认的API Key值（前缀为"sk-"，长度为32）
     *
     * @return 生成的API Key
     */
    public static String generateApiKey() {
        return generateApiKey("sk-", 32);
    }
}