package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchExportVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportResult;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreationVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyListVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyModelScopeVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyWithModelScopeVO;
import org.unreal.modelrouter.auth.security.event.ApiKeyAuditEvent;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.auth.security.quota.QuotaLimits;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.enterprise.spi.DataClassification;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Primary
@Service
public class ApiKeyService {

    private final Map<String, ApiKey> apiKeyCache = new ConcurrentHashMap<>();
    private final Map<String, String> keyIdIndex = new ConcurrentHashMap<>();
    private final SecurityProperties securityProperties;
    /** #811/#715：用量冻结快照的序列化器（per-Key 锁内使用） */
    private final ObjectMapper objectMapper;

    /** 是否定期从共享存储刷新本副本 API Key 缓存（#162） */
    @Value("${trunkline.security.api-key.cache-refresh-enabled:true}")
    private boolean cacheRefreshEnabled;

    @Autowired private ApiKeyValidator apiKeyValidator;
    @Autowired private ApiKeyBatchService apiKeyBatchService;
    @Autowired private ApiKeyPersistenceService apiKeyPersistenceService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    /** 密钥明文加密器（「我的密钥」复制能力的底层，2026-11 用户决策） */
    @Autowired private ApiKeyPlaintextCipher plaintextCipher;
    /** 白名单与权限分类一致性校验器（2026-10-24 新增） */
    @Autowired private ApiKeyWhitelistValidator apiKeyWhitelistValidator;

    /**
     * JWT 账户服务：用于校验「分配给谁（holder）」必须是真实存在的账户。
     * 可选注入（测试上下文可能没有该 Bean）——缺失时跳过存在性校验。
     */
    @Autowired(required = false) private JwtAccountService jwtAccountService;

    /** O(1) 查找索引（C6 整改） */
    @Autowired(required = false) private ApiKeyLookupIndex apiKeyLookupIndex;

    /**
     * 分组扩展信息解析器（P0-L：员工侧不再需要拉全公司资源池即可拿到友好名与加权均价）。
     *
     * <p>可选注入：极简测试上下文未装配该 Bean 时降级为「无分组信息」，接口照常返回密钥列表。</p>
     */
    @Autowired(required = false) private ApiKeyModelScopeResolver apiKeyModelScopeResolver;

    @Autowired
    public ApiKeyService(@Qualifier("jpaStoreManager") StoreManager sm,
                         ObjectMapper om, SecurityProperties sp) {
        this.objectMapper = om;
        this.securityProperties = sp;
    }

    public Mono<ApiKey> validateApiKey(String kv) { return validateApiKey(kv, null, null); }

    public Mono<ApiKey> validateApiKey(String kv, String ep, String ip) {
        return Mono.defer(() -> {
            var r = apiKeyValidator.validateFully(kv, apiKeyCache, ip);
            if (r.isSuccess()) {
                // 派生索引（lookupIndex）可能持有陈旧对象：定时 refreshCacheFromSharedStore 会
                // apiKeyCache.putAll 整批替换对象，而 lookupIndex 的 rebuild 与之存在竞争窗口；
                // 认证热路径改以主存储 apiKeyCache 的权威对象为准，避免拿到的 ApiKey 缺字段
                // （如 tenantId=null → 组织维租户配额被 sentinel 静默跳过）。
                // 注意：validateFully 返回的是 createSecureCopy()，其 keyHash 被置 null，
                // 不能用它反查缓存；改用 keyId → keyIdIndex → keyHash 拿到权威对象。
                ApiKey resolved = r.getApiKey();
                if (resolved != null && resolved.getKeyId() != null) {
                    String authoritativeHash = keyIdIndex.get(resolved.getKeyId());
                    if (authoritativeHash != null) {
                        ApiKey authoritative = apiKeyCache.get(authoritativeHash);
                        if (authoritative != null) {
                            resolved = authoritative;
                        }
                    }
                }
                updateUsageStatistics(resolved.getKeyId(), true);
                eventPublisher.publishEvent(ApiKeyAuditEvent.used(resolved.getKeyId(), ep, ip, true));
                return Mono.just(resolved);
            }
            if (r.getKeyId() != null) {
                updateUsageStatistics(r.getKeyId(), false);
                eventPublisher.publishEvent(ApiKeyAuditEvent.used(r.getKeyId(), ep, ip, false));
            }
            String et = r.getFailureType() == ApiKeyValidator.ValidationFailureType.FORMAT_ERROR
                    ? "API_KEY_MISSING"
                    : r.getFailureType() == ApiKeyValidator.ValidationFailureType.NOT_FOUND
                    ? "API_KEY_INVALID" : "API_KEY_VALIDATION_FAILED";
            eventPublisher.publishEvent(ApiKeyAuditEvent.securityEvent(et, r.getErrorMessage(), r.getKeyId(), ip));
            return Mono.error(r.toException());
        });
    }

    public Mono<ApiKeyCreationVO> createApiKey(ApiKeyCreateRequest req) { return createApiKey(req, "system", null); }

    public Mono<ApiKeyCreationVO> createApiKey(ApiKeyCreateRequest req, String by, String ip) {
        return createApiKey(req, by, ip, null);
    }

