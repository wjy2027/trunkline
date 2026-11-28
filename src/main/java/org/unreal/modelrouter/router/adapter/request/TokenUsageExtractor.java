package org.unreal.modelrouter.router.adapter.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.quota.QuotaReservation;
import org.unreal.modelrouter.auth.security.quota.QuotaTokenEstimator;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.dto.EmbeddingDTO;
import org.unreal.modelrouter.monitor.service.TokenUsageRecorder;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.adapter.CallerIdentityResolver;
import org.unreal.modelrouter.router.pool.PoolSelector;

import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Token 使用量提取与记录辅助类
 *
 * 从 NonStreamingRequestProcessor 提取的 token 使用量相关方法，包括：
 * - 从响应中提取 token 使用量并记录
 * - 更新 API Key 的 token 使用量配额
 * - 从请求中提取 API Key ID
 * - 提取原始请求的 model 字段
 * - 池路由后改写 model 字段
 */
@Component
public class TokenUsageExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageExtractor.class);

    private final ObjectMapper objectMapper;

    private final TokenUsageRecorder tokenUsageRecorder;

    private final ApiKeyService apiKeyService;

    private final PoolSelector poolSelector;

    private final MetricsCollector metricsCollector;

    /**
     * v3.5 Trunkline：调用者身份解析器（JWT 用户名 / 密钥持有人）。
     *
     * <p>用量落库必须带上 userId——{@code aggregateByMonthUserVirtualModel} 的 WHERE 含
     * {@code userId IS NOT NULL}，缺失会导致「团队用量」恒为 0。</p>
     */
    private final CallerIdentityResolver callerIdentityResolver;

    public TokenUsageExtractor(
            final ObjectMapper objectMapper,
            final TokenUsageRecorder tokenUsageRecorder,
            final ApiKeyService apiKeyService,
            final PoolSelector poolSelector,
            final MetricsCollector metricsCollector,
            final CallerIdentityResolver callerIdentityResolver) {
        this.objectMapper = objectMapper;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.apiKeyService = apiKeyService;
        this.poolSelector = poolSelector;
        this.metricsCollector = metricsCollector;
        this.callerIdentityResolver = callerIdentityResolver;
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录（不带配额结算，保留既有签名）。
     *
     * @param bodyStr      下游响应体
     * @param adapterType  适配器类型
     * @param instanceName 实例名
     * @param apiKeyId     API Key ID
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId) {
        extractAndRecordTokenUsage(bodyStr, adapterType, instanceName, apiKeyId, null);
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录，同时完成配额结算（v3.1 PR-2）。
     *
     * <p>配额结算发生在“实际 token 落库点”：按 {@code 实际 − 估算} 冲正预留
     * （{@link QuotaReservation#settleSuccess(QuotaReservation, long)}）。</p>
     *
     * <p>issue #712：响应不含 usage 或 total≤0 时，改按响应文本<b>估算</b>
     * completion 后照常落库+结算（{@code metadata.estimated=true} 标记）——
     * 修复前提前返回导致非流式与流式两链路口径不一致（流式已估算落库）。
     * 定案口径：估算仅配额兜底，不作对外计费依据；响应完全无文本信号时
     * 才退回「预留即计费」（预留保持原估算值，不会被错误冲正为 0）。</p>
     *
     * @param bodyStr        下游响应体
     * @param adapterType    适配器类型
     * @param instanceName   实例名
     * @param apiKeyId       API Key ID
     * @param quotaReservation 配额预留凭据，{@code null}（未预扣 / 账本未启用）时空操作
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId,
                                              final QuotaReservation quotaReservation) {
        extractAndRecordTokenUsage(bodyStr, adapterType, instanceName, apiKeyId, quotaReservation, null);
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录，同时完成配额结算；支持透传响应耗时（P1-4）。
     *
     * <p>{@code responseTimeMs} 落库到 token_usage 表，供观测中心平均响应时间统计使用；
     * 传 {@code null} 时该字段保持 NULL（历史行为,AVG 统计自动忽略）。</p>
     *
     * @param responseTimeMs 下游响应耗时毫秒数，可为 {@code null}
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId,
                                              final QuotaReservation quotaReservation,
                                              final Long responseTimeMs) {
        extractAndRecordTokenUsage(bodyStr, adapterType, instanceName, apiKeyId,
                quotaReservation, responseTimeMs, null);
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录，同时完成配额结算；支持透传响应耗时与虚拟模型归属。
     *
     * <p>v3.5 Trunkline：{@code httpRequest} 用于读取本次请求命中的<b>虚拟模型</b>（模型分组），
     * 写入 {@code token_usage.virtual_model}，作为「团队用量」按虚拟模型统计的归属依据。</p>
     *
     * <p>traceId 在当前线程上就近捕获（兜底：调用方未透传时退回运行时取值）。</p>
     *
     * @param httpRequest 原始请求（读取虚拟模型属性），可为 {@code null}
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId,
                                              final QuotaReservation quotaReservation,
                                              final Long responseTimeMs,
                                              final ServerHttpRequest httpRequest) {
        extractAndRecordTokenUsage(bodyStr, adapterType, instanceName, apiKeyId,
                quotaReservation, responseTimeMs, httpRequest,
                // 兜底：此重载在追踪上下文仍存活的线程上被调用时才能取到值
                TracingContextHolder.getCurrentTraceId());
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录；traceId 由调用方<b>同步捕获后显式透传</b>。
     *
     * <p><b>为什么必须透传</b>：本方法在 WebClient 响应回调线程上执行，
     * {@link TracingContextHolder} 是 ThreadLocal 实现——回调线程上没有请求的追踪上下文，
     * 运行时再取只会拿到空串，导致 {@code token_usage.trace_id} 为空、
     * 账单无法通过 traceId 反查调用历史与追踪。调用方须在请求处理线程（上下文存活）
     * 同步捕获后传入（与流式路径 {@code traceIdForRecord} 同一修法）。</p>
     *
     * @param traceId 调用方同步捕获的追踪 ID；空串/null 时落空（不阻断落库）
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId,
                                              final QuotaReservation quotaReservation,
                                              final Long responseTimeMs,
                                              final ServerHttpRequest httpRequest,
                                              final String traceId) {
        try {
            JsonNode jsonNode = objectMapper.readTree(bodyStr);

            JsonNode usage = jsonNode.has("usage") ? jsonNode.get("usage") : null;
            long promptTokens = 0;
            long completionTokens = 0;
            long totalTokens = 0;
            long cacheHitTokens = 0;
            long cacheMissTokens = 0;

            if (usage != null) {
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
                totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asLong() : 0;

                // v2.9.0: 提取 KV 缓存命中/未命中 token 数
                // DeepSeek 形态: prompt_cache_hit_tokens / prompt_cache_miss_tokens
                // OpenAI/vLLM 形态: prompt_tokens_details.cached_tokens
                if (usage.has("prompt_cache_hit_tokens")) {
                    cacheHitTokens = usage.get("prompt_cache_hit_tokens").asLong();
                }
                if (usage.has("prompt_cache_miss_tokens")) {
                    cacheMissTokens = usage.get("prompt_cache_miss_tokens").asLong();
                }
                // OpenAI 形态: prompt_tokens_details.cached_tokens → 视为 cacheHit
                if (cacheHitTokens == 0 && usage.has("prompt_tokens_details")) {
                    JsonNode details = usage.get("prompt_tokens_details");
                    if (details.has("cached_tokens")) {
                        cacheHitTokens = details.get("cached_tokens").asLong();
                    }
                }
                // 如果只有 cacheHit 无 cacheMiss，估算 miss = promptTokens - cacheHit
                if (cacheHitTokens > 0 && cacheMissTokens == 0) {
                    cacheMissTokens = Math.max(0, promptTokens - cacheHitTokens);
                }
            }

            // issue #712: 响应缺 usage 或 total<=0 时，不再直接放弃落库与结算
            //（修复前「预留即计费」口径导致两链路口径不一致——流式在 total==0 且见过内容时
            // 已按估算落库+结算），改按响应文本估算 completion（QuotaTokenEstimator：
            // HAN/2、其余/4，与流式 estimateTokens 同系数），metadata.estimated=true 标记。
            // 定案口径：估算仅配额兜底，不作对外计费依据。
            boolean estimated = false;
            if (totalTokens <= 0) {
                final long estimatedCompletion =
                        QuotaTokenEstimator.estimateFromText(extractResponseText(jsonNode));
                if (estimatedCompletion <= 0) {
                    // 响应无文本信号（空 choices/纯工具调用等），退回旧行为：不落库不结算
                    return;
                }
                completionTokens = estimatedCompletion;
                totalTokens = promptTokens + completionTokens;
                estimated = true;
            }

            String model = jsonNode.has("model") ? jsonNode.get("model").asText() : "unknown";

            // 记录 KV 缓存指标
            if (metricsCollector != null && (cacheHitTokens > 0 || cacheMissTokens > 0)) {
                metricsCollector.recordCacheTokenUsage(adapterType, instanceName,
                        cacheHitTokens, cacheMissTokens);
            }

            // 记录到 TokenUsage 表（traceId 使用调用方同步捕获的值，见方法注释）
            if (tokenUsageRecorder != null) {
                tokenUsageRecorder.recordTokenUsageNoAuth(
                        "CHAT",
                        model,
                        adapterType,
                        instanceName,
                        null,
                        promptTokens,
                        completionTokens,
                        totalTokens,
                        traceId,
                        null,
                        true,
                        null,
                        null,
                        responseTimeMs,
                        extractVirtualModel(httpRequest),
                        // v3.5 Trunkline: 调用者（团队用量按此归人；缺失会导致聚合查不到）
                        resolveCallerUser(httpRequest, apiKeyId),
                        // issue #713: 计费归因——调用方未透传时回退请求属性（handler 认证后写入）
                        apiKeyId != null ? apiKeyId : extractKeyIdFromRequest(httpRequest),
                        // issue #712: 估算行标记（metadata 为 JSON 文本；账单侧只记请求数、不作计费依据）
                        estimated ? "{\"estimated\":true}" : null,
                        // v4.1: 缓存命中数 → 落库时点取价按命中拆算加权 effective 输入价
                        cacheHitTokens
                );
            }

            // 更新 API Key 的每日 Token 使用量配额
            updateApiKeyTokenUsage(apiKeyId, totalTokens);

            // v3.1 PR-2: 配额结算 — 按实际用量冲正预留（恰一次）
            QuotaReservation.settleSuccess(quotaReservation, totalTokens);

            if (estimated) {
                logger.debug("Non-streaming token usage estimated: adapter={}, instance={}, model={}, total={}",
                        adapterType, instanceName, model, totalTokens);
            } else {
                logger.debug("Non-streaming token usage recorded: adapter={}, instance={}, model={}, total={}",
                        adapterType, instanceName, model, totalTokens);
            }

        } catch (Exception e) {
            logger.debug("Failed to extract token usage from response: {}", e.getMessage());
        }
    }

    /**
     * 提取响应文本用于 token 估算（issue #712）.
     *
     * <p>拼接 {@code choices[*].message.content} 全量内容；tool_calls / 多 choices
     * 覆盖不全会低估（方案已知偏差，定案接受）。</p>
     *
     * @param jsonNode 响应 JSON
     * @return 拼接后的文本；无内容返回空串
     */
    private String extractResponseText(final JsonNode jsonNode) {
        final StringBuilder text = new StringBuilder();
        final JsonNode choices = jsonNode.get("choices");
        if (choices != null && choices.isArray()) {
            for (final JsonNode choice : choices) {
                final JsonNode message = choice.get("message");
                if (message != null && message.has("content") && message.get("content").isTextual()) {
                    text.append(message.get("content").asText());
                }
            }
        }
        return text.toString();
    }

    /**
     * 更新 API Key 的 Token 使用量
     */
    void updateApiKeyTokenUsage(final String apiKeyId, final long totalTokens) {
        if (apiKeyService == null || totalTokens <= 0 || apiKeyId == null) {
            return;
        }
        try {
            apiKeyService.updateTokenUsage(apiKeyId, totalTokens);
            logger.debug("API Key token usage updated: keyId={}, tokens={}", apiKeyId, totalTokens);
        } catch (Exception e) {
            logger.debug("Failed to update API Key token usage: keyId={}, error={}", apiKeyId, e.getMessage());
        }
    }

    /**
     * 从 ServerHttpRequest 属性中获取 API Key ID
     */
    String extractKeyIdFromRequest(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        Object keyId = httpRequest.getAttributes().get(
                org.unreal.modelrouter.router.handler.ServiceRequestHandler.API_KEY_ID_ATTRIBUTE);
        if (keyId instanceof String) {
            return (String) keyId;
        }
        return null;
    }

    /**
     * v3.5 Trunkline: 提取本次请求命中的<b>虚拟模型</b>（模型分组 / 池名）。
     *
     * <p>由 {@code ServiceRequestHandler} 在路由决策后写入请求属性（仿 API Key ID 传递先例）。
     * 取不到时返回 {@code null}（未命中任何分组 / 简化入口），落库归入「未归类」。</p>
     */
    String extractVirtualModel(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        Object virtualModel = httpRequest.getAttributes().get(
                org.unreal.modelrouter.router.handler.ServiceRequestHandler.VIRTUAL_MODEL_ATTRIBUTE);
        if (virtualModel instanceof String name && !name.isBlank()) {
            return name;
        }
        return null;
    }

    /**
     * v3.5 Trunkline: 解析本次调用的<b>调用者</b>（JWT 用户名 / 密钥持有人）。
     *
     * <p>用于用量落库的 {@code userId}——{@code aggregateByMonthUserVirtualModel} 的 WHERE 含
     * {@code userId IS NOT NULL}，缺失会让「团队用量」恒为 0。</p>
     *
     * <p><b>容错说明</b>：解析器不可用（未注入）时返回 {@code null} 而不是抛异常。
     * 本方法位于用量落库的 try 块内，一旦抛异常会被外层 catch 吞掉，
     * 导致其后的<b>配额结算</b>（{@code QuotaReservation.settleSuccess}）不执行——
     * 表现为配额账本停留在预留值，故这里必须吞掉异常而不是往上抛。</p>
     */
    private String resolveCallerUser(final ServerHttpRequest httpRequest, final String apiKeyId) {
        if (callerIdentityResolver == null) {
            return null;
        }
        try {
            return callerIdentityResolver.resolve(httpRequest, apiKeyId);
        } catch (Exception e) {
            logger.debug("解析调用者身份失败（忽略，不影响用量落库与配额结算）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * v2.8.9: 提取原始请求的 model 字段(chat/embedding 形态)
     */
    String extractRequestModel(final Object request) {
        if (request instanceof ChatDTO.Request r) {
            return r.model();
        }
        if (request instanceof EmbeddingDTO.Request e) {
            return e.model();
        }
        return null;
    }

    /**
     * 网关池名改写凭据的请求属性键。
     *
     * <p>池名判定（{@code PoolSelector#isPoolName}）依赖租户隔离的池快照；适配器处理器运行在
     * boundedElastic 线程上，租户 ThreadLocal 在该链路不可达，会把池名误判成普通模型名，
     * 虚拟模型名原样透传上游（上游按未知模型回 404）。网关侧在租户上下文可靠的线程上完成
     * 「是否为池名 → 改写目标实例名」判定，经此请求属性下发给处理器。</p>
     */
    public static final String GATEWAY_MODEL_REWRITE_ATTRIBUTE = "TRUNKLINE_GATEWAY_MODEL_REWRITE";

    /**
     * v2.8.9: 池路由后,将目标中的 model 字段改写为实际实例名。
     * 兼容 Map 与强类型 DTO/record 两种载体;record 不可变,故 DTO 经 Jackson 转为可变 Map 后替换,
     * 此时必须返回新对象并由调用方接收。仅当原始请求 model 是池名时触发,不影响普通请求。
     *
     * @return 改写后的请求载体(可能为新建的 Map);未触发改写时返回原对象
     */
    @SuppressWarnings("unchecked")
    Object rewriteModelField(final Object target, final String requestedModel, final String instanceName) {
        if (target == null || instanceName == null || requestedModel == null
                || poolSelector == null || !poolSelector.isPoolName(requestedModel)
                || requestedModel.equals(instanceName)) {
            return target;
        }
        return applyModelField(target, instanceName);
    }

    /**
     * 池名改写的网关凭据变体：优先采用网关在下发的改写凭据（见
     * {@link #GATEWAY_MODEL_REWRITE_ATTRIBUTE}），无凭据时回退到本线程的池名判定。
     *
     * @param gatewayRewriteTarget 网关下发的改写目标（实例名）；null/空表示无凭据
     */
    Object rewriteModelField(final Object target, final String requestedModel, final String instanceName,
            final String gatewayRewriteTarget) {
        if (target != null && gatewayRewriteTarget != null && !gatewayRewriteTarget.isBlank()
                && requestedModel != null && !requestedModel.equals(instanceName)) {
            return applyModelField(target, gatewayRewriteTarget);
        }
        return rewriteModelField(target, requestedModel, instanceName);
    }

    /** 把载体中的 model 字段替换为指定值（兼容 ObjectNode/Map/DTO record 三种载体）。 */
    @SuppressWarnings("unchecked")
    private Object applyModelField(final Object target, final String modelName) {
        if (target instanceof ObjectNode) {
            // Jackson ObjectNode 可变(如 GpuStackRequestTransformer 产出的请求),原地 put
            ((ObjectNode) target).put("model", modelName);
            return target;
        }
        if (target instanceof Map) {
            final Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) target);
            copy.put("model", modelName);
            return copy;
        }
        // DTO / record:不可变,序列化到可变 Map 后替换 model,返回新对象
        try {
            final Map<String, Object> map = objectMapper.convertValue(target, LinkedHashMap.class);
            map.put("model", modelName);
            return map;
        } catch (final Exception e) {
            logger.debug("池名 model 改写失败,保持原请求向下游转发: {}", e.getMessage());
            return target;
        }
    }
}
