package org.unreal.modelrouter.router.adapter.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.quota.QuotaReservation;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.auth.sanitization.SanitizationService;
import org.unreal.modelrouter.config.core.StreamingSafetyProperties;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.router.adapter.request.TokenUsageExtractor;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.monitor.callhistory.ApiCallHistoryRecorder;
import org.unreal.modelrouter.monitor.callhistory.config.CallHistoryProperties;
import org.unreal.modelrouter.monitor.callhistory.config.RecordLevel;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryRecordDTO;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.monitor.service.TokenUsageRecorder;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.router.adapter.CallerIdentityResolver;
import org.unreal.modelrouter.router.adapter.error.ErrorTargetResolver;
import org.unreal.modelrouter.router.adapter.transformer.ResponseTransformer;
import org.unreal.modelrouter.router.cache.CachedStreamingResponse;
import org.unreal.modelrouter.router.cache.ResponseCacheService;
import org.unreal.modelrouter.router.handler.ServiceRequestHandler;
import org.unreal.modelrouter.router.pool.PoolSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 流式请求处理器
 * 负责 SSE (Server-Sent Events) 格式的流式请求处理
 */
@Component
public class StreamingRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(StreamingRequestProcessor.class);

    // Token 估算系数
    private static final double ENGLISH_CHARS_PER_TOKEN = 4.0;
    private static final double CHINESE_CHARS_PER_TOKEN = 2.0;

    /** v3.2.3 (#127): 累积内容默认字符上限（宽松；配置可调） */
    static final int DEFAULT_MAX_CONTENT_CHARS = 1_048_576;

    /** v3.2.3 (#127): 流式缓存默认字符预算（超出则不缓存） */
    static final int DEFAULT_MAX_CACHE_CHARS = 262_144;

    /** v3.2.3 (#126): 上游块间空闲超时默认值（宽松；PT0S 可禁用） */
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(5);

    /** v3.2.3 (#127): 记录侧截断标记（累积超上界时附加在记录内容尾部） */
    static final String TRUNCATION_MARKER = "\n…[content truncated]";

    private final ResponseTransformer responseTransformer;

    // v3.2.3: 流式安全上界（可选注入，缺省时用宽松常量默认值）
    @Autowired(required = false)
    private StreamingSafetyProperties streamingSafety;

    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    @Autowired(required = false)
    private TokenUsageRecorder tokenUsageRecorder;

    @Autowired(required = false)
    private ApiKeyService apiKeyService;

    // v3.5 Trunkline: 调用者身份解析（JWT 用户名 / 密钥持有人）——调用观测与团队用量共用
    @Autowired(required = false)
    private CallerIdentityResolver callerIdentityResolver;

    // v2.8.9: 资源池选择器(池路由后改写下游流式请求的 model 字段为实际实例名)
    @Autowired(required = false)
    private PoolSelector poolSelector;

    // v2.9.2: 记录治理
    @Autowired(required = false)
    private ApiCallHistoryRecorder callHistoryRecorder;

    @Autowired(required = false)
    private CallHistoryProperties callHistoryProperties;

    @Autowired(required = false)
    private SanitizationService sanitizationService;

    // v2.9.10: 响应缓存门面（可选注入，未装配时跳过流式缓存写）
    @Autowired(required = false)
    private ResponseCacheService responseCacheService;
    
    // B5-out: 输出侧（流式增量）审核扩展点。社区版 Noop 的 openOutputSession 默认返回 empty，
    // 主干逐字节返回原始 chunk 流（零解析、零拷贝、零行为变化）。
    @Autowired(required = false)
    private ModerationProvider moderationProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public StreamingRequestProcessor(final ResponseTransformer responseTransformer) {
        this.responseTransformer = responseTransformer;
    }

    /**
     * v3.5 Trunkline: 提取本次请求命中的<b>虚拟模型</b>（模型分组 / 池名）。
     *
     * <p>由 {@link ServiceRequestHandler} 在路由决策后写入请求属性；取不到时返回 {@code null}
     * （未命中任何分组），落库归入「未归类」。</p>
     */
    private String extractVirtualModel(final org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        Object virtualModel = httpRequest.getAttributes().get(ServiceRequestHandler.VIRTUAL_MODEL_ATTRIBUTE);
        if (virtualModel instanceof String name && !name.isBlank()) {
            return name;
        }
        return null;
    }

    /**
     * v2.8.9: 池路由后,将请求中的 model 字段改写为实际实例名(仅池名触发)。
     * 兼容两种请求载体:Map(早期路径)与强类型 DTO/record(当前 chat/embedding/rerank 等路径)。
     * record 不可变,无法直接 set model,故对 DTO 走 Jackson 序列化到可变 Map 后再替换 model 字段。
     */
    @SuppressWarnings("unchecked")
    private Object rewriteModelField(final Object target, final String instanceName) {
        return rewriteModelField(target, instanceName, null);
    }

    /**
     * v2.8.9 池名改写（网关凭据变体）：{@code isPoolName} 依赖租户隔离的池快照，本方法在
     * boundedElastic 线程上租户上下文不可达时会误判，虚拟模型名透传上游（上游按未知模型回 404）。
     * 网关已在租户上下文可靠的线程上做出判定并经请求属性下发
     * （{@code TokenUsageExtractor.GATEWAY_MODEL_REWRITE_ATTRIBUTE}），凭据存在时直接改写。
     */
    private Object rewriteModelField(final Object target, final String instanceName,
            final String gatewayRewriteTarget) {
        if (target != null && gatewayRewriteTarget != null && !gatewayRewriteTarget.isBlank()) {
            final String originalModel = extractModel(target);
            if (originalModel != null && !originalModel.equals(instanceName)) {
                return replaceModel(target, gatewayRewriteTarget);
            }
            return target;
        }
        if (target == null || instanceName == null || poolSelector == null) {
            return target;
        }
        final String originalModel = extractModel(target);
        if (originalModel == null
                || !poolSelector.isPoolName(originalModel)
                || originalModel.equals(instanceName)) {
            return target;
        }
        return replaceModel(target, instanceName);
    }

    /**
     * 读取网关下发的池名改写凭据（见 {@code TokenUsageExtractor#GATEWAY_MODEL_REWRITE_ATTRIBUTE}）。
     * 请求属性随 ServerHttpRequest 走，与执行线程无关，规避 boundedElastic 上租户上下文缺失。
     */
    private String gatewayRewriteTarget(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        final Object target = httpRequest.getAttributes()
                .get(TokenUsageExtractor.GATEWAY_MODEL_REWRITE_ATTRIBUTE);
        return target instanceof String ? (String) target : null;
    }

    /**
     * 提取请求载体中的 model 字段值。
     * 支持三种载体:Jackson ObjectNode(如 GpuStackRequestTransformer 产出的请求)、
     * Map(早期路径)与带 model() 访问器的强类型 DTO/record(chat/embedding/rerank 等路径)。
     */
    private String extractModel(final Object target) {
        if (target instanceof ObjectNode) {
            final JsonNode m = ((ObjectNode) target).get("model");
            return m != null && m.isTextual() ? m.textValue() : null;
        }
        if (target instanceof Map) {
            final Object m = ((Map<String, Object>) target).get("model");
            return m instanceof String ? (String) m : null;
        }
        try {
            final Method modelMethod = target.getClass().getMethod("model");
            final Object v = modelMethod.invoke(target);
            return v instanceof String ? (String) v : null;
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * 将请求载体中的 model 字段替换为实际实例名。
     * ObjectNode 可变,原地 put;Map 复制替换;DTO/record 经 Jackson 转为可变 Map 后替换。
     */
    @SuppressWarnings("unchecked")
    private Object replaceModel(final Object target, final String instanceName) {
        if (target instanceof ObjectNode) {
            ((ObjectNode) target).put("model", instanceName);
            return target;
        }
        if (target instanceof Map) {
            final Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) target);
            copy.put("model", instanceName);
            return copy;
        }
        try {
            final Map<String, Object> map = objectMapper.convertValue(target, LinkedHashMap.class);
            map.put("model", instanceName);
            return map;
        } catch (final Exception e) {
            logger.debug("池名 model 改写失败,保持原请求向下游转发: {}", e.getMessage());
            return target;
        }
    }

    /**
     * 处理流式请求
     *
     * @param request            请求对象
     * @param authorization      授权信息
     * @param client             WebClient实例
     * @param path               请求路径
     * @param selectedInstance   选中的实例
     * @param serviceType        服务类型
     * @param adapterType        适配器类型
     * @param transformChunkFn   数据块转换函数（可选）
     * @return 流式响应 ResponseEntity
     */
    public <T> Mono<? extends org.springframework.http.ResponseEntity<?>> processStreamingRequest(
            final T request,
            final String authorization,
            final WebClient client,
            final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType,
            final String adapterType,
            final Function<String, String> transformChunkFn,
            final ServerHttpRequest httpRequest) {

        final String capturedKeyId = captureApiKeyId(httpRequest);
        // v3.5 Trunkline: 在入口处（仍在本请求处理线程、追踪上下文存活时）同步捕获 traceId，
        // 避免后续在 Netty event loop / 异步落库线程上再读 ThreadLocal 取到空串
        //（已有 bug：流式记录 traceId 落空，调用历史最新一条 Trace ID 为空）。
        final String capturedTraceId = TracingContextHolder.getCurrentTraceId();
        // 多租户：在入口处（请求处理线程、租户上下文存活时）同步捕获归属租户，
        // 供异步落库线程写入调用历史打标（异步线程读不到实时 ThreadLocal）。
        // 优先取认证成功时写入的请求属性（API Key 调用不带 X-Tenant-Id，租户靠 Key 归属反查；
        // 该属性在整个链路含异步线程都稳定可读），回退当前租户上下文。
        final String capturedTenantId = resolveTenantId(httpRequest);
        // v3.1 PR-2: 配额预留凭据（handler 在 selectInstance 前挂载；未预扣/账本未启用时为 null）
        final QuotaReservation quotaReservation = QuotaReservation.from(httpRequest);

        String instanceName = selectedInstance.getName();
        long requestStartTime = System.currentTimeMillis();

        // #1011: 下游错误日志目标（host:port 脱敏：丢 userinfo/query/fragment，不携带凭证）；
        // 只作 logger 占位参数，不拼进异常 message——/v1 面错误帧与响应 reason 会回显给客户端
        final String errorTarget = ErrorTargetResolver.hostPortFromUrl(selectedInstance.getBaseUrl());

        logger.debug("开始流式请求: adapter={}, instance={}, path={}", adapterType, instanceName, path);

        // v2.9.10: 流式缓存写 — 读取 handler 预存的缓存键（skipStreaming=false 且确定性请求时非空）
        final String cacheKeyForWrite;
        if (httpRequest != null && responseCacheService != null) {
            Object attr = httpRequest.getAttributes()
                    .get(ServiceRequestHandler.CACHE_KEY_ATTRIBUTE);
            cacheKeyForWrite = (attr instanceof String key && !key.isBlank()) ? key : null;
        } else {
            cacheKeyForWrite = null;
        }

        // v2.9.2: 记录治理 - 在入口处序列化请求体
        final String capturedRequestBody;
        RecordLevel recordLevel = resolveRecordLevel();
        if (recordLevel != RecordLevel.METADATA_ONLY) {
            capturedRequestBody = serializeAndTruncate(request);
        } else {
            capturedRequestBody = null;
        }

        // Token 使用量追踪
        AtomicLong promptTokens = new AtomicLong(0);
        AtomicLong completionTokens = new AtomicLong(0);
        AtomicLong totalTokens = new AtomicLong(0);
        // v2.9.0: KV 缓存命中/未命中 token 追踪
        AtomicLong cacheHitTokens = new AtomicLong(0);
        AtomicLong cacheMissTokens = new AtomicLong(0);
        StringBuilder contentBuilder = new StringBuilder();
        AtomicReference<String> modelRef = new AtomicReference<>("unknown");

        // v3.2.3 (#127): 独立 token 估算计数（按全部增量累计，不受截断影响）+ 截断标记
        final int maxContentChars = maxContentChars();
        final int maxCacheChars = maxCacheChars();
        final AtomicLong contentChineseChars = new AtomicLong(0);
        final AtomicLong contentOtherChars = new AtomicLong(0);
        final AtomicBoolean contentSawAnyChar = new AtomicBoolean(false);
        final AtomicBoolean contentTruncated = new AtomicBoolean(false);

        // 摘自上游 #356：是否已经产出过至少一个块 —— 决定失败时「按下游真实状态结束」（未产出）还是
        // 「先补协议 error 帧再终止」（已产出，此时 200 已提交，状态已无法更改）。
        final AtomicBoolean firstChunkEmitted = new AtomicBoolean(false);

        // #721：是否见过协议终止标记（OpenAI [DONE] / finish_reason / Anthropic message_stop）。
        // 下游提前断开 body 时 WebFlux 同样会正常 onComplete——无终止标记的"完整成功"
        // 实为截断流，把残缺内容写进缓存会污染后续同键请求。
        final AtomicBoolean terminalSeen = new AtomicBoolean(false);

        // v2.9.10: 流式缓存写 — 仅缓存键存在时收集变换后块与 finish_reason
        final List<String> transformedChunks = (cacheKeyForWrite != null) ? new ArrayList<>() : null;
        final AtomicReference<String> finishReasonRef = (cacheKeyForWrite != null)
                ? new AtomicReference<>(null) : null;
        // v3.2.3 (#127): 缓存字符预算 — 超预算的响应不缓存（缓存是优化，不是正确性）
        final AtomicLong cacheChars = new AtomicLong(0);
        final AtomicBoolean cacheOverBudget = new AtomicBoolean(false);

        // v3.1 修复: 与非流式路径保持一致——实例自定义 headers 优先（含下游 Authorization），
        // 仅在实例未提供 Authorization 时回退到请求传入的 authorization。
        // 修复前流式路径写死 .header("Authorization", authorization)，导致"密钥只配在实例
        // headers 上"的部署（参见 config-external/router/services.yml 的既有模式）流式调用
        // 必然被下游 401 拒绝。
        final Map<String, String> instanceHeaders = (selectedInstance.getHeaders() != null)
                ? selectedInstance.getHeaders() : Map.of();
        final boolean hasInstanceAuth = instanceHeaders.keySet().stream()
                .anyMatch(key -> "Authorization".equalsIgnoreCase(key));

        WebClient.RequestBodySpec requestSpec = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        for (Map.Entry<String, String> header : instanceHeaders.entrySet()) {
            requestSpec = requestSpec.header(header.getKey(), header.getValue());
            logger.debug("应用实例自定义请求头: {} = {}", header.getKey(), "***");
        }
        if (!hasInstanceAuth && authorization != null && !authorization.isEmpty()) {
            requestSpec = requestSpec.header("Authorization", authorization);
        }

        // B5-out: 输出侧审核的输入上下文只构造一次（不在每帧解析调用者 / 租户）。
        final ModerationProvider.ModerationInput outputModerationInput = moderationProvider == null
                ? null
                : new ModerationProvider.ModerationInput(
                        adapterType, "", resolveCallerUser(httpRequest, capturedKeyId),
                        TenantContextHolder.currentTenantId());

        // 使用 ServerSentEvent 包装每个数据块，确保 SSE 格式正确。
        // 摘自上游 v3.2.x (#257 A)：后处理链抽成局部 lambda —— /v1 面需要在**订阅响应体之前**先看下游状态，
        // 因此链路入口从「固定 retrieve()」改为「由调用方传入原始 chunk 流」；
        // legacy /api/** 面仍以同样的 retrieve().onStatus(...) 进入（见下方分支），语义逐字节不变。
        final Function<Flux<String>, Flux<ServerSentEvent<String>>> decorateChunks = rawChunks -> {
            // B5-out: 挂在原始 chunk 层（SSE 包装之前），一处覆盖 /v1、legacy 与 Anthropic translator
            // 的下游。拦截以异常终止 → doOnComplete 不触发（不写流式缓存、不记成功、不落违规响应体），
            // 由既有 onErrorResume / StreamingErrorTermination 补协议 error 帧。
            // 社区版 Noop 返回空会话，apply 原样返回 rawChunks，此处零开销。
            final Flux<String> moderatedChunks = StreamingOutputModeration.apply(
                    rawChunks, moderationProvider, outputModerationInput, objectMapper);
            Flux<ServerSentEvent<String>> streamResponse = moderatedChunks
                .map(chunk -> {
                    // 摘自上游 #356：标记已产出首块（决定失败时是否可补协议 error 帧）
                    firstChunkEmitted.set(true);
                    // 提取 usage 信息和累积内容。
                    // v3.2.3: contentBuilder 与 doOnCancel/doOnError 的缓冲释放共享监视器
                    // （Reactor 不保证 cancel 与 onNext 串行，StringBuilder 非线程安全）。
                    synchronized (contentBuilder) {
                        final int contentLenBefore = contentBuilder.length();
                        extractUsageAndContent(chunk, promptTokens, completionTokens, totalTokens,
                                cacheHitTokens, cacheMissTokens, contentBuilder, modelRef);
                        // v3.2.3 (#127): 独立计数（截断前）+ 累积上界
                        accountAppendedChars(contentBuilder, contentLenBefore,
                                contentChineseChars, contentOtherChars, contentSawAnyChar);
                        if (contentBuilder.length() > maxContentChars) {
                            contentBuilder.setLength(maxContentChars);
                            contentTruncated.set(true);
                        }
                    }
                    // v2.9.10: 提取 finish_reason 用于流式缓存；#721: 同步记录终止标记
                    if (cacheKeyForWrite != null) {
                        extractFinishReason(chunk, finishReasonRef, terminalSeen);
                    }
                    ServerSentEvent<String> sse = transformAndWrapChunk(chunk, transformChunkFn);
                    // v2.9.10: 收集变换后 data 串用于流式缓存（v3.2.3: 受缓存字符预算约束）
                    if (transformedChunks != null && !cacheOverBudget.get()) {
                        final String data = sse.data();
                        cacheChars.addAndGet(data != null ? data.length() : 0);
                        synchronized (transformedChunks) {
                            if (cacheChars.get() > maxCacheChars) {
                                cacheOverBudget.set(true);
                                transformedChunks.clear();
                            } else {
                                transformedChunks.add(data);
                            }
                        }
                    }
                    return sse;
                });

        // v3.2.3 (#126): 块间空闲看门狗 — 超时向上游 cancel（释放连接），
        // 向下游发出终止错误（TimeoutException），经 doOnError 回滚配额并释放累积缓冲。
        // 空闲型而非总时长型：长但持续产出的流不受影响。PT0S/负值可禁用。
        final Duration idleTimeout = idleTimeout();
        if (idleTimeout != null && !idleTimeout.isZero() && !idleTimeout.isNegative()) {
            streamResponse = streamResponse.timeout(idleTimeout);
        }

        streamResponse = streamResponse
                .doOnComplete(() -> {
                    recordStreamingComplete(serviceType, adapterType, instanceName, requestStartTime);

                    // v2.9.10: 流式缓存写 — 完整成功流结束后缓存（错误/中断不触发 doOnComplete）
                    // v3.2.3 (#127): 超出缓存预算的响应不缓存
                    if (cacheKeyForWrite != null && responseCacheService != null
                            && transformedChunks != null && !transformedChunks.isEmpty()
                            && !cacheOverBudget.get()
                            // #721：仅当流以协议终止标记收尾才写缓存——下游提前断开也会
                            // 正常触发 doOnComplete，无标记即截断流，缓存残缺内容会污染
                            // 后续同键请求。保守代价：不发终止标记的厂商其流不再被缓存。
                            && terminalSeen.get()) {
                        final List<String> chunksToCache;
                        synchronized (transformedChunks) {
                            chunksToCache = List.copyOf(transformedChunks);
                        }
                        cacheStreamingResponse(cacheKeyForWrite, new CachedStreamingResponse(
                                chunksToCache,
                                modelRef.get(),
                                promptTokens.get() > 0 ? promptTokens.get() : null,
                                completionTokens.get() > 0 ? completionTokens.get() : null,
                                totalTokens.get() > 0 ? totalTokens.get() : null,
                                finishReasonRef != null ? finishReasonRef.get() : null));
                    }

                    // 记录 token 使用量(含 KV 缓存指标)。
                    // v3.2.3 (#127): 估算走独立计数，不依赖可能被截断的 contentBuilder。
                    recordTokenUsage(httpRequest, adapterType, instanceName, modelRef.get(),
                            promptTokens.get(), completionTokens.get(), totalTokens.get(),
                            cacheHitTokens.get(), cacheMissTokens.get(),
                            estimateTokensFromCounts(contentChineseChars.get(), contentOtherChars.get()),
                            contentSawAnyChar.get(), capturedKeyId, quotaReservation,
                            System.currentTimeMillis() - requestStartTime,
                            extractVirtualModel(httpRequest), capturedTraceId);

                    // v2.9.2: 记录治理 - 记录含请求/响应体的调用历史
                    // 脱敏与落库不得阻塞 EventLoop（doOnComplete 可能在 IO 线程执行）
                    if (recordLevel != RecordLevel.METADATA_ONLY && callHistoryRecorder != null) {
                        final long duration = System.currentTimeMillis() - requestStartTime;
                        final String rawResponseBody;
                        synchronized (contentBuilder) {
                            rawResponseBody = recordedResponseBody(contentBuilder, contentTruncated.get());
                        }
                        final String modelForRecord = modelRef.get();
                        // 关联链路追踪：复用入口处已捕获的 traceId（完成回调在 Netty event loop 上执行，
                        // 此处再读 ThreadLocal 必为空串）。
                        final String traceIdForRecord = capturedTraceId;
                        final long promptTok = promptTokens.get();
                        final long completionTok = completionTokens.get();
                        final long totalTok = totalTokens.get();
                        final boolean summarySanitize = recordLevel == RecordLevel.SUMMARY
                                && sanitizationService != null && rawResponseBody != null;

                        Mono<String> bodyMono = summarySanitize
                                ? sanitizationService.sanitizeForStorage(rawResponseBody, "application/json")
                                        .timeout(Duration.ofSeconds(5))
                                        .onErrorResume(e -> {
                                            logger.debug("流式响应体脱敏失败: {}", e.getMessage());
                                            return Mono.just(rawResponseBody);
                                        })
                                        .map(sanitized -> sanitized != null ? sanitized : rawResponseBody)
                                : Mono.just(rawResponseBody);

                        bodyMono.subscribeOn(Schedulers.boundedElastic())
                                .subscribe(responseBodyForRecord -> {
                                    try {
                                        CallHistoryRecordDTO dto = CallHistoryRecordDTO.builder()
                                                // 关联链路追踪（为 null 时落库侧兜底随机 UUID）
                                                .traceId(traceIdForRecord)
                                                .serviceType(serviceType != null ? serviceType.name() : null)
                                                .modelName(modelForRecord)
                                                .virtualModel(extractVirtualModel(httpRequest))
                                                .provider(adapterType)
                                                .instanceName(instanceName)
                                                .instanceUrl(selectedInstance.getBaseUrl())
                                                .responseTimeMs(duration)
                                                .isSuccess(true)
                                                .promptTokens(promptTok > 0 ? promptTok : null)
                                                .completionTokens(completionTok > 0 ? completionTok : null)
                                                .totalTokens(totalTok > 0 ? totalTok : null)
                                                .apiKeyId(capturedKeyId)
                                                .tenantId(capturedTenantId)
                                                .userId(resolveCallerUser(httpRequest, capturedKeyId))
                                                .requestBody(capturedRequestBody)
                                                .responseBody(responseBodyForRecord)
                                                .build();
                                        callHistoryRecorder.record(dto);
                                    } catch (Exception e) {
                                        logger.debug("流式调用历史记录失败: {}", e.getMessage());
                                    }
                                }, err -> logger.debug("流式调用历史脱敏订阅失败: {}", err.getMessage()));
                    }
                })
                .doOnError(throwable -> {
                    // v3.2.3 (#126/#127): 释放累积缓冲（空闲超时/异常终止时立即归还内存）。
                    // onError 与 onNext 串行，此处与 map() 无并发；同步仅统一监视器约定。
                    releaseAccumulation(contentBuilder, transformedChunks);
                    // #719：此处结算为「终端结算」而非 attempt 级——body 已在 200 响应内提交，
                    // BaseAdapter 的重试只覆盖 ResponseEntity 发出前的错误，body 信号（错误/取消）
                    // 不再有任何重试跟随，且 handler 层观测不到 body 级信号；删除会把流中失败
                    // 与客户端断连的预留永久泄漏（残量直到窗口滚动才自愈）。保留恰一次 CAS。
                    QuotaReservation.settleFailure(quotaReservation);
                    // #1011: target 优先沿 cause 链取真实目标（回退 baseUrl）
                    recordStreamingError(serviceType, adapterType, instanceName,
                            ErrorTargetResolver.resolveTarget(throwable, selectedInstance.getBaseUrl()),
                            requestStartTime, throwable);
                })
                .doOnCancel(() -> {
                    // v3.2.3 (#126/#127): 同样释放累积缓冲（cancel 不与 onNext 串行，须持锁）
                    releaseAccumulation(contentBuilder, transformedChunks);
                    // #719：body 级取消（客户端断连）是终端处置，同上保留结算。
                    // 「重试中取消」发生在 ResponseEntity 发出前，不会进入本钩子，
                    // 由 ServiceRequestHandler 末端的 doOnCancel 结算。
                    QuotaReservation.settleFailure(quotaReservation);
                })
                // 摘自上游 v3.2.x (#257)：/v1 OpenAI 面在失败时先补一个协议形状 error 帧、再以异常终止
                // （不补 [DONE]：它是「正常结束」的语汇，补上会让截断被读成成功；详见 StreamingErrorTermination）。
                // #356：**只在首块之后**补帧 —— 首块之前失败时状态码还没提交，必须让异常原样上抛，
                // 才能按下游真实状态结束；若此时补帧，WebFlux 会先写出 200，
                // 客户端就把「下游 500」读成 200 + error 帧。
                .onErrorResume(throwable -> firstChunkEmitted.get()
                        ? StreamingErrorTermination.terminate(throwable, httpRequest, objectMapper)
                        : Flux.error(throwable));

            return streamResponse;
        };

        if (StreamingErrorTermination.isV1Face(httpRequest)) {
            // 摘自上游 #356 修复：原实现把 body flux 交回框架后 Mono.just(ResponseEntity) 立即终止，
            // WebClient 释放并排空响应体 ⇒ 客户端看到 200 + text/event-stream 但响应体 0 字节。
            // 现改为 retrieve() + 状态保真的 onStatus：错误仍在订阅响应体之前发生，
            // 而正常流的 body 与 legacy 面走同一条已验证有帧的 bodyToFlux 路径。
            final Flux<ServerSentEvent<String>> v1Stream = decorateChunks.apply(requestSpec
                    .bodyValue(rewriteModelField(request, instanceName, gatewayRewriteTarget(httpRequest)))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse -> {
                        logger.warn("流式请求下游错误: instance={}, target={}, status={}",
                                instanceName, errorTarget, clientResponse.statusCode());
                        return clientResponse.releaseBody()
                                .then(Mono.error(new ResponseStatusException(
                                        clientResponse.statusCode(), "下游服务错误")));
                    })
                    .bodyToFlux(String.class));

            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(v1Stream));
        }

        // legacy /api/** 面（与 Anthropic 面之外的其余路径）：保持既有语义 —— 200 + 流，异常在流内终止
        final Flux<ServerSentEvent<String>> legacyStream = decorateChunks.apply(requestSpec
                .bodyValue(rewriteModelField(request, instanceName, gatewayRewriteTarget(httpRequest)))
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
                    logger.error("流式请求5xx错误: instance={}, target={}, status={}",
                            instanceName, errorTarget, clientResponse.statusCode());
                    return Mono.error(new ResponseStatusException(
                            clientResponse.statusCode(), "下游服务错误"));
                })
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                    logger.error("流式请求4xx错误: instance={}, target={}, status={}",
                            instanceName, errorTarget, clientResponse.statusCode());
                    return Mono.error(new ResponseStatusException(
                            clientResponse.statusCode(), "请求错误"));
                })
                .bodyToFlux(String.class));

        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(legacyStream));
    }

    /**
     * 转换并包装数据块为 SSE 格式
     */
    private ServerSentEvent<String> transformAndWrapChunk(final String chunk,
                                                            final Function<String, String> transformFn) {
        String transformed;
        if (transformFn != null) {
            transformed = transformFn.apply(chunk);
        } else {
            transformed = responseTransformer.transformStreamChunk(chunk);
        }

        return ServerSentEvent.<String>builder()
                .data(transformed)
                .build();
    }

    /**
     * 记录流式请求完成指标
     */
    private void recordStreamingComplete(final ModelServiceRegistry.ServiceType serviceType,
                                          final String adapterType,
                                          final String instanceName,
                                          final long startTime) {
        if (metricsCollector != null) {
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordRequest(serviceType.name(), "STREAM", responseTime, "200");
            metricsCollector.recordBackendCall(adapterType, instanceName, responseTime, true);
            logger.debug("流式请求完成: adapter={}, instance={}, duration={}ms",
                    adapterType, instanceName, responseTime);
        }
    }

    /**
     * 记录流式请求错误指标
     *
     * <p>#1011: 日志带 target（沿 cause 链取连接级异常的真实 URI host:port，回退实例 baseUrl）；
     * 只打异常类名不打 {@code getMessage()} 原文——WebClientRequestException 的 message
     * 含完整 URL，凭证拼在 query/userinfo 时原文入日志即泄露。</p>
     */
    private void recordStreamingError(final ModelServiceRegistry.ServiceType serviceType,
                                        final String adapterType,
                                        final String instanceName,
                                        final String target,
                                        final long startTime,
                                        final Throwable throwable) {
        if (metricsCollector != null) {
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordBackendCall(adapterType, instanceName, responseTime, false);
            logger.error("流式请求错误: adapter={}, instance={}, target={}, error={}",
                    adapterType, instanceName, target, throwable.getClass().getSimpleName());
        }
    }

    /**
     * 获取默认的数据块转换器
     */
    public Function<String, String> getDefaultChunkTransformer(final String adapterType) {
        return chunk -> responseTransformer.transformStreamChunk(chunk);
    }

    /**
     * 处理流式请求（使用默认转换器）
     */
    public <T> Mono<? extends org.springframework.http.ResponseEntity<?>> processStreamingRequest(
            final T request,
            final String authorization,
            final WebClient client,
            final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType,
            final String adapterType,
            final ServerHttpRequest httpRequest) {

        return processStreamingRequest(request, authorization, client, path,
                selectedInstance, serviceType, adapterType, null, httpRequest);
    }

    /**
     * 从 SSE chunk 提取 usage 信息和累积响应内容
     *
     * @param chunk              SSE 数据块
     * @param promptTokens       prompt tokens 计数器
     * @param completionTokens   completion tokens 计数器
     * @param totalTokens        total tokens 计数器
     * @param cacheHitTokens     v2.9.0: KV 缓存命中 token 计数器
     * @param cacheMissTokens    v2.9.0: KV 缓存未命中 token 计数器
     * @param contentBuilder     内容累积器
     * @param modelRef           模型名称引用
     */
    private void extractUsageAndContent(final String chunk,
                                         final AtomicLong promptTokens,
                                         final AtomicLong completionTokens,
                                         final AtomicLong totalTokens,
                                         final AtomicLong cacheHitTokens,
                                         final AtomicLong cacheMissTokens,
                                         final StringBuilder contentBuilder,
                                         final AtomicReference<String> modelRef) {
        try {
            String jsonPart = chunk;
            if (chunk.startsWith("data: ")) {
                jsonPart = chunk.substring(6);
            }

            if ("[DONE]".equals(jsonPart.trim())) {
                return;
            }

            JsonNode jsonNode = objectMapper.readTree(jsonPart);

            // 提取模型名称
            if (jsonNode.has("model")) {
                modelRef.set(jsonNode.get("model").asText());
            }

            // 提取 usage 信息（如果后端提供）
            if (jsonNode.has("usage")) {
                JsonNode usage = jsonNode.get("usage");
                if (usage.has("prompt_tokens")) {
                    promptTokens.set(usage.get("prompt_tokens").asLong());
                }
                if (usage.has("completion_tokens")) {
                    completionTokens.set(usage.get("completion_tokens").asLong());
                }
                if (usage.has("total_tokens")) {
                    totalTokens.set(usage.get("total_tokens").asLong());
                }
                // v2.9.0: 提取 KV 缓存命中/未命中 token 数
                // DeepSeek 形态
                if (usage.has("prompt_cache_hit_tokens")) {
                    cacheHitTokens.set(usage.get("prompt_cache_hit_tokens").asLong());
                }
                if (usage.has("prompt_cache_miss_tokens")) {
                    cacheMissTokens.set(usage.get("prompt_cache_miss_tokens").asLong());
                }
                // OpenAI/vLLM 形态: prompt_tokens_details.cached_tokens
                if (cacheHitTokens.get() == 0 && usage.has("prompt_tokens_details")) {
                    JsonNode details = usage.get("prompt_tokens_details");
                    if (details.has("cached_tokens")) {
                        cacheHitTokens.set(details.get("cached_tokens").asLong());
                    }
                }
            }

            // 累积响应内容（从 choices 中提取）
            if (jsonNode.has("choices")) {
                JsonNode choices = jsonNode.get("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).path("delta");
                    if (delta.has("content")) {
                        contentBuilder.append(delta.get("content").asText());
                    }
                }
            }
        } catch (Exception e) {
            logger.trace("Failed to parse chunk for usage extraction: {}", e.getMessage());
        }
    }

    /**
     * 记录 Token 使用量
     * 如果后端未提供 usage 信息，则根据独立字符计数进行估算（与完整内容估算逐字节一致）
     *
     * <p>v3.1 PR-2: 本方法同时是流式链路的配额结算点——在 token 落库处按
     * {@code 实际 − 估算} 冲正预留（{@link QuotaReservation#settleSuccess(QuotaReservation, long)}），
     * 中断/异常路径由调用方的 {@code doOnError} / {@code doOnCancel} 回滚，
     * 凭据自身保证恰一次结算。</p>
     *
     * <p>v3.2.3 (#127): 估算入参改为独立累计的 token 数与「是否见过内容」标记，
     * 不再读取可能被截断的累积缓冲——截断不影响账单/配额。</p>
     */
    private void recordTokenUsage(final ServerHttpRequest httpRequest,
                                   final String adapterType,
                                   final String instanceName,
                                   final String model,
                                   final long promptTokens,
                                   final long completionTokens,
                                   final long totalTokens,
                                   final long cacheHitTokens,
                                   final long cacheMissTokens,
                                   final long estimatedCompletionTokens,
                                   final boolean sawContent,
                                   final String apiKeyId,
                                   final QuotaReservation quotaReservation,
                                   final Long responseTimeMs,
                                   final String virtualModel,
                                   final String traceId) {
        if (tokenUsageRecorder == null) {
            return;
        }

        long finalPromptTokens = promptTokens;
        long finalCompletionTokens = completionTokens;
        long finalTotalTokens = totalTokens;

        // 如果后端未提供 usage，则估算（估算值来自独立计数，与截断无关）
        if (totalTokens == 0 && sawContent) {
            finalCompletionTokens = estimatedCompletionTokens;
            finalTotalTokens = finalPromptTokens + finalCompletionTokens;
            logger.debug("Token usage estimated: adapter={}, instance={}, prompt={}, completion={}, total={}",
                    adapterType, instanceName, finalPromptTokens, finalCompletionTokens, finalTotalTokens);
        } else if (totalTokens > 0) {
            logger.debug("Token usage from backend: adapter={}, instance={}, prompt={}, completion={}, total={}",
                    adapterType, instanceName, finalPromptTokens, finalCompletionTokens, finalTotalTokens);
        }

        // 只有当有实际 token 使用量时才记录
        if (finalTotalTokens > 0) {
            try {
                // 使用入口捕获的 traceId（在 Netty event loop 上读 ThreadLocal 必为空串）
                tokenUsageRecorder.recordTokenUsageNoAuth(
                        "CHAT",
                        model,
                        adapterType,
                        instanceName,
                        null, // instanceUrl
                        finalPromptTokens,
                        finalCompletionTokens,
                        finalTotalTokens,
                        traceId,
                        null, // clientIp
                        true, // isSuccess
                        null, // errorCode
                        null, // errorMessage
                        responseTimeMs, // P1-4: 流式耗时透传,供平均响应时间统计
                        virtualModel, // v3.5 Trunkline: 请求命中的虚拟模型（模型分组）
                        resolveCallerUser(httpRequest, apiKeyId), // v3.5 Trunkline: 调用者（团队用量按此归人）
                        apiKeyId, // issue #713: 计费归因——按 Key 统计的归属依据
                        null, // v4.1: metadata 占位（流式原无 metadata 位，第 18 参）
                        cacheHitTokens // v4.1: 缓存命中数（方法参数，第 19 参）→ 取价拆算缓存加权
                );

                // 更新 API Key 的每日 Token 使用量配额
                updateApiKeyTokenUsage(apiKeyId, finalTotalTokens);

                // v3.1 PR-2: 配额结算 — 按实际用量冲正预留（恰一次；失败/中断路径回滚）
                QuotaReservation.settleSuccess(quotaReservation, finalTotalTokens);

                // v2.9.0: 记录 KV 缓存命中/未命中指标
                if (metricsCollector != null && (cacheHitTokens > 0 || cacheMissTokens > 0)) {
                    metricsCollector.recordCacheTokenUsage(adapterType, instanceName,
                            cacheHitTokens, cacheMissTokens);
                }

            } catch (Exception e) {
                logger.warn("Failed to record token usage: {}", e.getMessage());
            }
        }

        // v2.9.0: 即使 totalTokens==0，如果有缓存命中信息也记录指标
        if (finalTotalTokens == 0 && metricsCollector != null && (cacheHitTokens > 0 || cacheMissTokens > 0)) {
            metricsCollector.recordCacheTokenUsage(adapterType, instanceName,
                    cacheHitTokens, cacheMissTokens);
        }
    }

    /**
     * 更新 API Key 的 Token 使用量
     */
    private void updateApiKeyTokenUsage(final String apiKeyId, final long totalTokens) {
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
     * 从请求属性中捕获 API Key ID
     * 使用 ServiceRequestHandler 预存的 keyId（与 NonStreamingRequestProcessor 一致）
     */
    /**
     * 解析本次调用的归属租户（多租户）。
     *
     * <p>取值顺序：① 认证成功时写入的请求属性 {@code TENANT_ID_ATTRIBUTE}（API Key 归属，
     * 对异步落库线程同样可见）；② 当前租户上下文（控制台 JWT 请求由企业过滤器按
     * {@code X-Tenant-Id} 解析后写入）；③ 都取不到时由落库侧回落单租户 {@code default}。</p>
     */
    private String resolveTenantId(final ServerHttpRequest httpRequest) {
        try {
            if (httpRequest != null) {
                final Object attr = httpRequest.getAttributes()
                        .get(org.unreal.modelrouter.router.handler.ServiceRequestHandler.TENANT_ID_ATTRIBUTE);
                if (attr instanceof String tenantId && !tenantId.isBlank()) {
                    return tenantId;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve tenant id from request attributes: {}", e.getMessage());
        }
        return TenantContextHolder.currentTenantId();
    }

    private String captureApiKeyId(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        try {
            Object keyId = httpRequest.getAttributes()
                    .get(org.unreal.modelrouter.router.handler.ServiceRequestHandler.API_KEY_ID_ATTRIBUTE);
            if (keyId instanceof String key && !key.isBlank()) {
                return key;
            }
        } catch (Exception e) {
            logger.debug("Failed to capture API Key ID from request attributes: {}", e.getMessage());
        }
        return null;
    }

    /**
     * v3.5 Trunkline: 解析本次调用的<b>员工</b>身份（「调用观测」的「员工」列、
     * 「团队用量」的归属维度）。
     *
     * <p>委托给 {@link CallerIdentityResolver}——与 {@code TokenUsageExtractor} 共用同一套解析逻辑，
     * 保证「调用观测」和「团队用量」两处的归属口径完全一致。</p>
     *
     * <p><b>容错说明</b>：解析失败时返回 {@code null} 而不抛异常。本方法在用量落库的 try 块内被调用，
     * 抛异常会被外层 catch 吞掉，导致其后的配额结算不执行（配额账本会停在预留值）。</p>
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
     * 根据内容估算 token 数量
     * 英文约 4 字符/token，中文约 2 字符/token
     */
    private long estimateTokens(final String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;

        for (char c : content.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            } else if (!Character.isWhitespace(c)) {
                otherChars++;
            }
        }

        return (long) Math.ceil(chineseChars / CHINESE_CHARS_PER_TOKEN
                + otherChars / ENGLISH_CHARS_PER_TOKEN);
    }

    // ==================== v3.2.3 (#126, #127): 流式安全辅助 ====================

    /**
     * 释放累积缓冲（异常/取消终止时立即归还内存）。
     *
     * <p>{@code doOnCancel} 与 {@code onNext} 不保证串行，必须与 {@code map()} 中的
     * 累积写入共用监视器，避免 {@link StringBuilder}/{@link ArrayList} 并发损坏。</p>
     *
     * @param contentBuilder    内容累积缓冲
     * @param transformedChunks 流式缓存块收集器（无缓存键时为 {@code null}）
     */
    private void releaseAccumulation(final StringBuilder contentBuilder,
                                     final List<String> transformedChunks) {
        synchronized (contentBuilder) {
            contentBuilder.setLength(0);
        }
        if (transformedChunks != null) {
            synchronized (transformedChunks) {
                transformedChunks.clear();
            }
        }
    }

    /**
     * 将 contentBuilder 新增区间计入独立 token 估算计数（截断前调用，保证与完整内容估算一致）.
     *
     * @param contentBuilder   累积缓冲
     * @param from             本次增量起始下标
     * @param chineseChars     汉字计数（估算用，独立于缓冲）
     * @param otherChars       其余非空白字符计数（估算用，独立于缓冲）
     * @param sawAnyChar       是否出现过任意字符（含空白；决定估算分支是否启用）
     */
    private void accountAppendedChars(final StringBuilder contentBuilder,
                                      final int from,
                                      final AtomicLong chineseChars,
                                      final AtomicLong otherChars,
                                      final AtomicBoolean sawAnyChar) {
        final int to = contentBuilder.length();
        if (to <= from) {
            return;
        }
        sawAnyChar.set(true);
        for (int i = from; i < to; i++) {
            final char c = contentBuilder.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars.incrementAndGet();
            } else if (!Character.isWhitespace(c)) {
                otherChars.incrementAndGet();
            }
        }
    }

    /**
     * 由独立字符计数估算 token 数——与 {@link #estimateTokens(String)} 同一系数.
     *
     * <p>对未截断的同一段内容，结果与 {@code estimateTokens(contentBuilder.toString())}
     * 完全一致（先按字符分类计数再代入公式，除法顺序不变）。</p>
     *
     * @param chineseChars 汉字字符数
     * @param otherChars   其余非空白字符数
     * @return 估算 token 数（非负）
     */
    private long estimateTokensFromCounts(final long chineseChars, final long otherChars) {
        return (long) Math.ceil(chineseChars / CHINESE_CHARS_PER_TOKEN
                + otherChars / ENGLISH_CHARS_PER_TOKEN);
    }

    /**
     * 记录用响应体：有界前缀 + 截断标记（如有），再按 call-history 上限截断.
     *
     * @param contentBuilder    累积缓冲（已按 maxContentChars 收紧）
     * @param contentTruncated  是否发生过累积截断
     * @return 记录用响应体
     */
    private String recordedResponseBody(final StringBuilder contentBuilder, final boolean contentTruncated) {
        final String body = contentTruncated
                ? contentBuilder + TRUNCATION_MARKER
                : contentBuilder.toString();
        return truncate(body);
    }

    /**
     * 累积内容字符上限（配置优先，缺省 {@link #DEFAULT_MAX_CONTENT_CHARS}）.
     */
    private int maxContentChars() {
        if (streamingSafety != null && streamingSafety.getMaxContentChars() > 0) {
            return streamingSafety.getMaxContentChars();
        }
        return DEFAULT_MAX_CONTENT_CHARS;
    }

    /**
     * 流式缓存字符预算（配置优先，缺省 {@link #DEFAULT_MAX_CACHE_CHARS}）.
     */
    private int maxCacheChars() {
        if (streamingSafety != null && streamingSafety.getMaxCacheChars() > 0) {
            return streamingSafety.getMaxCacheChars();
        }
        return DEFAULT_MAX_CACHE_CHARS;
    }

    /**
     * 上游块间空闲超时（配置优先；PT0S/负值表示禁用）.
     */
    private Duration idleTimeout() {
        if (streamingSafety != null && streamingSafety.getIdleTimeout() != null) {
            return streamingSafety.getIdleTimeout();
        }
        return DEFAULT_IDLE_TIMEOUT;
    }

    /**
     * v2.9.10: 从 SSE 块提取 finish_reason（用于流式缓存）.
     *
     * <p>与 {@code extractUsageAndContent} 独立，避免修改其签名破坏既有反射测试。
     *
     * @param chunk           原始 SSE 块（可能含 {@code data: } 前缀）
     * @param finishReasonRef finish_reason 累积引用（最后非 null 值生效）
     */
    private void extractFinishReason(final String chunk, final AtomicReference<String> finishReasonRef) {
        // #721：保留两参签名（既有反射测试锁定），委托三参重载（terminalSeen=null 不置位）。
        extractFinishReason(chunk, finishReasonRef, null);
    }

    /**
     * #721：三参重载——提取 finish_reason 的同时记录协议终止标记。
     * 覆盖三种协议形态：OpenAI {@code [DONE]} / {@code choices[0].finish_reason}、
     * Anthropic {@code event: message_stop}（独立 event 行，data 行可能缺席）
     * 与 {@code {"type":"message_stop"}}。
     */
    private void extractFinishReason(final String chunk, final AtomicReference<String> finishReasonRef,
            final AtomicBoolean terminalSeen) {
        try {
            String jsonPart = chunk;
            if (chunk.startsWith("data: ")) {
                jsonPart = chunk.substring(6);
            }
            if ("[DONE]".equals(jsonPart.trim())) {
                // #721：[DONE] 是 OpenAI 协议终止帧——先置位再返回（原实现直接 return 不置位）。
                markTerminal(terminalSeen);
                return;
            }
            // #721：Anthropic SSE 事件行独立成块（"event: message_stop"），必须先于 readTree
            // 识别，否则解析失败被 catch 吞掉、终止标记丢失。
            if (chunk.contains("event: message_stop")) {
                markTerminal(terminalSeen);
            }
            JsonNode jsonNode = objectMapper.readTree(jsonPart);
            // #721：Anthropic 事件 data 形态 {"type":"message_stop"}
            if ("message_stop".equals(jsonNode.path("type").asText())) {
                markTerminal(terminalSeen);
            }
            if (jsonNode.has("choices")) {
                JsonNode choices = jsonNode.get("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                        finishReasonRef.set(choice.get("finish_reason").asText());
                        // #721：finish_reason 到达即终止
                        markTerminal(terminalSeen);
                    }
                }
            }
        } catch (Exception e) {
            logger.trace("Failed to extract finish_reason from chunk: {}", e.getMessage());
        }
    }

    private static void markTerminal(final AtomicBoolean terminalSeen) {
        if (terminalSeen != null) {
            terminalSeen.set(true);
        }
    }

    /**
     * v2.9.10: 写入流式响应缓存.
     *
     * <p>仅在完整成功流（doOnComplete 正常触发）时调用；
     * 键为空或数据不完整时不写。
     *
     * @param cacheKey         缓存键
     * @param cachedResponse   流式缓存值
     */
    void cacheStreamingResponse(final String cacheKey, final CachedStreamingResponse cachedResponse) {
        if (cacheKey == null || cachedResponse == null || responseCacheService == null) {
            return;
        }
        responseCacheService.store(cacheKey, cachedResponse);
    }

    // ==================== v2.9.2: 记录治理辅助方法 ====================

    /**
     * 解析当前记录级别（默认 METADATA_ONLY）
     */
    private RecordLevel resolveRecordLevel() {
        if (callHistoryProperties != null) {
            return callHistoryProperties.getRecordLevel();
        }
        return RecordLevel.METADATA_ONLY;
    }

    /**
     * 将对象序列化为 JSON 字符串并截断到 maxContentLength
     */
    private String serializeAndTruncate(final Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(obj);
            return truncate(json);
        } catch (Exception e) {
            logger.debug("序列化请求体失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 截断字符串到 maxContentLength
     */
    private String truncate(final String content) {
        if (content == null) {
            return null;
        }
        int maxLen = callHistoryProperties != null ? callHistoryProperties.getMaxContentLength() : 65536;
        if (content.length() > maxLen) {
            return content.substring(0, maxLen);
        }
        return content;
    }
}