    /** controller 透传 X-Tenant-Id：新建 Key 绑定顶部当前租户，唯一可靠来源。 */
    public Mono<ApiKeyCreationVO> createApiKey(final ApiKeyCreateRequest req, final String by, final String ip,
                                               final String tenantHeader) {
        return Mono.fromCallable(() -> {
            // issue #96：创建路径此前对 quotaAlertThreshold 完全没有校验，现与更新路径统一区间
            QuotaLimits.validateAlertThreshold(req.getQuotaAlertThreshold());
            validateLimitNonNegative("hourlyRequestLimit", req.getHourlyRequestLimit());
            validateLimitNonNegative("hourlyTokenLimit", req.getHourlyTokenLimit());
            validateLimitNonNegative("monthlyRequestLimit", req.getMonthlyRequestLimit());
            validateLimitNonNegative("monthlyTokenLimit", req.getMonthlyTokenLimit());
            String kid = req.getKeyId() != null && !req.getKeyId().isEmpty()
                    ? req.getKeyId()
                    : "key-" + UUID.randomUUID().toString().substring(0, 8);
            if (keyIdIndex.containsKey(kid)) throw new IllegalArgumentException("API Key ID已存在: " + kid);
            String kv = ApiKey.generateApiKey("sk-", 32);
            String kh = ApiKeyHashUtil.hashApiKey(kv);
            List<String> validatedPermissions = validatePermissions(req.getPermissions());
            // 2026-11：前端已移除「可调用的模型分类」手选字段——未显式提供时按白名单自动推导
            // （白名单空 = 不限制模型 = 全部分类），避免配出「永远 403」的密钥
            if (validatedPermissions == null || validatedPermissions.isEmpty()) {
                validatedPermissions = apiKeyWhitelistValidator.derivePermissions(req.getAllowedGroups());
            }
            // 白名单与权限分类一致性校验（防止配出「永远调不通」的密钥）
            apiKeyWhitelistValidator.validate(validatedPermissions, req.getAllowedGroups());
            // 持有人（分配给谁）：未指定时回落为操作者本人
            final String holder = resolveHolder(req.getHolder(), by);
            // 归属租户（多租户强制绑定）：用户平台级共享，Key 承载调用归属。
            // 「全部密钥」是跨租户管理视图，不能靠当前请求上下文隐式推断——必须显式指定。
            // 归属租户取当前请求上下文（顶部租户切换器经 X-Tenant-Id 注入），唯一数据源
            final String tenantId = resolveCreationTenantIdFromHeader(tenantHeader);
            // #9 数据分级出境：密级作为服务端信任锚写入 metadata，请求头自报不采信。
            // fromCode 对 null / 空 / 未知值统一回落 PUBLIC（保持首版默认 PUBLIC 口径）。
            final String classification = DataClassification.fromCode(req.getClassification()).getCode();
            final Map<String, Object> metadata = new HashMap<>();
            metadata.put("classification", classification);
            ApiKey ak = ApiKey.builder().keyId(kid).keyHash(kh).keyPrefix("sk-")
                .tenantId(tenantId)
                .metadata(metadata)
                // 明文只在创建时可得：此刻落盘脱敏展示串与加密明文，明文本身不落盘（C6 + 2026-11 复制能力）
                .keyMasked(ApiKey.maskKey(kv))
                .keyEncrypted(plaintextCipher.encrypt(kv))
                .description(req.getDescription())
                .permissions(validatedPermissions)
                .allowedGroups(req.getAllowedGroups())
                .enabled(req.getEnabled() != null ? req.getEnabled() : true)
                .expiresAt(req.getExpiresAt()).createdAt(LocalDateTime.now()).createdBy(by).holder(holder)
                .creatorIpAddress(ip)
                .rotationPeriodDays(req.getRotationPeriodDays() != null ? req.getRotationPeriodDays() : 0)
                .allowedIpAddresses(req.getAllowedIpAddresses())
                .dailyRequestLimit(req.getDailyRequestLimit() != null ? req.getDailyRequestLimit() : 0L)
                .dailyTokenLimit(req.getDailyTokenLimit() != null ? req.getDailyTokenLimit() : 0L)
                .hourlyRequestLimit(req.getHourlyRequestLimit() != null ? req.getHourlyRequestLimit() : 0L)
                .hourlyTokenLimit(req.getHourlyTokenLimit() != null ? req.getHourlyTokenLimit() : 0L)
                .monthlyRequestLimit(req.getMonthlyRequestLimit() != null ? req.getMonthlyRequestLimit() : 0L)
                .monthlyTokenLimit(req.getMonthlyTokenLimit() != null ? req.getMonthlyTokenLimit() : 0L)
                .rateLimitPerMinute(req.getRateLimitPerMinute() != null ? req.getRateLimitPerMinute() : 0)
                .quotaAlertThreshold(req.getQuotaAlertThreshold() != null ? req.getQuotaAlertThreshold() : 0.8)
                .usage(UsageStatistics.builder().totalRequests(0L).successfulRequests(0L).failedRequests(0L)
                    .dailyUsage(new HashMap<>()).dailyTokenUsage(new HashMap<>())
                    .hourlyUsage(new HashMap<>()).hourlyTokenUsage(new HashMap<>())
                    .monthlyUsage(new HashMap<>()).monthlyTokenUsage(new HashMap<>()).build()).build();
            if (apiKeyLookupIndex != null) {
                apiKeyLookupIndex.attachLookupHash(ak, kv);
                apiKeyLookupIndex.index(ak);
            }
            apiKeyCache.put(kh, ak); keyIdIndex.put(kid, kh);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            eventPublisher.publishEvent(ApiKeyAuditEvent.created(kid, by, ip));
            log.info("创建API Key成功: {}", kid);
            // 2026-11 安全加固：管理员代员工建密钥时，响应不回传明文（C6）。
            // 只有「持有人 = 操作者本人」时才回显明文（如快速开始向导自建密钥）；
            // 代建场景下持有人登录后在「我的密钥」里查看并复制（keyEncrypted 解密通道）。
            final boolean selfHeld = by != null && by.equals(holder);
            return ApiKeyCreationVO.builder().keyId(kid).keyValue(selfHeld ? kv : null).description(ak.getDescription())
                .classification(classification)
                .createdBy(by)
                .holder(holder)
                .permissions(ak.getPermissions())
                .allowedGroups(ak.getAllowedGroups())
                .enabled(ak.isEnabled()).createdAt(ak.getCreatedAt())
                .expiresAt(ak.getExpiresAt())
                .dailyRequestLimit(ak.getDailyRequestLimit())
                .dailyTokenLimit(ak.getDailyTokenLimit())
                .hourlyRequestLimit(ak.getHourlyRequestLimit())
                .hourlyTokenLimit(ak.getHourlyTokenLimit())
                .monthlyRequestLimit(ak.getMonthlyRequestLimit())
                .monthlyTokenLimit(ak.getMonthlyTokenLimit())
                .rateLimitPerMinute(ak.getRateLimitPerMinute())
                .quotaAlertThreshold(ak.getQuotaAlertThreshold())
                .warning(selfHeld
                    ? "密钥值只会显示一次，请妥善保存！"
                    : "密钥已创建并加密保存，持有人可在「我的密钥」中查看并复制密钥值。").build();
        });
    }

    /**
     * 解析密钥持有人（原型「分配给谁？」）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>请求未带 holder 或为空白 → 回落为当前操作者 {@code fallback}</li>
     *   <li>请求带了 holder → 必须是库中已存在的账户，否则拒绝
     *       （否则会写出永远查不到人的「幽灵持有人」）</li>
     * </ul>
     *
     * @param requested 前端传入的持有人（可空）
     * @param fallback  回落值（当前操作者）
     * @return 归一化后的持有人；两者皆空时返回 {@code null}
     */
    private String resolveHolder(final String requested, final String fallback) {
        final String trimmed = requested == null ? null : requested.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            final String fb = fallback == null ? null : fallback.trim();
            return (fb == null || fb.isEmpty()) ? null : fb;
        }
        if (jwtAccountService != null && !jwtAccountService.accountExists(trimmed)) {
            throw new IllegalArgumentException("持有人不存在: " + trimmed);
        }
        return trimmed;
    }

    /**
     * 解析新建 Key 的归属租户：直接取当前请求上下文租户（由顶部租户切换器经
     * {@code X-Tenant-Id} 注入，请求拦截器每请求携带）。租户只有「顶部当前租户」一个数据源，
     * 不再由创建表单显式指定——避免表单与顶部两处选择不一致。单租户（无 delegate）下
     * 上下文恒为 {@code default}，行为零变化。
     */
    private String resolveCreationTenantId() {
        final String normalized = TenantContextHolder.normalize(TenantContextHolder.currentTenantId());
        if (!TenantContextHolder.isValidTenantId(normalized)) {
            throw new IllegalArgumentException("无法识别当前租户，请先在顶部选择租户");
        }
        return normalized;
    }

    /**
     * 新建 Key 的归属租户：优先用 controller 透传的 {@code X-Tenant-Id}（顶部切换器唯一可靠来源），
     * 缺失时回落上下文（内部/单测调用）。单租户恒 default，行为零变化。
     */
    private String resolveCreationTenantIdFromHeader(final String tenantHeader) {
        if (!TenantContextHolder.isMultiTenant()) {
            return TenantContextHolder.normalize(TenantContextHolder.currentTenantId());
        }
        final String source = (tenantHeader != null && !tenantHeader.isBlank())
                ? tenantHeader : TenantContextHolder.currentTenantId();
        final String normalized = TenantContextHolder.normalize(source);
        if (!TenantContextHolder.isValidTenantId(normalized)) {
            throw new IllegalArgumentException("无法识别当前租户，请先在顶部选择租户");
        }
        return normalized;
    }

    // ===== 密钥链路多租户隔离（2026-11-19）=====
    //
    // 关键时序：管理端 controller 方法在「装配阶段」执行——此时 TenantContextWebFilter 的
    // Mono.defer 尚未订阅、租户还没写进 ThreadLocal/Reactor Context。因此绝不能在装配阶段
    // 读 TenantContextHolder.currentTenantId()（那正是「切换租户列表不变」的根因），必须由
    // controller 从 HTTP 请求头显式取 X-Tenant-Id 透传进来，再在订阅阶段（Mono.fromCallable
    // 内）用下面的 helper 归一化并过滤。请求头是此链路唯一可靠、与顶部切换器同源的租户来源。

    /**
     * 管理端请求的租户作用域：多租户下取请求头归一化的租户 ID；单租户返回 {@code null}（不过滤）。
     */
    public String tenantScope(final String tenantHeader) {
        return TenantContextHolder.isMultiTenant()
                ? TenantContextHolder.normalize(tenantHeader)
                : null;
    }

    /** 密钥是否属于作用域；{@code scope=null}（单租户）恒成立。 */
    private static boolean inScope(final ApiKey ak, final String scope) {
        return scope == null || scope.equals(TenantContextHolder.normalize(ak.getTenantId()));
    }

    /** 供配额等兄弟服务复用的归属判定：按 keyId 判断密钥是否属于 scope。 */
    public boolean isKeyInScope(final String keyId, final String scope) {
        if (scope == null) return true;
        final String kh = keyIdIndex.get(keyId);
        final ApiKey ak = kh != null ? apiKeyCache.get(kh) : null;
        return ak != null && inScope(ak, scope);
    }

    /** 取密钥并校验归属；跨租户访问按「不存在」处理（404），不泄露他租户密钥是否存在。 */
    private ApiKey requireInScope(final String kid, final String scope) {
        final String kh = keyIdIndex.get(kid);
        if (kh == null) throw new ApiKeyNotFoundException("API Key不存在: " + kid);
        final ApiKey ak = apiKeyCache.get(kh);
        if (ak == null) throw new ApiKeyNotFoundException("API Key缓存不一致: " + kid);
        if (!inScope(ak, scope)) throw new ApiKeyNotFoundException("API Key不存在: " + kid);
        return ak;
    }

    /**
     * 校验多窗口配额限额字段非负（{@code 0} 表示不限制，负数视为非法配置）。
     *
     * @param name  字段名（用于错误信息）
     * @param value 字段值，可为 {@code null}（表示不修改）
     */
    private static void validateLimitNonNegative(final String name, final Long value) {
        if (value != null && value < 0L) {
            throw new IllegalArgumentException(name + " 不能为负（0 表示不限制）");
        }
    }

    public Mono<ApiKeyVO> updateApiKey(String kid, ApiKeyUpdateRequest req) {
        return updateApiKey(kid, req, null);
    }

    /** 更新密钥并校验归属当前租户；跨租户按「不存在」处理。 */
    public Mono<ApiKeyVO> updateApiKey(final String kid, final ApiKeyUpdateRequest req, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> {
            ApiKey ak = requireInScope(kid, scope);

            // 2026-10-24：白名单与权限分类一致性校验。
            // 只拦截「本次改动新引入的」不一致项 —— 存量数据里已有的不一致
            // （历史遗留）不阻塞其它字段的编辑，避免存量密钥变成不可编辑的死数据。
            final List<String> oldPermissions = ak.getPermissions();
            final List<String> oldGroups = ak.getAllowedGroups();
            final List<String> newGroups = req.getAllowedGroups() != null
                    ? req.getAllowedGroups() : oldGroups;
            final List<String> newPermissions;
            if (req.getPermissions() != null) {
                newPermissions = validatePermissions(req.getPermissions());
            } else if (req.getAllowedGroups() != null) {
                // 2026-11：前端已移除「可调用的模型分类」字段——本次改动了白名单时，
                // 分类权限按新白名单自动推导，保证密钥始终能调通其所配分组
                newPermissions = apiKeyWhitelistValidator.derivePermissions(newGroups);
            } else {
                newPermissions = oldPermissions;
            }
            validateWhitelistDelta(oldPermissions, oldGroups, newPermissions, newGroups);

            // #9 数据分级出境：管理员改密级——req.classification 为 null 表示不修改；
            // 传入值按 fromCode 归一化（非法值兜底 PUBLIC），写入 metadata 信任锚。
            // 密级是敏感治理字段，变更前后值记入审计日志（降级放宽尤其需要留痕）。
            if (req.getClassification() != null) {
                final String oldClassification = DataClassification.fromCode(
                        ak.getMetadata() == null ? null : String.valueOf(ak.getMetadata().get("classification"))).getCode();
                final String newClassification = DataClassification.fromCode(req.getClassification()).getCode();
                if (ak.getMetadata() == null) {
                    ak.setMetadata(new HashMap<>());
                }
                ak.getMetadata().put("classification", newClassification);
                if (!oldClassification.equals(newClassification)) {
                    eventPublisher.publishEvent(ApiKeyAuditEvent.securityEvent(
                            "CLASSIFICATION_CHANGED",
                            "API Key 密级变更: " + oldClassification + " -> " + newClassification,
                            kid, null));
                }
            }

            if (req.getDescription() != null) ak.setDescription(req.getDescription());
            if (req.getHolder() != null) ak.setHolder(req.getHolder().trim());
            if (req.getEnabled() != null) ak.setEnabled(req.getEnabled());
            if (req.getExpiresAt() != null) ak.setExpiresAt(req.getExpiresAt());
            if (req.getPermissions() != null) ak.setPermissions(newPermissions);
            if (req.getAllowedGroups() != null) ak.setAllowedGroups(newGroups);
            if (req.getAllowedIpAddresses() != null) ak.setAllowedIpAddresses(req.getAllowedIpAddresses());
            if (req.getDailyRequestLimit() != null) ak.setDailyRequestLimit(req.getDailyRequestLimit());
            if (req.getRotationPeriodDays() != null) ak.setRotationPeriodDays(req.getRotationPeriodDays());
            if (req.getDailyTokenLimit() != null) ak.setDailyTokenLimit(req.getDailyTokenLimit());
            validateLimitNonNegative("hourlyRequestLimit", req.getHourlyRequestLimit());
            if (req.getHourlyRequestLimit() != null) ak.setHourlyRequestLimit(req.getHourlyRequestLimit());
            validateLimitNonNegative("hourlyTokenLimit", req.getHourlyTokenLimit());
            if (req.getHourlyTokenLimit() != null) ak.setHourlyTokenLimit(req.getHourlyTokenLimit());
            validateLimitNonNegative("monthlyRequestLimit", req.getMonthlyRequestLimit());
            if (req.getMonthlyRequestLimit() != null) ak.setMonthlyRequestLimit(req.getMonthlyRequestLimit());
            validateLimitNonNegative("monthlyTokenLimit", req.getMonthlyTokenLimit());
            if (req.getMonthlyTokenLimit() != null) ak.setMonthlyTokenLimit(req.getMonthlyTokenLimit());
            if (req.getRateLimitPerMinute() != null) ak.setRateLimitPerMinute(req.getRateLimitPerMinute());
            if (req.getQuotaAlertThreshold() != null) {
                // issue #96：更新路径与创建路径统一区间
                QuotaLimits.validateAlertThreshold(req.getQuotaAlertThreshold());
                ak.setQuotaAlertThreshold(req.getQuotaAlertThreshold());
            }
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("更新API Key成功: {}", kid);
            return convertToVO(ak);
        });
    }

    public Mono<Void> deleteApiKey(String kid) { return deleteApiKey(kid, "system"); }

    public Mono<Void> deleteApiKey(String kid, String by) { return deleteApiKey(kid, by, null); }

    /** 删除密钥并校验归属当前租户；跨租户按「不存在」处理。 */
    public Mono<Void> deleteApiKey(final String kid, final String by, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromRunnable(() -> {
            ApiKey ak = requireInScope(kid, scope);
            String kh = ak.getKeyHash();
            apiKeyCache.remove(kh); keyIdIndex.remove(kid);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            eventPublisher.publishEvent(ApiKeyAuditEvent.revoked(kid, "手动删除", by));
            log.info("删除API Key成功: {}", kid);
        });
    }

    public Mono<ApiKeyListVO> getAllApiKeysVO() {
        // 兼容旧调用方；生产链路一律走带 header 的重载（见下），避免装配阶段读上下文的时序坑。
        return getAllApiKeysVOFromScope(null);
    }

    /**
     * 「全部密钥」列表（管理端入口）：controller 透传 {@code X-Tenant-Id}，订阅阶段算 scope。
     * 单租户 scope=null 不过滤，行为零变化。
     */
    public Mono<ApiKeyListVO> getAllApiKeysVO(final String tenantHeader) {
        return getAllApiKeysVOFromScope(tenantScope(tenantHeader));
    }

    private Mono<ApiKeyListVO> getAllApiKeysVOFromScope(final String scope) {
        return Mono.fromCallable(() -> {
            List<ApiKeyVO> items = apiKeyCache.values().stream()
                .filter(ak -> scope == null || scope.equals(TenantContextHolder.normalize(ak.getTenantId())))
                .map(this::convertToVO)
                .sorted(Comparator.comparing(ApiKeyVO::getCreatedAt).reversed()).toList();
            int en = 0, dis = 0, exp = 0; long ttr = 0, tsr = 0, tfr = 0;
            String td = LocalDateTime.now().toLocalDate().toString();
            for (ApiKey ak : apiKeyCache.values()) {
                if (scope != null && !scope.equals(TenantContextHolder.normalize(ak.getTenantId()))) {
                    continue;
                }
                if (ak.isEnabled()) en++; else dis++;
                if (ak.isExpired()) exp++;
                UsageStatistics us = ak.getUsage();
                if (us != null) {
                    Map<String, Long> du = us.getDailyUsage();
                    if (du != null && du.get(td) != null) ttr += du.get(td);
                    tsr += us.getSuccessfulRequests(); tfr += us.getFailedRequests();
                }
            }
            return ApiKeyListVO.builder().items(items).total(items.size())
                .enabledCount(en).disabledCount(dis).expiredCount(exp)
                .summary(ApiKeyListVO.Summary.builder().todayTotalRequests(ttr)
                    .todaySuccessfulRequests(tsr).todayFailedRequests(tfr).build()).build();
        });
    }

    /**
     * 「我的密钥」列表：仅返回指定归属用户的密钥（v3.5 Trunkline UI 改造，§4.2）。
     *
     * <p>过滤口径：<b>有效持有人</b>（{@link #effectiveHolder}）等于 owner。owner 由控制器从
     * {@code Authentication.getName()} 透传（JWT subject），**严禁**信任前端传入的用户名，
     * 避免越权查看他人密钥。</p>
     *
     * <p>与 {@link #getAllApiKeysVO()} 的差异仅在于按归属过滤；统计口径同源（各自密钥的
     * 当日请求量 + 累计成功/失败），返回结构复用 {@link ApiKeyListVO}，前端可复用既有
     * 列表渲染与统计卡逻辑。</p>
     *
     * @param owner 归属用户名（当前登录用户 subject）
     * @return 归属该用户的密钥列表（含汇总统计）
     */
    public Mono<ApiKeyListVO> getMineApiKeysVO(final String owner) {
        return getMineApiKeysVO(owner, null);
    }

    /** 「我的密钥」带租户作用域版本：多租户下只看当前租户下、且归属本人的密钥。 */
    public Mono<ApiKeyListVO> getMineApiKeysVO(final String owner, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> {
            List<ApiKeyVO> items = apiKeyCache.values().stream()
                    .filter(ak -> belongsTo(ak, owner) && inScope(ak, scope))
                    .map(this::convertToVO)
                    .sorted(Comparator.comparing(ApiKeyVO::getCreatedAt).reversed())
                    .toList();
            int en = 0, dis = 0, exp = 0; long ttr = 0, tsr = 0, tfr = 0;
            String td = LocalDateTime.now().toLocalDate().toString();
            for (ApiKey ak : apiKeyCache.values()) {
                if (!belongsTo(ak, owner) || !inScope(ak, scope)) {
                    continue;
                }
                if (ak.isEnabled()) en++; else dis++;
                if (ak.isExpired()) exp++;
                UsageStatistics us = ak.getUsage();
                if (us != null) {
                    Map<String, Long> du = us.getDailyUsage();
                    if (du != null && du.get(td) != null) ttr += du.get(td);
                    tsr += us.getSuccessfulRequests(); tfr += us.getFailedRequests();
                }
            }
            return ApiKeyListVO.builder().items(items).total(items.size())
                .enabledCount(en).disabledCount(dis).expiredCount(exp)
                .summary(ApiKeyListVO.Summary.builder().todayTotalRequests(ttr)
                    .todaySuccessfulRequests(tsr).todayFailedRequests(tfr).build()).build();
        });
    }

    /**
     * 「我的密钥」列表（含分组扩展信息，P0-L 越权泄漏收敛的补偿能力）。
     *
     * <p>与 {@link #getMineApiKeysVO(String)} 的差异：除密钥本体外，额外下发
     * <b>该用户可见分组</b>的展示信息（分组名 / 友好名 / 服务类型 / 加权均价）——
     * 见 {@link ApiKeyWithModelScopeVO#getModelScope()} 与
     * {@link ApiKeyVO#getModelScope()}。</p>
     *
     * <p><b>为什么需要它</b>：此前员工侧为显示友好名与均价，必须调用
     * {@code GET /api/config/pools/list} 拉取全公司资源池（含成员 instanceId / weight）。
     * 收紧 {@code config:pools:read} 权限后该接口对员工关闭，改由服务端按<b>白名单</b>
     * 解析后随密钥一起下发，既保住员工侧功能，又不再跨员工泄漏资源池。</p>
     *
     * <p>归属过滤口径与 {@link #getMineApiKeysVO(String)} 完全一致（有效持有人 = owner）。</p>
     *
     * @param owner 归属用户名（当前登录用户 subject）
     * @return 归属该用户的密钥列表（含汇总统计与分组扩展信息）
     */
    public Mono<ApiKeyWithModelScopeVO> getMineApiKeysWithModelScope(final String owner) {
        return getMineApiKeysWithModelScope(owner, null);
    }

    /** 「我的密钥」(含分组信息) 带租户作用域版本：多租户下只看当前租户下、且归属本人的密钥。 */
    public Mono<ApiKeyWithModelScopeVO> getMineApiKeysWithModelScope(final String owner, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> {
            final List<ApiKeyVO> items = apiKeyCache.values().stream()
                    .filter(ak -> belongsTo(ak, owner) && inScope(ak, scope))
                    .map(this::convertToVO)
                    .sorted(Comparator.comparing(ApiKeyVO::getCreatedAt).reversed())
                    .toList();
            int en = 0, dis = 0, exp = 0;
            long ttr = 0, tsr = 0, tfr = 0;
            final String td = LocalDateTime.now().toLocalDate().toString();
            for (final ApiKey ak : apiKeyCache.values()) {
                if (!belongsTo(ak, owner) || !inScope(ak, scope)) {
                    continue;
                }
                if (ak.isEnabled()) {
                    en++;
                } else {
                    dis++;
                }
                if (ak.isExpired()) {
                    exp++;
                }
                final UsageStatistics us = ak.getUsage();
                if (us != null) {
                    final Map<String, Long> du = us.getDailyUsage();
                    if (du != null && du.get(td) != null) {
                        ttr += du.get(td);
                    }
                    tsr += us.getSuccessfulRequests();
                    tfr += us.getFailedRequests();
                }
            }
            final ApiKeyWithModelScopeVO vo = ApiKeyWithModelScopeVO.builder()
                    .items(items)
                    .total(items.size())
                    .enabledCount(en)
                    .disabledCount(dis)
                    .expiredCount(exp)
                    .summary(ApiKeyListVO.Summary.builder()
                            .todayTotalRequests(ttr)
                            .todaySuccessfulRequests(tsr)
                            .todayFailedRequests(tfr)
                            .build())
                    .build();
            attachModelScope(vo);
            return vo;
        });
    }

    /**
     * 员工取回自己的密钥明文（「我的密钥」页复制按钮的数据源，2026-11 用户决策）。
     *
     * <p>安全约束：归属校验在服务端完成（{@code holder/createdBy} 必须等于当前登录名，
     * 与 {@link #getMineApiKeysWithModelScope} 同一口径），不信任前端传参；
     * 明文从 {@code ApiKey.keyEncrypted}（AES-256-GCM）解密得到，
     * 存量未加密密钥（2026-11-05 之前创建）明文不可恢复，明确报错引导轮换。</p>
     *
     * @param owner 当前登录用户名（从 Authentication 解析，非前端传参）
     * @param keyId 目标密钥 ID
     * @return 密钥明文
     * @throws IllegalArgumentException 密钥不存在，或不属于该用户
     * @throws IllegalStateException    明文不可恢复（存量未加密密钥）
     */
    public String revealMineApiKeyValue(final String owner, final String keyId) {
        return revealMineApiKeyValue(owner, keyId, null);
    }

    /** 带租户作用域的明文取回：多租户下还须属于当前租户。 */
    public String revealMineApiKeyValue(final String owner, final String keyId, final String tenantHeader) {
        final String keyHash = keyIdIndex.get(keyId);
        final ApiKey ak = keyHash != null ? apiKeyCache.get(keyHash) : null;
        if (ak == null) {
            throw new IllegalArgumentException("API Key不存在: " + keyId);
        }
        if (!belongsTo(ak, owner)) {
            throw new IllegalArgumentException("该密钥不属于当前用户");
        }
        if (!inScope(ak, tenantScope(tenantHeader))) {
            throw new IllegalArgumentException("API Key不存在: " + keyId);
        }
        final String encrypted = ak.getKeyEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("该密钥创建于旧版本，未存储可解密的明文，请先轮换密钥后再使用复制功能");
        }
        return plaintextCipher.decrypt(encrypted);
    }

    /**
     * 给密钥列表补齐分组扩展信息（按密钥 + 按用户两个维度）。
     *
     * <p>解析器缺失（极简上下文）时降级为空列表，接口不失败。</p>
     *
     * @param vo 待补齐的 VO
     */
    private void attachModelScope(final ApiKeyWithModelScopeVO vo) {
        final List<ApiKeyVO> items = vo.getItems();
        if (items == null || items.isEmpty()) {
            vo.setModelScope(new ArrayList<>());
            return;
        }
        if (apiKeyModelScopeResolver == null) {
            vo.setModelScope(new ArrayList<>());
            return;
        }
        final List<List<String>> whitelistOfItems = new ArrayList<>(items.size());
        for (final ApiKeyVO item : items) {
            whitelistOfItems.add(item.getAllowedGroups() == null ? List.of() : item.getAllowedGroups());
        }
        // 应修8：用带密钥级均价的批量解析，一次性拿到分组扩展信息 + 密钥级合计均价。
        // 索引只在内部构建一次，避免逐密钥重复扫表（应修10 同样收益）。
        final List<ApiKeyModelScopeResolver.ResolvedKeyScope> resolved =
                apiKeyModelScopeResolver.resolveAllWithPrices(whitelistOfItems);
        final Map<String, ApiKeyModelScopeVO> union = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            final ApiKeyModelScopeResolver.ResolvedKeyScope entry = i < resolved.size() ? resolved.get(i) : null;
            final List<ApiKeyModelScopeVO> scope = entry == null ? List.of() : entry.scope();
            items.get(i).setModelScope(scope);
            // 应修8：服务端下发密钥级合计均价，前端只显示不计算。
            items.get(i).setMixedPrice(entry == null ? null : entry.keyLevelMixedPrice());
            for (final ApiKeyModelScopeVO scopeEntry : scope) {
                if (scopeEntry.getPoolName() != null) {
                    union.putIfAbsent(scopeEntry.getPoolName(), scopeEntry);
                }
            }
        }
        vo.setModelScope(new ArrayList<>(union.values()));
    }

    /**
     * 密钥是否归属指定用户。
     *
     * <p>口径：<b>有效持有人</b>（{@link #effectiveHolder}）等于 owner —— 与「全部密钥」页
     * 「持有人」列（{@code holder || createdBy}）、以及调用观测「员工」列完全一致。</p>
     *
     * <p><b>为什么不能是「或」语义</b>：{@code createdBy} 是<b>操作者</b>（谁在控制台点了
     * 创建），{@code holder} 是<b>使用者</b>（密钥发给谁用）。管理员给别人发密钥时
     * {@code createdBy=admin, holder=员工}；若用「{@code createdBy == owner ||
     * holder == owner}」，管理员的「我的密钥」里会多出发给别人的密钥，而「全部密钥」页的
     * 「持有人」列明明显示的是别人 —— 两页口径分叉，用户看到的同一把密钥归属对不上。</p>
     *
     * <p>存量密钥没有 {@code holder} 字段，{@code ApiKeyPersistenceService} 加载时已把它
     * 兜底为 {@code createdBy}，此处再兜一次双保险：行为与改造前完全一致。</p>
     *
     * @param ak    密钥
     * @param owner 用户名
     * @return 属于该用户返回 true
     */
    private static boolean belongsTo(final ApiKey ak, final String owner) {
        if (owner == null || owner.isBlank() || ak == null) {
            return false;
        }
        return owner.equals(effectiveHolder(ak));
    }

    /**
     * 密钥的有效持有人：{@code holder}（发给谁用）优先，为空时回落 {@code createdBy}（谁创建的）。
     *
     * <p>这是全站唯一的「密钥归属」口径来源：「全部密钥」页「持有人」列、「我的密钥」页过滤、
     * 调用观测「员工」列三处共用，避免同一把密钥在不同页面归属不一致。</p>
     *
     * @param ak 密钥
     * @return 有效持有人用户名；两者皆空时返回 {@code null}
     */
    private static String effectiveHolder(final ApiKey ak) {
        if (ak == null) {
            return null;
        }
        final String holder = ak.getHolder();
        if (holder != null && !holder.isBlank()) {
            return holder;
        }
        return ak.getCreatedBy();
    }

    /**
     * 按 API Key ID 查<b>密钥持有人</b>（有效持有人），供调用观测的「员工」列归属。
     *
     * <p>v3.5 Trunkline：「调用观测」要回答「谁在什么时候调了什么」，需要把 API Key 调用
     * 归到具体员工。持有人的口径与「全部密钥」页的「持有人」列、「我的密钥」页过滤完全一致
     * （统一走 {@link #effectiveHolder}），此处只读不写、不改语义。</p>
     *
     * <p>读内存缓存（{@code keyIdIndex} → {@code apiKeyCache}），无数据库开销；
     * 查不到（密钥已删除 / 缓存未同步 / 系统密钥）时返回 {@code null}，
     * 调用方据此把该行归入「未知」，不丢数据。</p>
     *
     * @param keyId API Key ID
     * @return 持有人用户名；未知时为 {@code null}
     */
    public String findHolderByKeyId(final String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return null;
        }
        try {
            final String kh = keyIdIndex.get(keyId);
            if (kh == null) {
                return null;
            }
            final ApiKey ak = apiKeyCache.get(kh);
            if (ak == null) {
                return null;
            }
            final String holder = effectiveHolder(ak);
            return holder == null || holder.isBlank() ? null : holder;
        } catch (Exception e) {
            // 查不到持有人不影响调用记录本身，静默返回未知
            return null;
        }
    }

    public Mono<ApiKeyVO> getApiKeyByIdVO(String kid) {
        return getApiKeyByIdVO(kid, null);
    }

    /** 按 ID 查密钥并校验归属当前租户；跨租户按「不存在」处理。 */
    public Mono<ApiKeyVO> getApiKeyByIdVO(final String kid, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> convertToVO(requireInScope(kid, scope)));
    }

    public Mono<ApiKeyVO> enableApiKey(String kid) { return enableApiKey(kid, null); }

    /** 启用密钥并校验归属当前租户；跨租户按「不存在」处理。 */
    public Mono<ApiKeyVO> enableApiKey(final String kid, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> {
            ApiKey ak = requireInScope(kid, scope);
            ak.setEnabled(true);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("启用API Key成功: {}", kid);
            return convertToVO(ak);
        });
    }

    public Mono<ApiKeyVO> disableApiKey(String kid) { return disableApiKey(kid, null); }

    /** 禁用密钥并校验归属当前租户；跨租户按「不存在」处理。 */
    public Mono<ApiKeyVO> disableApiKey(final String kid, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> {
            ApiKey ak = requireInScope(kid, scope);
            ak.setEnabled(false);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("禁用API Key成功: {}", kid);
            return convertToVO(ak);
        });
    }

    public Mono<ApiKeyBatchExportVO> exportApiKeys() { return exportApiKeys(null); }

    /** 导出按当前租户过滤（多租户下只导出本租户密钥）。 */
    public Mono<ApiKeyBatchExportVO> exportApiKeys(final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        if (scope == null) return apiKeyBatchService.exportApiKeys(apiKeyCache);
        final Map<String, ApiKey> scoped = new java.util.LinkedHashMap<>();
        apiKeyCache.forEach((hash, ak) -> { if (inScope(ak, scope)) scoped.put(hash, ak); });
        return apiKeyBatchService.exportApiKeys(scoped);
    }

    public Mono<ApiKeyBatchImportResult> importApiKeys(ApiKeyBatchImportRequest req, String by, String ip) {
        return importApiKeys(req, by, ip, null);
    }

    /** 导入绑定当前租户；REPLACE 模式只清当前租户的密钥，绝不跨租户清空。 */
    public Mono<ApiKeyBatchImportResult> importApiKeys(final ApiKeyBatchImportRequest req, final String by,
                                                       final String ip, final String tenantHeader) {
        return apiKeyBatchService.importApiKeys(req, apiKeyCache, keyIdIndex, by, ip, tenantScope(tenantHeader));
    }

    public Mono<Integer> rotateExpiredKeys() { return apiKeyBatchService.rotateExpiredKeys(apiKeyCache, keyIdIndex); }

    public Mono<ApiKeyCreationVO> forceRotateKey(String kid, String by) { return forceRotateKey(kid, by, null); }

    /** 强制轮换并校验归属当前租户；跨租户按「不存在」处理。 */
    public Mono<ApiKeyCreationVO> forceRotateKey(final String kid, final String by, final String tenantHeader) {
        final String scope = tenantScope(tenantHeader);
        return Mono.fromCallable(() -> { requireInScope(kid, scope); return true; })
                .then(apiKeyBatchService.forceRotateKey(kid, apiKeyCache, keyIdIndex, by));
    }

    public Mono<Integer> cleanupExpiredKeys() { return apiKeyBatchService.cleanupExpiredKeys(apiKeyCache); }

    public Mono<ApiKeyRotationStats> getRotationStats() {
        return apiKeyBatchService.getRotationStats(apiKeyCache);
    }

    public Mono<ApiKeyExpirationStats> getExpirationStats() {
        return apiKeyBatchService.getExpirationStats(apiKeyCache);
    }

    public boolean hasPersistedAccountConfig() { return apiKeyPersistenceService.hasPersistedAccountConfig(); }

    public void initializeApiKeyFromYaml() {
        apiKeyPersistenceService.initializeApiKeyFromYaml(apiKeyCache, keyIdIndex, loadApiKeysFromConfig());
        if (apiKeyLookupIndex != null) {
            apiKeyLookupIndex.rebuild(apiKeyCache);
        }
    }

    public void loadLatestApiKeyConfig() {
        apiKeyPersistenceService.loadLatestApiKeyConfig(apiKeyCache, keyIdIndex, loadApiKeysFromConfig());
        if (apiKeyLookupIndex != null) {
            apiKeyLookupIndex.rebuild(apiKeyCache);
        }
    }

    /**
     * 加载持久化配置并与 YAML 配置合并（YAML 优先）
     * v2.x 修复：解决 YAML 中定义的 API Key 无法生效的问题
     */
    public void loadAndMergeWithYaml() {
        apiKeyPersistenceService.loadAndMergeWithYaml(apiKeyCache, keyIdIndex, loadApiKeysFromConfig());
        if (apiKeyLookupIndex != null) {
            apiKeyLookupIndex.rebuild(apiKeyCache);
        }
    }

    /**
     * 定期从共享存储刷新本副本的 API Key 缓存（跨副本最终一致，#162）。
     *
     * <p>缓存是本副本的内存镜像，只在本进程内更新：多副本下在副本 A 吊销或新建的 Key，
     * 副本 B 原先要等到重启才能感知——其间已吊销的 Key 会继续被放行。本方法让各副本在
     * {@code trunkline.security.api-key.cache-refresh-interval-seconds} 内收敛到共享存储的
     * 最新内容（含移除已删除项）。</p>
     *
     * <p><b>每个副本都必须执行</b>，因此刻意不加跨副本排他锁：加锁会让只有一个副本刷新
     * 自己的缓存，其余副本依旧停留在过期视图。</p>
     */
    @Scheduled(fixedDelayString = "${trunkline.security.api-key.cache-refresh-interval-seconds:60}",
            timeUnit = TimeUnit.SECONDS)
    public void refreshCacheFromSharedStore() {
        if (!cacheRefreshEnabled) {
            return;
        }
        int refreshed = apiKeyPersistenceService.refreshApiKeyCache(apiKeyCache, keyIdIndex);
        if (refreshed >= 0) {
            if (apiKeyLookupIndex != null) {
                apiKeyLookupIndex.rebuild(apiKeyCache);
            }
            log.debug("本副本 API Key 缓存已刷新，当前 {} 个密钥", refreshed);
        }
    }

    /**
     * 更新 API Key 请求使用统计
     *
     * <p><b>线程语义（issue #262）</b>：{@code saveApiKeysToStore} 是整仓序列化 + 存储写入的
     * 阻塞调用，而本方法经 {@link #validateApiKey(String, String, String)} 由 WebFilter 认证链
     * 调用、运行在 Reactor 非阻塞线程（Netty 事件循环）上时，把工作整体交给
     * {@link reactor.core.scheduler.Schedulers#boundedElastic()} 执行后立即返回；
     * 非 Reactor 线程仍内联执行，保持“方法返回即已写入”的既有语义。</p>
     *
     * @param kid  API Key ID
     * @param succ 本次请求是否成功
     */
    public void updateUsageStatistics(String kid, boolean succ) {
        // 阻塞存储写入不得阻塞 EventLoop（issue #262）
        if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> doUpdateUsageStatistics(kid, succ));
            return;
        }
        doUpdateUsageStatistics(kid, succ);
    }

    /**
     * 更新 API Key 请求使用统计的实际实现（阻塞：全量写存储）.
     *
     * <p>必须运行在可阻塞线程上；由 {@link #updateUsageStatistics(String, boolean)} 负责选择执行线程。</p>
     */
    private void doUpdateUsageStatistics(String kid, boolean succ) {
        String kh = keyIdIndex.get(kid);
        if (kh == null) { log.warn("API Key不存在: {}", kid); return; }
        ApiKey ak = apiKeyCache.get(kh);
        if (ak == null) { log.warn("API Key不存在: {}", kid); return; }
        // #811/#715：per-Key 锁内「改内存 → 冻结快照 → 单 Key 原子回写」。
        // 回写必须也在锁内——否则同 Key 并发更新时，回写顺序可能与冻结顺序颠倒，
        // 陈旧快照会覆盖新快照。锁序恒为 ak → storeManager，无反向获取，无死锁环。
        synchronized (ak) {
            if (ak.getUsage() == null) {
                ak.setUsage(UsageStatistics.builder()
                        .dailyUsage(new HashMap<>())
                        .dailyTokenUsage(new HashMap<>())
                        .hourlyUsage(new HashMap<>())
                        .hourlyTokenUsage(new HashMap<>())
                        .monthlyUsage(new HashMap<>())
                        .monthlyTokenUsage(new HashMap<>())
                        .totalRequests(0L).successfulRequests(0L).failedRequests(0L)
                        .build());
            }
            ak.getUsage().incrementRequest(succ);
            final String frozenJson = freezeUsageSnapshot(ak);
            if (frozenJson != null) {
                apiKeyPersistenceService.saveSingleApiKeyToStore(kh, frozenJson);
            }
        }
        log.debug("更新API Key使用统计: {} (成功: {})", kid, succ);
    }

    /**
     * #811/#715：在 per-Key 锁内把 ApiKey 序列化为不可变 JSON 快照。
     *
     * <p>失败时返回 {@code null}（调用方跳过本次回写，下次用量更新会带上累计值补写）——
     * 用量统计丢失一次增量远好于撕裂 JSON 或回写中间态。</p>
     *
     * @param ak 已在锁内的 ApiKey
     * @return 冻结 JSON；序列化失败返回 {@code null}
     */
    private String freezeUsageSnapshot(ApiKey ak) {
        try {
            return objectMapper.writeValueAsString(ak);
        } catch (Exception e) {
            log.warn("冻结用量快照失败，跳过本次回写: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 更新 API Key Token 使用量
     *
     * <p><b>线程语义（issue #115）</b>：{@code saveApiKeysToStore} 是整仓序列化 + 文件/Redis 写入的
     * 阻塞调用，而调用方位于流式 {@code doOnComplete} 等 Reactor 非阻塞线程（Netty 事件循环）上时，
     * 把工作整体交给 {@link reactor.core.scheduler.Schedulers#boundedElastic()} 执行后立即返回；
     * 非 Reactor 线程仍内联执行，保持“方法返回即已写入”的既有语义。</p>
     *
     * @param kid   API Key ID
     * @param tokens 消耗的 Token 数
     */
    public void updateTokenUsage(String kid, long tokens) {
        if (tokens <= 0) {
            return;
        }
        // 阻塞存储写入不得阻塞 EventLoop（issue #115）
        if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> doUpdateTokenUsage(kid, tokens));
            return;
        }
        doUpdateTokenUsage(kid, tokens);
    }

    /**
     * 更新 API Key Token 使用量的实际实现（阻塞：全量写存储）.
     *
     * <p>必须运行在可阻塞线程上；由 {@link #updateTokenUsage(String, long)} 负责选择执行线程。</p>
     */
    private void doUpdateTokenUsage(String kid, long tokens) {
        String kh = keyIdIndex.get(kid);
        if (kh == null) {
            log.warn("API Key不存在: {}", kid);
            return;
        }
        ApiKey ak = apiKeyCache.get(kh);
        if (ak == null || ak.getUsage() == null) {
            log.warn("API Key或usage不存在: {}", kid);
            return;
        }
        // #811/#715：per-Key 锁内「改内存 → 冻结快照 → 单 Key 原子回写」（同 doUpdateUsageStatistics）。
        synchronized (ak) {
            ak.getUsage().incrementTokens(tokens);
            final String frozenJson = freezeUsageSnapshot(ak);
            if (frozenJson != null) {
                apiKeyPersistenceService.saveSingleApiKeyToStore(kh, frozenJson);
            }
        }
        log.debug("更新API Key Token使用量: {} (tokens: {})", kid, tokens);
    }

    /**
     * 重置指定 API Key 的每日配额计数器
     *
     * @param kid API Key ID
     */
    public Mono<Void> resetDailyQuota(String kid) {
        return Mono.fromRunnable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new IllegalArgumentException("API Key不存在: " + kid);
            ApiKey ak = apiKeyCache.get(kh);
            if (ak == null || ak.getUsage() == null) {
                throw new IllegalArgumentException("API Key或usage不存在: " + kid);
            }
            ak.getUsage().resetDaily();
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("重置API Key每日配额: {}", kid);
        });
    }

    /**
     * 旧版掩码入口（兼容存量密钥内存中偶存的明文）：统一走 {@link ApiKey#maskKey(String)}。
     *
     * <p>密钥值不可得（老数据 / 从 YAML 迁移来的密钥未保留明文）时返回 {@code null}，
     * 前端显示「—」，不臆造。</p>
     */
    private static String maskKeyValue(final String keyValue) {
        return ApiKey.maskKey(keyValue);
    }

    private ApiKeyVO convertToVO(ApiKey ak) {
        // #9 数据分级出境：从服务端 metadata 读取密级；存量未打标 Key 展示为 PUBLIC
        // （与 resolveClassification 的运行时兜底一致），不因查询而改写存储。
        final String classification = DataClassification.fromCode(
                ak.getMetadata() == null ? null : String.valueOf(ak.getMetadata().get("classification"))).getCode();
        ApiKeyVO vo = ApiKeyVO.builder().keyId(ak.getKeyId()).description(ak.getDescription())
            .classification(classification)
            .tenantId(TenantContextHolder.normalize(ak.getTenantId()))
            // 优先用创建/轮换/导入时持久化的脱敏串；存量无明文密钥回落（仍为 null → 前端显示「—」）
            .keyMasked(ak.getKeyMasked() != null ? ak.getKeyMasked() : maskKeyValue(ak.getKeyValue()))
            .permissions(normalizeStoredPermissions(ak.getPermissions()))
            .allowedGroups(ak.getAllowedGroups())
            .allowedIpAddresses(ak.getAllowedIpAddresses())
            .enabled(ak.isEnabled()).expired(ak.isExpired())
            .createdAt(ak.getCreatedAt()).createdBy(ak.getCreatedBy()).holder(ak.getHolder())
            .creatorIpAddress(ak.getCreatorIpAddress())
            .rotationPeriodDays(ak.getRotationPeriodDays()).lastRotatedAt(ak.getLastRotatedAt())
            .needsRotation(ak.needsRotation()).expiresAt(ak.getExpiresAt())
            .dailyRequestLimit(ak.getDailyRequestLimit())
            .dailyTokenLimit(ak.getDailyTokenLimit())
            .hourlyRequestLimit(ak.getHourlyRequestLimit())
            .hourlyTokenLimit(ak.getHourlyTokenLimit())
            .monthlyRequestLimit(ak.getMonthlyRequestLimit())
            .monthlyTokenLimit(ak.getMonthlyTokenLimit())
            .rateLimitPerMinute(ak.getRateLimitPerMinute())
            .quotaAlertThreshold(ak.getQuotaAlertThreshold())
            .todayRequestCount(ak.getTodayRequestCount())
            .todayTokenUsage(ak.getTodayTokenUsage())
            .quotaAlertTriggered(ak.isQuotaAlertTriggered())
            .build();
        if (ak.getUsage() != null) {
            vo.setTotalRequests(ak.getUsage().getTotalRequests());
            vo.setSuccessfulRequests(ak.getUsage().getSuccessfulRequests());
            vo.setFailedRequests(ak.getUsage().getFailedRequests());
            vo.setLastUsedAt(ak.getUsage().getLastUsedAt());
        }
        vo.calculateRemainingDays();
        return vo;
    }

    /**
     * 更新路径的白名单一致性校验（差异比对版）。
     *
     * <p><b>为什么不用绝对校验：</b>存量密钥里可能已经存在「权限与白名单分类不一致」
     * 的历史数据（本次校验上线前允许这么配）。若用绝对校验，这些密钥将完全无法编辑
     * （改个描述都会被拒），变成死数据。因此这里只拦截<b>本次改动新引入的</b>
     * 不一致项：</p>
     * <ul>
     *   <li>新加的白名单项，分类不被新权限覆盖 → 拒绝</li>
     *   <li>收窄权限导致原本一致的白名单项变得不一致 → 拒绝</li>
     *   <li>本来就存在的不一致项（本次未触碰）→ 放行，允许用户逐步清理</li>
     * </ul>
     *
     * @param oldPermissions 变更前权限
     * @param oldModels      变更前模型白名单
     * @param oldGroups      变更前分组白名单
     * @param newPermissions 变更后权限
     * @param newModels      变更后模型白名单
     * @param newGroups      变更后分组白名单
     */
    private void validateWhitelistDelta(final List<String> oldPermissions,
                                        final List<String> oldGroups,
                                        final List<String> newPermissions,
                                        final List<String> newGroups) {
        final Map<String, Set<String>> oldInvalid =
                apiKeyWhitelistValidator.findInvalidByCategory(oldPermissions, oldGroups);
        final Map<String, Set<String>> newInvalid =
                apiKeyWhitelistValidator.findInvalidByCategory(newPermissions, newGroups);
        if (newInvalid.isEmpty()) {
            return;
        }
        // 剔除「变更前就已不一致」的名字 → 只保留本次新引入的
        final Map<String, Set<String>> introduced = new LinkedHashMap<>();
        newInvalid.forEach((category, items) -> {
            final Set<String> previouslyBad = oldInvalid.get(category);
            final Set<String> fresh = new TreeSet<>();
            for (final String item : items) {
                if (previouslyBad == null || !previouslyBad.contains(item)) {
                    fresh.add(item);
                }
            }
            if (!fresh.isEmpty()) {
                introduced.put(category, fresh);
            }
        });
        if (introduced.isEmpty()) {
            return;
        }
        throw new ApiKeyWhitelistMismatchException(
                apiKeyWhitelistValidator.buildMessage(introduced, newPermissions));
    }

    /**
     * 归一化已落库的权限列表（兼容历史小写值）。
     *
     * <p>历史版本 {@code validatePermissions} 用 {@code toLowerCase()} 归一化，
     * 会把 {@code imgGen}/{@code imgEdit} 存成 {@code imggen}/{@code imgedit}，
     * 导致前端 i18n 键匹配不到、复选框回显丢失。此处统一还原为枚举规范值。</p>
     *
     * @param permissions 已落库的权限列表
     * @return 规范值权限列表（无法识别的值原样保留）
     */
    private List<String> normalizeStoredPermissions(final List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return permissions;
        }
        final List<String> normalized = new ArrayList<>(permissions.size());
        for (final String permission : permissions) {
            final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(permission);
            normalized.add(canonical != null ? canonical : permission);
        }
        return normalized;
    }

    private List<ApiKey> loadApiKeysFromConfig() {
        return securityProperties.getApiKey().getKeys().stream()
            .peek(item -> apiKeyPersistenceService.initializeApiKeyFields(item)).toList();
    }

    public Map<String, ApiKey> getApiKeyCache() { return apiKeyCache; }
    public Map<String, String> getKeyIdIndex() { return keyIdIndex; }

    /**
     * 判断指定 {@code keyId} 是否归属该用户（自助端点共用的属主校验）。
     *
     * <p>归属口径与「我的密钥」列表完全一致（{@link #belongsTo} → 有效持有人
     * {@code holder || createdBy}），不信任调用方传入的用户名以外的任何身份参数。
     * 供「我的密钥」「本月已用」等只读自助端点在放行数之前校验，避免员工借
     * 通用监控端点查到别人密钥的用量。</p>
     *
     * @param owner  当前登录用户名（应取自 {@code Authentication#getName()}）
     * @param keyId  待查询的 API Key ID
     * @return 属于该用户返回 true；keyId 未知 / 空白 / owner 为空一律 false
     */
    public boolean isOwnedBy(final String owner, final String keyId) {
        if (owner == null || owner.isBlank() || keyId == null || keyId.isBlank()) {
            return false;
        }
        final String keyHash = keyIdIndex.get(keyId);
        final ApiKey ak = keyHash != null ? apiKeyCache.get(keyHash) : null;
        return belongsTo(ak, owner);
    }

    /**
     * 「我的可见租户」数据来源（P2）：当前用户持有的密钥所覆盖的租户 ID 集合。
     *
     * <p>归属口径与 {@link #isOwnedBy} / 「我的密钥」列表完全一致（有效持有人
     * {@code holder || createdBy}，见 {@link #belongsTo}）——管理员代员工建密钥时算员工、
     * 不算管理员。租户 ID 经 {@link TenantContextHolder#normalize(String)} 归一化，
     * 存量无 tenantId 的密钥回落 {@code default}；按 ID 自然排序，便于前端稳定渲染。</p>
     *
     * <p>不读租户上下文、不看当前 {@code X-Tenant-Id}——本方法回答"这个人的密钥分布在
     * 哪些租户"，是跨租户的账号级查询（管理面），不能套用业务行的行级过滤器。</p>
     *
     * @param owner 当前登录用户名（取自 {@code Authentication#getName()}）
     * @return 归一化、去重、排序后的租户 ID 集合；owner 空白 / 无密钥时返回空集合
     */
    public Set<String> visibleTenantIdsFor(final String owner) {
        if (owner == null || owner.isBlank()) {
            return Set.of();
        }
        final Set<String> ids = new TreeSet<>();
        for (final ApiKey ak : apiKeyCache.values()) {
            if (belongsTo(ak, owner)) {
                ids.add(TenantContextHolder.normalize(ak.getTenantId()));
            }
        }
        return java.util.Collections.unmodifiableSet(ids);
    }

    /**
     * 验证权限列表是否有效.
     *
     * <p>权限值必须是 ServiceTypeConstants 中定义的服务类型（如 chat, embedding 等）。
     *
     * @param permissions 权限列表
     * @return 验证后的权限列表（已过滤无效值）
     */
    public List<String> validatePermissions(final List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return permissions;
        }

        List<String> validPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (permission == null || permission.trim().isEmpty()) {
                continue;
            }

            String trimmedPermission = permission.trim();

            // 检查是否是有效的服务类型
            // 归一化到枚举规范值（chat/embedding/.../imgGen/imgEdit），
            // 不能用 toLowerCase：imgGen/imgEdit 转小写后得到 imggen/imgedit，
            // 与 ServiceTypeConstants 枚举 value 及前端 i18n 键都不一致，
            // 会导致密钥列表把权限显示成原始值，且前端复选框回显匹配不到而被静默丢弃。
            final String normalized = ServiceTypeConstants.ServiceType.normalizeValue(trimmedPermission);
            if (normalized != null) {
                validPermissions.add(normalized);
            } else if (isLegacyPermission(trimmedPermission.toLowerCase())) {
                // 处理旧权限格式，转换为全部服务类型权限
                log.warn("检测到旧权限格式 '{}'，将自动转换为全部服务类型权限。"
                    + "请更新 API Key 权限配置为服务类型（chat, embedding, rerank, tts, stt, imgGen, imgEdit）",
                    permission);
                return convertLegacyPermissionsToAllServiceTypes();
            } else {
                log.warn("忽略无效的权限值: {}", permission);
            }
        }

        return validPermissions;
    }

    /**
     * 检查是否是旧权限格式（READ, WRITE, DELETE, ADMIN）.
     *
     * @param permission 权限值
     * @return 是否是旧权限格式
     */
    private boolean isLegacyPermission(final String permission) {
        return "read".equals(permission)
            || "write".equals(permission)
            || "delete".equals(permission)
            || "admin".equals(permission);
    }

    /**
     * 将旧权限格式转换为全部服务类型权限.
     *
     * @return 全部服务类型权限列表
     */
    private List<String> convertLegacyPermissionsToAllServiceTypes() {
        return new ArrayList<>(ServiceTypeConstants.getServiceTypeList());
    }
}
