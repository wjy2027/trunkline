package org.unreal.modelrouter.router.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.router.adapter.handler.MultipartRequestHandler;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.adapter.processor.FallbackRequestProcessor;
import org.unreal.modelrouter.monitor.monitoring.error.ErrorTracker;
import org.unreal.modelrouter.router.adapter.processor.StreamingRequestProcessor;
import org.unreal.modelrouter.router.adapter.retry.RetryPolicy;
import org.unreal.modelrouter.router.adapter.support.AdapterContext;
import org.unreal.modelrouter.router.adapter.support.RequestProcessingSupport;
import org.unreal.modelrouter.router.adapter.support.ResilienceSupport;
import org.unreal.modelrouter.router.adapter.util.ModelUtils;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.dto.EmbeddingDTO;
import org.unreal.modelrouter.common.dto.ImageEditDTO;
import org.unreal.modelrouter.common.dto.ImageGenerateDTO;
import org.unreal.modelrouter.common.dto.RerankDTO;
import org.unreal.modelrouter.common.dto.SttDTO;
import org.unreal.modelrouter.common.dto.TtsDTO;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.enterprise.spi.OutboundContentInspector;
import org.unreal.modelrouter.enterprise.spi.ToolGovernanceProvider;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;
import org.unreal.modelrouter.router.fallback.impl.CacheFallbackStrategy;
import org.unreal.modelrouter.auth.security.quota.QuotaReservation;
import org.unreal.modelrouter.router.loadbalancer.SelectedInstanceHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * BaseAdapter - v2.28.0 重构版
 * 使用聚合组件减少构造函数参数：16 参数 → 3 参数。
 * 所有请求处理逻辑已完全委托给专门组件。
 */
public abstract class BaseAdapter implements ServiceCapability {

    private final AdapterContext context;
    private final RequestProcessingSupport requestSupport;
    private final ResilienceSupport resilienceSupport;
    protected final ObjectMapper objectMapper;
    private final Logger logger = LoggerFactory.getLogger(BaseAdapter.class);

    /**
     * C-06：非幂等 POST 重试的幂等键，挂在 {@link ServerHttpRequest} 属性上。
     * 同一逻辑请求（含其全部重试/实例故障转移）共用一个键，使下游可据此去重，
     * 避免"实例切换/超时重发"导致同一笔模型调用被重复生成、重复计费。
     * 键由 {@link #processRequest} 在入口处生成一次；未进入网关（httpRequest 为空）时不注入。
     */
    public static final String IDEMPOTENCY_KEY_ATTRIBUTE = "TRUNKLINE_IDEMPOTENCY_KEY";

    @Autowired
    private MultipartRequestHandler multipartRequestHandler;
    @Autowired(required = false)
    private FallbackRequestProcessor fallbackRequestProcessor;
    @Autowired(required = false)
    private ErrorTracker errorTracker;
    /**
     * 内容审核扩展点（O7-c）：Noop 恒注册（全放行），判空仅为防御。
     * 企业版覆盖后按租户/模型策略判定；拦截与否只看返回值（SPI 约定：实现不得抛异常）。
     */
    @Autowired(required = false)
    private ModerationProvider moderationProvider;

    /**
     * 出站内容防泄露扩展点（B2）：Noop 恒注册（全放行），判空仅为防御。
     * 与 O7-c 同一挂载点（实例选择之前、调用上游之前）；失败语义与 O7-c 相反——
     * 实现抛异常由主干兜底 fail-closed 拒绝出站（{@code DLP_BLOCKED}）。
     */
    @Autowired(required = false)
    private OutboundContentInspector outboundContentInspector;

    /**
     * Agent 工具治理扩展点（M4）：Noop 恒注册（全放行），判空仅为防御。
     * 挂在出站 DLP 之后（实例选择之前）——对工具定义（{@code tools[]}）与工具结果
     * 回传（{@code role:"tool"} wire 消息）做白名单 / 授权 / 复检治理；失败语义与
     * B2 同款 fail-closed（实现抛异常由主干兜底，{@code TOOL_BLOCKED}）。
     */
    @Autowired(required = false)
    private ToolGovernanceProvider toolGovernanceProvider;

    /**
     * 新构造函数 - 使用聚合组件（推荐）。
     * @param context 核心上下文
     * @param requestSupport 请求处理支持
     * @param resilienceSupport 弹性支持
     */
    @Autowired
    public BaseAdapter(final AdapterContext context,
                       final RequestProcessingSupport requestSupport,
                       final ResilienceSupport resilienceSupport) {
        this.context = context;
        this.requestSupport = requestSupport;
        this.resilienceSupport = resilienceSupport;
        this.objectMapper = context.getObjectMapper();
    }

    public void setErrorTracker(final ErrorTracker errorTracker) {
        this.errorTracker = errorTracker;
    }

    // ==================== 核心方法 ====================

    private String classifyError(final Throwable throwable) {
        return resilienceSupport.getErrorHandler().classifyError(throwable);
    }

    public ModelServiceRegistry getRegistry() { return context.getRegistry(); }

    protected RetryPolicy getRetryPolicy() { return resilienceSupport.getRetryPolicy(); }

    /**
     * 根据实例 baseUrl 获取 WebClient（不重新选择实例）。
     * 用于故障转移重选实例后获取新实例的 WebClient。
     */
    @SuppressWarnings("all")
    private WebClient getWebClientForInstance(final ModelRouterProperties.ModelInstance instance) {
        String baseUrl = instance.getBaseUrl();
        try {
            var tracingFactory = org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory.class);
            return tracingFactory.createTracingWebClient(baseUrl);
        } catch (Exception e) {
            return org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.router.model.WebClientCacheManager.class)
                    .getOrCreate(baseUrl);
        }
    }

    protected Mono<ResponseEntity<String>> checkCapability(final ModelServiceRegistry.ServiceType serviceType) {
        return resilienceSupport.getCapabilityChecker().checkCapability(supportCapability(), serviceType);
    }

    // ==================== 请求处理模板方法 ====================

    /**
     * C-06：在同一逻辑请求上生成/复用幂等键（首次调用生成，其后的重试与实例故障转移复用）。
     *
     * <p>挂在 {@link ServerHttpRequest} 属性上，因为重试链（{@code processRequestWithRetry}
     * 递归）与 processor lambda 捕获的是同一个 httpRequest 对象，键可跨重试保持稳定。
     * 下游据此对"已处理但响应丢失"的重复投递去重，避免重复生成/重复计费。
     * httpRequest 为空或未带属性表时静默跳过（保持既有可测性与容错）。</p>
     *
     * @param httpRequest 当前 HTTP 请求，可为 null
     * @return 稳定的幂等键；无法挂载时返回 null
     */
    static String ensureIdempotencyKey(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        try {
            Map<String, Object> attributes = httpRequest.getAttributes();
            if (attributes == null) {
                return null;
            }
            Object existing = attributes.get(IDEMPOTENCY_KEY_ATTRIBUTE);
            if (existing instanceof String key && !key.isEmpty()) {
                return key;
            }
            String key = UUID.randomUUID().toString();
            attributes.put(IDEMPOTENCY_KEY_ATTRIBUTE, key);
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("all")
    protected <T> Mono processRequest(final T request, final String authorization,
            final ServerHttpRequest httpRequest, final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final RequestProcessor<T> processor) {
        // C-06：入口处生成一次幂等键，贯穿本次请求的所有重试/故障转移分支。
        ensureIdempotencyKey(httpRequest);
        // 仅 chat 走内容检查链（O7-c 第一版只接 ChatDTO.Request，占比最高、字段最规整；
        // embedding/image 等按 SOW 再扩）。挂载在实例选择之前——被拦截的请求
        // 不应消耗负载均衡状态与实例级限流令牌。拦截时上游零调用。
        if (request instanceof ChatDTO.Request chat) {
            if (moderationProvider != null) {
                return Mono.fromCallable(() -> moderationProvider.check(new ModerationProvider.ModerationInput(
                                modelName, extractChatText(chat), chat.user(),
                                TenantContextHolder.currentTenantId())))
                        // 统一线程模型（v1.2）：审核可能涉及外部引擎 IO，BaseAdapter 也会被
                        // 非主链路径调用，不能假设当前线程——一律切弹性线程。
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(decision -> decision.allowed()
                                ? applyOutboundInspection(chat, authorization, httpRequest, serviceType,
                                        modelName, processor)
                                : Mono.error(ApiException.of("CONTENT_BLOCKED",
                                        decision.reason() != null ? decision.reason() : "内容未通过审核",
                                        org.springframework.http.HttpStatus.BAD_REQUEST)));
            }
            return applyOutboundInspection(chat, authorization, httpRequest, serviceType,
                    modelName, processor);
        }
        return doProcessRequest(request, authorization, httpRequest, serviceType, modelName, processor);
    }

    /**
     * B2 出站 DLP 检查（O7-c 审核放行后执行，实例选择之前）。
     *
     * <p>判定语汇三态：放行（原样出站）/ 脱敏（替换消息文本后出站）/ 阻断（{@code DLP_BLOCKED}）。
     * <b>fail-closed 三重保险</b>：① SPI 实现抛异常 → 拒绝出站；② 返回 {@code null} 判定 →
     * 拒绝出站；③ SANITIZE 返回的脱敏列表与输入消息列表不等长（契约违约）→ 拒绝出站。
     * 防泄露的失败模式必须是「拒绝」而不是「放行」。</p>
     *
     * <p>SSE / 流式不受影响：本检查只作用于请求侧的结构化消息文本（已是完整对象，
     * 无整体缓冲问题）；响应侧流式审核属 B5-out（{@code openOutputSession}），互不相干。</p>
     */
    @SuppressWarnings({"all", "unchecked", "rawtypes"})
    private Mono applyOutboundInspection(final ChatDTO.Request chat, final String authorization,
            final ServerHttpRequest httpRequest, final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final RequestProcessor processor) {
        if (outboundContentInspector == null) {
            return applyToolGovernance(chat, authorization, httpRequest, serviceType, modelName, processor);
        }
        return Mono.fromCallable(() -> outboundContentInspector.inspect(new OutboundContentInspector.InspectionInput(
                        modelName, chatMessageTexts(chat), chat.user(),
                        TenantContextHolder.currentTenantId())))
                // ② null 判定按 fail-closed 处理：fromCallable 返回 null 会产生空 Mono，
                // 不进下游 flatMap，必须在源头用 switchIfEmpty 兜住。
                .switchIfEmpty(Mono.error(ApiException.of("DLP_BLOCKED",
                        "出站内容检查返回无效判定，请求已被数据防泄露策略拒绝",
                        org.springframework.http.HttpStatus.BAD_REQUEST)))
                .subscribeOn(Schedulers.boundedElastic())
                // ① 实现抛异常一律拒绝出站（fail-closed）：异常消息可能含敏感片段，不透传原文
                .onErrorResume(throwable -> Mono.error(ApiException.of("DLP_BLOCKED",
                        "出站内容检查失败，请求已被数据防泄露策略拒绝",
                        org.springframework.http.HttpStatus.BAD_REQUEST)))
                .flatMap(decision -> {
                    // ② null 判定按 fail-closed 处理
                    if (decision == null || decision.verdict() == null) {
                        return Mono.error(ApiException.of("DLP_BLOCKED",
                                "出站内容检查返回无效判定，请求已被数据防泄露策略拒绝",
                                org.springframework.http.HttpStatus.BAD_REQUEST));
                    }
                    if (decision.verdict() == OutboundContentInspector.Verdict.BLOCK) {
                        return Mono.error(ApiException.of("DLP_BLOCKED",
                                decision.reason() != null ? decision.reason() : "出站内容未通过数据防泄露检查",
                                org.springframework.http.HttpStatus.BAD_REQUEST));
                    }
                    if (decision.verdict() == OutboundContentInspector.Verdict.SANITIZE) {
                        // ③ 脱敏契约违约（列表缺失 / 不等长）按 fail-closed 处理
                        final ChatDTO.Request sanitized = sanitizeChatRequest(chat, decision.sanitizedMessages());
                        if (sanitized == null) {
                            return Mono.error(ApiException.of("DLP_BLOCKED",
                                    "出站内容检查返回非法脱敏结果，请求已被数据防泄露策略拒绝",
                                    org.springframework.http.HttpStatus.BAD_REQUEST));
                        }
                        return applyToolGovernance(sanitized, authorization, httpRequest, serviceType,
                                modelName, processor);
                    }
                    return applyToolGovernance(chat, authorization, httpRequest, serviceType,
                            modelName, processor);
                });
    }

    /**
     * M4 Agent 工具治理检查（出站 DLP 放行后执行，实例选择之前）。
     *
     * <p>治理对象：Anthropic {@code /v1/messages} 透传链路上的工具定义
     * （{@code options.tools}）与工具结果回传（{@code wire_messages} 中
     * {@code role:"tool"} 消息文本）。纯文本请求（无工具且无回传）零开销直通。</p>
     *
     * <p><b>fail-closed 三重保险</b>（与 B2 同款）：① SPI 实现抛异常 → 拒绝出站；
     * ② 返回 {@code null} 判定 → 拒绝出站；③ STRIP 时主干剥离失败（契约违约）→
     * 拒绝出站。判定语汇三态：放行（原样出站）/ 剥离（按 {@code allowedTools} 替换
     * 工具定义，空列表降级纯文本对话）/ 阻断（{@code TOOL_BLOCKED}）。</p>
     */
    @SuppressWarnings({"all", "unchecked", "rawtypes"})
    private Mono applyToolGovernance(final ChatDTO.Request chat, final String authorization,
            final ServerHttpRequest httpRequest, final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final RequestProcessor processor) {
        final ChatDTO.Options options = chat.options();
        final java.util.List<Map<String, Object>> tools =
                options != null && options.getTools() != null ? options.getTools() : java.util.List.of();
        final java.util.List<String> toolResultTexts =
                extractToolResultTexts(options != null ? options.getWireMessages() : null);
        if (tools.isEmpty() && toolResultTexts.isEmpty()) {
            // 纯文本请求（无工具定义且无工具结果回传）：零开销直通
            return doProcessRequest(chat, authorization, httpRequest, serviceType, modelName, processor);
        }
        if (toolGovernanceProvider == null) {
            return doProcessRequest(chat, authorization, httpRequest, serviceType, modelName, processor);
        }
        return Mono.fromCallable(() -> toolGovernanceProvider.govern(new ToolGovernanceProvider.GovernanceInput(
                        modelName, tools, toolResultTexts, extractToolNames(tools), chat.user(),
                        TenantContextHolder.currentTenantId())))
                .switchIfEmpty(Mono.error(ApiException.of("TOOL_BLOCKED",
                        "工具治理检查返回无效判定，请求已被 Agent 工具治理策略拒绝",
                        org.springframework.http.HttpStatus.BAD_REQUEST)))
                .subscribeOn(Schedulers.boundedElastic())
                // ① 实现抛异常一律拒绝出站（fail-closed）：异常消息可能含敏感片段，不透传原文
                .onErrorResume(throwable -> Mono.error(ApiException.of("TOOL_BLOCKED",
                        "工具治理检查失败，请求已被 Agent 工具治理策略拒绝",
                        org.springframework.http.HttpStatus.BAD_REQUEST)))
                .flatMap(decision -> {
                    // ② null 判定按 fail-closed 处理
                    if (decision == null || decision.verdict() == null) {
                        return Mono.error(ApiException.of("TOOL_BLOCKED",
                                "工具治理检查返回无效判定，请求已被 Agent 工具治理策略拒绝",
                                org.springframework.http.HttpStatus.BAD_REQUEST));
                    }
                    if (decision.verdict() == ToolGovernanceProvider.Verdict.BLOCK) {
                        return Mono.error(ApiException.of("TOOL_BLOCKED",
                                decision.reason() != null ? decision.reason() : "请求未通过 Agent 工具治理检查",
                                org.springframework.http.HttpStatus.BAD_REQUEST));
                    }
                    if (decision.verdict() == ToolGovernanceProvider.Verdict.STRIP) {
                        // ③ 剥离契约违约（allowedTools 为 null——决定必须走 stripAll/strip 工厂）按 fail-closed 处理
                        final ChatDTO.Request stripped = stripToolDefinitions(chat, decision.allowedTools());
                        if (stripped == null) {
                            return Mono.error(ApiException.of("TOOL_BLOCKED",
                                    "工具治理返回非法剥离结果，请求已被 Agent 工具治理策略拒绝",
                                    org.springframework.http.HttpStatus.BAD_REQUEST));
                        }
                        return doProcessRequest(stripped, authorization, httpRequest, serviceType,
                                modelName, processor);
                    }
                    return doProcessRequest(chat, authorization, httpRequest, serviceType, modelName, processor);
                });
    }

    /**
     * M4：按判定剥离工具定义。{@code allowedTools} 为空 → 全剥离（tools/tool_choice/
     * wire_messages 一并清空，请求降级纯文本对话，下游请求体与无工具请求一致）；
     * 非空 → 仅替换 tools，tool_choice 与工具历史保留。Options 为共享可变对象，
     * 原地修改后重建 Request（record 不可变语义保持不变）。
     *
     * @return 剥离后的请求；{@code allowedTools} 为 {@code null}（契约违约）时返回 {@code null}
     */
    static ChatDTO.Request stripToolDefinitions(final ChatDTO.Request chat,
            final java.util.List<Map<String, Object>> allowedTools) {
        if (chat == null || chat.options() == null) {
            return chat;
        }
        final ChatDTO.Options options = chat.options();
        if (allowedTools == null) {
            return null;
        }
        if (allowedTools.isEmpty()) {
            options.setTools(null);
            options.setToolChoice(null);
            options.setWireMessages(null);
        } else {
            options.setTools(new java.util.ArrayList<>(allowedTools));
        }
        return new ChatDTO.Request(chat.model(), chat.messages(), chat.stream(), chat.maxTokens(),
                chat.temperature(), chat.topP(), chat.topK(), chat.frequencyPenalty(),
                chat.presencePenalty(), chat.stop(), chat.user(), options);
    }

    /**
     * M4：从 wire 消息中提取工具结果回传文本（{@code role:"tool"} 的 {@code content}）。
     * content 支持 String 与分段数组（{@code [{type:"text",text:...}]}）两种形状；
     * 非 tool 角色消息一律跳过。提取文本仅用于治理检查，禁止写日志。
     */
    static java.util.List<String> extractToolResultTexts(final java.util.List<Map<String, Object>> wireMessages) {
        if (wireMessages == null || wireMessages.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<String> texts = new java.util.ArrayList<>();
        for (final Map<String, Object> message : wireMessages) {
            if (message == null || !"tool".equals(message.get("role"))) {
                continue;
            }
            final Object content = message.get("content");
            if (content instanceof String s) {
                if (!s.isBlank()) {
                    texts.add(s);
                }
            } else if (content instanceof java.util.List<?> segments) {
                final StringBuilder sb = new StringBuilder();
                for (final Object segment : segments) {
                    if (segment instanceof Map<?, ?> m && m.get("text") instanceof String s && !s.isBlank()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(s);
                    }
                }
                if (sb.length() > 0) {
                    texts.add(sb.toString());
                }
            }
        }
        return java.util.List.copyOf(texts);
    }

    /**
     * M4：从工具定义列表提取工具名（OpenAI wire 形状 {@code function.name}）。
     * 与 {@code tools} 顺序一一对应；形状非法的位置为 {@code null}（由实现按契约处置）。
     */
    static java.util.List<String> extractToolNames(final java.util.List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<String> names = new java.util.ArrayList<>(tools.size());
        for (final Map<String, Object> tool : tools) {
            Object name = null;
            if (tool != null && tool.get("function") instanceof Map<?, ?> function) {
                name = function.get("name");
            }
            names.add(name instanceof String s ? s : null);
        }
        // 注意：List.copyOf 不允许 null 元素，而非法形状位置必须保留 null 占位
        return java.util.Collections.unmodifiableList(names);
    }

    /**
     * O7-c：提取 chat 请求的全部消息文本（按消息顺序换行拼接），供内容审核。
     * 空 messages / 空 content 安全返回空串。
     */
    static String extractChatText(final ChatDTO.Request chat) {
        if (chat == null || chat.messages() == null || chat.messages().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final ChatDTO.Message message : chat.messages()) {
            if (message == null || message.content() == null || message.content().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(message.content());
        }
        return sb.toString();
    }

    /**
     * B2：提取逐条消息文本（与请求消息顺序一一对应），供出站 DLP 检查。
     * 与 {@link #extractChatText} 不同：不做拼接，保留逐条边界以便脱敏后精确回填。
     */
    static java.util.List<String> chatMessageTexts(final ChatDTO.Request chat) {
        if (chat == null || chat.messages() == null) {
            return java.util.List.of();
        }
        final java.util.List<String> texts = new java.util.ArrayList<>(chat.messages().size());
        for (final ChatDTO.Message message : chat.messages()) {
            texts.add(message == null ? null : message.content());
        }
        return texts;
    }

    /**
     * B2：按脱敏列表重建 chat 请求（逐条替换 content，role / name 与其余参数保留）。
     * 脱敏列表与消息列表不等长（或输入缺失）时返回 {@code null}——由调用方按
     * fail-closed 处理，绝不部分替换后放行。
     */
    static ChatDTO.Request sanitizeChatRequest(final ChatDTO.Request chat,
            final java.util.List<String> sanitizedMessages) {
        if (chat == null || chat.messages() == null || sanitizedMessages == null
                || sanitizedMessages.size() != chat.messages().size()) {
            return null;
        }
        final java.util.List<ChatDTO.Message> sanitized = new java.util.ArrayList<>(chat.messages().size());
        for (int i = 0; i < chat.messages().size(); i++) {
            final ChatDTO.Message original = chat.messages().get(i);
            final String replacement = sanitizedMessages.get(i);
            if (original == null) {
                sanitized.add(null);
                continue;
            }
            sanitized.add(new ChatDTO.Message(original.role(), replacement, original.name()));
        }
        return new ChatDTO.Request(chat.model(), java.util.List.copyOf(sanitized), chat.stream(),
                chat.maxTokens(), chat.temperature(), chat.topP(), chat.topK(),
                chat.frequencyPenalty(), chat.presencePenalty(), chat.stop(),
                chat.user(), chat.options());
    }

    /** {@link #processRequest} 的原有主体（审核放行后执行）。 */
    @SuppressWarnings("all")
    private <T> Mono doProcessRequest(final T request, final String authorization,
            final ServerHttpRequest httpRequest, final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final RequestProcessor<T> processor) {
        // 优先复用网关（ServiceRequestHandler）已选中的实例：每个请求只应发生一次实例选择，
        // 否则会重复推进负载均衡状态、重复扣减实例级限流令牌，并让路由监控一次请求 +多次计数。
        // 未经网关的调用（持有者为空）回退为自行选择，保持既有行为。
        ModelRouterProperties.ModelInstance selectedInstance = SelectedInstanceHolder.get();
        if (selectedInstance == null) {
            selectedInstance = selectInstance(serviceType, modelName, IpUtils.getClientIp(httpRequest));
        }
        // 用已选中的实例构造 WebClient：客户端目标与指标归属实例保持一致
        WebClient client = getWebClientForInstance(selectedInstance);
        // 池路由 path 兜底：registry.getModelPath 的池回退依赖租户上下文（PoolSelector 按租户隔离池），
        // 而这里可能运行在 boundedElastic 线程上，ThreadLocal 租户上下文不可用，按池名回退查找会落空
        // 返回 ""，转发打到上游 baseUrl 根路径（上游 404 → 触发降级 503）。已选中的实例就是本次
        // 转发的目标，其自带 path 与请求模型名无关，直接兜底使用。
        String path = resolveForwardPath(getModelPath(serviceType, modelName), selectedInstance);
        long startTime = System.currentTimeMillis();
        String adapterType = getAdapterType();
        String modelNameFromRequest = ModelUtils.getModelNameFromRequest(request);
        resilienceSupport.getTracingManager()
                .recordCallStart(adapterType, selectedInstance, serviceType, modelNameFromRequest);
        return processRequestWithRetry(request, authorization, client, path, selectedInstance,
                serviceType, modelNameFromRequest, processor, startTime, 0,
                new HashSet<>());
    }

    /**
     * 实例键生成：与 LoadBalancer 的实例标识保持一致
     */
    private String instanceKey(final ModelRouterProperties.ModelInstance instance) {
        return instance.getBaseUrl() + ":" + instance.getPath();
    }

    /**
     * v2.9.6: 尝试故障转移重选实例，排除已失败的实例。
     * 如果找不到新的可用实例，返回当前实例（保留原有重试行为作为兜底）。
     *
     * @param currentInstance 当前失败的实例
     * @param serviceType 服务类型
     * @param modelName 模型名称
     * @param failedKeys 已失败的实例键集合
     * @return 重选的实例（可能与 currentInstance 相同，表示无可用替代）
     */
    private ModelRouterProperties.ModelInstance tryReselectInstance(
            final ModelRouterProperties.ModelInstance currentInstance,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final Set<String> failedKeys) {
        // 最大尝试次数 = 已失败数 + 2（留余量，避免死循环）
        int maxAttempts = failedKeys.size() + 2;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                ModelRouterProperties.ModelInstance candidate =
                        getRegistry().selectInstance(serviceType, modelName, null);
                if (candidate != null && !failedKeys.contains(instanceKey(candidate))) {
                    return candidate;
                }
                // 候选已在失败列表中或为空，继续尝试
            } catch (Exception e) {
                // 选择异常（如无可用实例），跳出循环
                logger.debug("故障转移重选实例失败: {}", e.getMessage());
                break;
            }
        }
        return currentInstance;
    }

    @SuppressWarnings("all")
    private <T> Mono processRequestWithRetry(final T request, final String authorization,
            final WebClient client, final String path, final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType, final String modelName,
            final RequestProcessor<T> processor, final long startTime, final int retryCount,
            final Set<String> failedKeys) {
        String adapterType = getAdapterType();
        String instanceName = selectedInstance.getName();
        RetryPolicy retryPolicy = resilienceSupport.getRetryPolicy();
        int maxRetries = retryPolicy.getMaxRetriesByServiceType(serviceType);
        return processor.process(request, authorization, client, path, selectedInstance, serviceType)
                .doOnSuccess(response -> {
                    long duration = System.currentTimeMillis() - startTime;
                    boolean success = response != null && response.getStatusCode().is2xxSuccessful();
                    AdapterMetricsRecorder metricsRecorder = resilienceSupport.getMetricsRecorder();
                    if (metricsRecorder != null) {
                        // v2.9.2: 成功路径的调用历史由 RequestProcessor 层记录（含请求/响应体），此处仅更新统计，避免重复记录
                        metricsRecorder.recordCompleteCallStats(adapterType, instanceName, duration, success,
                                null, modelName, serviceType, selectedInstance);
                    }
                    resilienceSupport.getTracingManager().recordCallComplete(adapterType, selectedInstance, serviceType,
                            ModelUtils.getModelNameFromRequest(request), duration, success);
                })
                .onErrorResume(throwable -> {
                    long duration = System.currentTimeMillis() - startTime;
                    String errorCode = classifyError(throwable);
                    AdapterMetricsRecorder metricsRecorder = resilienceSupport.getMetricsRecorder();
                    if (metricsRecorder != null) {
                        if (RetryPolicy.isClientSide4xx(throwable)) {
                            // #647: 客户端侧 4xx 是请求本身的问题，不得计入实例熔断失败。
                            // 分类直接取异常状态码——classifyError 把下游异常一律归 "503" 会掩盖 4xx；
                            // 408（超时）/429（限流）不在此列，按失败计熔断（与重试侧口径一致）。
                            metricsRecorder.recordClientErrorCompleteCall(adapterType, instanceName, duration,
                                    String.valueOf(RetryPolicy.statusOf(throwable)),
                                    modelName, serviceType, selectedInstance);
                        } else {
                            metricsRecorder.recordCompleteCall(adapterType, instanceName, duration, false,
                                    errorCode, modelName, serviceType, selectedInstance);
                        }
                    }
                    // C-06：用按服务类型收敛后的实际预算（recordRetry 也用它），
                    // 与 RetryPolicy 字段里的全局默认值解耦，避免"声明重试 1 次、实际重试 3 次"。
                    if (retryPolicy.canRetry(retryCount, throwable, maxRetries)
                            && retryPolicy.isRetryable(throwable)) {
                        resilienceSupport.getTracingManager()
                                .recordRetry(adapterType, selectedInstance, retryCount + 1, maxRetries, throwable);
                        if (metricsRecorder != null) {
                            metricsRecorder.recordRetry(adapterType, instanceName, retryCount + 1, throwable);
                        }
                        long retryDelay = retryPolicy.calculateRetryDelay(retryCount);
                        // #719：可重试分支绝不结算预留——错误在下次 attempt 重试，预留归属未定；
                        // 结算收口在链末端（handler doOnError/doOnCancel 与降级入口）。
                        // v2.9.6: 请求级故障转移 — 重选实例排除已失败实例
                        failedKeys.add(instanceKey(selectedInstance));
                        ModelRouterProperties.ModelInstance nextInstance =
                                tryReselectInstance(selectedInstance, serviceType, modelName, failedKeys);
                        // 使用新实例时需要获取新的 WebClient
                        WebClient nextClient = (nextInstance != selectedInstance)
                                ? getWebClientForInstance(nextInstance) : client;
                        String nextPath = (nextInstance != selectedInstance)
                                ? resolveForwardPath(getModelPath(serviceType, modelName), nextInstance) : path;
                        return Mono.delay(Duration.ofMillis(retryDelay))
                                .then(processRequestWithRetry(request, authorization, nextClient, nextPath,
                                        nextInstance, serviceType, modelName, processor,
                                        System.currentTimeMillis(), retryCount + 1, failedKeys));
                    }
                    if (errorTracker != null) {
                        Map<String, Object> errorContext = new HashMap<>();
                        errorContext.put("modelName", modelName);
                        errorContext.put("serviceType", serviceType != null ? serviceType.name() : null);
                        errorContext.put("provider", adapterType);
                        errorContext.put("instanceName", instanceName);
                        errorContext.put("responseTimeMs", duration);
                        errorContext.put("errorCode", errorCode);
                        errorContext.put("retryCount", retryCount);
                        errorTracker.trackError(throwable, adapterType + ".request", errorContext);
                    }
                    return resilienceSupport.getErrorResponseBuilder()
                            .buildErrorResponse(throwable).flatMap(Mono::error);
                });
    }

    @SuppressWarnings({"all", "unchecked"})
    protected <T> Mono<? extends ResponseEntity<?>> processRequestWithFallback(final T request,
            final String authorization, final ServerHttpRequest httpRequest,
            final ModelServiceRegistry.ServiceType serviceType, final String modelName,
            final RequestProcessor<T> processor) {
        ModelRouterProperties.ServiceConfig serviceConfig = getRegistry().getServiceConfig(serviceType);
        FallbackStrategy<ResponseEntity<?>> fallbackStrategy =
                getRegistry().getFallbackManager().getFallbackStrategy(serviceType.name(), serviceConfig);
        if (fallbackStrategy == null) {
            return processRequest(request, authorization, httpRequest, serviceType, modelName, processor);
        }
        Mono<ResponseEntity<?>> rawMono = (Mono<ResponseEntity<?>>) (Mono) processRequest(
                request, authorization, httpRequest, serviceType, modelName, processor);
        // M-24：解析本次请求的降级键，写入侧(CacheFallbackStrategy.cacheResponse)与读取侧
        // (fallback(cause, requestKey))共用同一权威来源，实现请求级隔离、杜绝跨请求串扰。
        String requestKey = CacheFallbackStrategy.resolveRequestKey(serviceType, modelName, httpRequest);
        // M-24：把写入桥接上——此前 BaseAdapter.cacheSuccessfulResponse 定义了却从未被调用，
        // 导致 strategy:cache 的内置缓存恒空、降级永远 503（形同虚设）。仅在 2xx 成功且
        // 策略确为缓存型时写入；写入键与读取键同为 requestKey，天然请求级隔离。
        Mono<ResponseEntity<?>> requestMono = rawMono.doOnSuccess(response -> {
            // #892：流式响应（Publisher body / SSE）是一次性流，写入降级缓存后重放
            // 会得到空/报错/串味的"一次性 Flux"。流式自身的 chunks 缓存由
            // StreamingRequestProcessor 负责（含 #721 终止标记校验），此处只放行非流式。
            if (response != null && response.getStatusCode().is2xxSuccessful()
                    && !isStreamingResponse(response)) {
                cacheSuccessfulResponse(serviceType, modelName, response, httpRequest);
            }
        });
        if (fallbackRequestProcessor != null) {
            return requestMono.onErrorResume(throwable -> {
                // #719：进入降级 = 重试耗尽后的终端处置（此后不会再有 attempt），先结算失败。
                // 缓存命中降级同理——降级响应不消耗配额（与 handler「缓存命中不消耗配额」同口径）。
                // CAS 恰一次：降级若再抛错，handler 的 doOnError 结算会被去重。
                QuotaReservation.settleFailure(QuotaReservation.from(httpRequest));
                if (errorTracker != null) {
                    String adapterType = getAdapterType();
                    Map<String, Object> errorContext = new HashMap<>();
                    errorContext.put("modelName", modelName);
                    errorContext.put("serviceType", serviceType != null ? serviceType.name() : null);
                    errorContext.put("provider", adapterType);
                    errorTracker.trackError(throwable, adapterType + ".fallback", errorContext);
                }
                return fallbackRequestProcessor.handleFallbackError(throwable, fallbackStrategy, requestKey);
            });
        }
        return requestMono.onErrorResume(throwable -> {
            // #719：降级入口终端结算（同上）。
            QuotaReservation.settleFailure(QuotaReservation.from(httpRequest));
            if (errorTracker != null) {
                String adapterType = getAdapterType();
                Map<String, Object> errorContext = new HashMap<>();
                errorContext.put("modelName", modelName);
                errorContext.put("serviceType", serviceType != null ? serviceType.name() : null);
                errorContext.put("provider", adapterType);
                errorTracker.trackError(throwable, adapterType + ".fallback", errorContext);
            }
            return resolveFallbackResponse(throwable, fallbackStrategy, requestKey);
        });
    }

    /**
     * 将一次降级求值封装为响应 Mono（问题清单 m-09 / M-19）。
     *
     * <p>原实现用三元式 {@code fallback(x) != null ? Mono.just(fallback(x)) : ...}，
     * 同一异常会把 {@code fallback()} 连调两遍（有副作用/缓存读取时结果可能不一致）；
     * 且把 {@code Throwable} 直接强转为 {@code Exception}，遇到 {@code Error} 会抛 ClassCastException。
     *
     * @param throwable       触发降级的错误
     * @param fallbackStrategy 降级策略
     * @return 降级响应；无可用降级时返回 503 错误 Mono
     */
    static Mono<ResponseEntity<?>> resolveFallbackResponse(
            final Throwable throwable, final FallbackStrategy<ResponseEntity<?>> fallbackStrategy) {
        return resolveFallbackResponse(throwable, fallbackStrategy, null);
    }

    /**
     * M-24：带请求键的降级求值——把请求维度传入策略，缓存降级只返回本次请求的缓存结果。
     *
     * <p>无状态策略（DefaultFallbackStrategy）走接口默认方法忽略 requestKey，行为不变；
     * 缓存策略（CacheFallbackStrategy）据此精确命中，未命中返回 503 而非任意缓存。</p>
     */
    static Mono<ResponseEntity<?>> resolveFallbackResponse(
            final Throwable throwable, final FallbackStrategy<ResponseEntity<?>> fallbackStrategy,
            final String requestKey) {
        Exception cause = throwable instanceof Exception
            ? (Exception) throwable
            : new IllegalStateException("请求失败", throwable);
        // requestKey 为空（无法定位请求维度/无状态策略/兼容旧调用）时走两参重载，
        // 既保持既有降级语义，也兼容只 stub 了两参 fallback 的调用方与测试。
        ResponseEntity<?> fallbackResponse = requestKey == null
            ? fallbackStrategy.fallback(cause)
            : fallbackStrategy.fallback(cause, requestKey); // 只求值一次
        return fallbackResponse != null
            ? Mono.just(fallbackResponse)
            : Mono.error(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "服务降级且无缓存"));
    }

    // ==================== 非流式请求处理 ====================

    protected <T> Mono<? extends ResponseEntity<?>> processNonStreamingRequest(final T request,
            final String authorization, final WebClient client, final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType, final Class<?> responseType,
            final ServerHttpRequest httpRequest) {
        String adapterType = getAdapterType();
        String finalPath = adaptModelName(path);
        String finalAuth = getAuthorizationHeader(adaptModelName(authorization), adapterType);
        Function<Object, Object> transformRequestFn = req -> transformRequest(req, adapterType);
        Function<Object, Object> transformResponseFn = data -> transformResponse(data, adapterType);

        // 延迟获取 multipartRequestHandler（如果尚未注入）
        MultipartRequestHandler handler = this.multipartRequestHandler;
        if (handler == null) {
            try {
                handler = org.unreal.modelrouter.common.util.ApplicationContextProvider
                        .getBean(MultipartRequestHandler.class);
                this.multipartRequestHandler = handler; // 缓存以供后续使用
                logger.debug("从 ApplicationContext 获取 MultipartRequestHandler 成功");
            } catch (Exception e) {
                logger.warn("无法获取 MultipartRequestHandler: {}", e.getMessage());
            }
        }

        return requestSupport.getNonStreamingProcessor().processRequest(request, finalAuth, client, finalPath,
                selectedInstance, serviceType, responseType, adapterType,
                transformRequestFn, transformResponseFn, handler, httpRequest, getAdditionalHeaders());
    }

    // ==================== 流式请求处理 ====================

    protected <T> Mono<? extends ResponseEntity<?>> processStreamingRequest(final T request,
            final String authorization, final WebClient client, final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType,
            final org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {
        StreamingRequestProcessor processor = requestSupport.getStreamingProcessor();
        if (processor == null) {
            logger.error("StreamingRequestProcessor 未注入，无法处理流式请求");
            return Mono.error(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "流式请求处理器未配置"));
        }
        String adapterType = getAdapterType();
        String finalPath = adaptModelName(path);
        String finalAuth = getAuthorizationHeader(adaptModelName(authorization), adapterType);
        Object transformedRequest = transformRequest(request, adapterType);
        return processor.processStreamingRequest(transformedRequest, finalAuth, client,
                finalPath, selectedInstance, serviceType, adapterType, httpRequest);
    }

    // ==================== 抽象方法 ====================

    public abstract AdapterCapabilities supportCapability();
    protected abstract String getAdapterType();

    // ==================== 业务方法入口 ====================

    @SuppressWarnings("all")
    @Override
    public Mono chat(final ChatDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.chat);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.chat, request.model(),
                (req, auth, client, path, instance, st) -> Boolean.TRUE.equals(req.stream())
                        ? processStreamingRequest(req, auth, client, path, instance, st, httpRequest)
                        : processNonStreamingRequest(req, auth, client, path, instance, st, String.class, httpRequest));
    }

    @SuppressWarnings("all")
    @Override
    public Mono embedding(final EmbeddingDTO.Request request, final String authorization,
            final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.embedding);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.embedding, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class, httpRequest));
    }

    @SuppressWarnings("all")
    @Override
    public Mono rerank(final RerankDTO.Request request, final String authorization,
            final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.rerank);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.rerank, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class, httpRequest));
    }

    @SuppressWarnings("all")
    @Override
    public Mono tts(final TtsDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.tts);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.tts, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, byte[].class, httpRequest));
    }

    @SuppressWarnings("all")
    @Override
    public Mono stt(final SttDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.stt);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.stt, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class, httpRequest));
    }

    @SuppressWarnings("all")
    public Mono imageGenerate(final ImageGenerateDTO.Request request, final String authorization,
                              final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.imgGen);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.imgGen, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class, httpRequest));
    }

    @SuppressWarnings("all")
    public Mono imageEdit(final ImageEditDTO.Request request, final String authorization,
                          final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.imgEdit);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.imgEdit, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class, httpRequest));
    }

    // ==================== 子类可重写方法 ====================

    /**
     * 获取适配器特有的额外请求头
     * 子类可重写此方法以添加适配器特定的头（如 Claude 的 anthropic-version）
     *
     * @return 额外请求头映射，空 Map 表示无额外头
     */
    protected Map<String, String> getAdditionalHeaders() {
        return java.util.Collections.emptyMap();
    }

    protected <T> WebClient.RequestBodySpec configureRequestHeaders(
            final WebClient.RequestBodySpec requestSpec, final T request) {
        return multipartRequestHandler.configureRequestHeaders(requestSpec, request);
    }

    protected <T> WebClient.RequestBodySpec configureRequestHeaders(
            final WebClient.RequestBodySpec requestSpec, final T request,
            final ModelRouterProperties.ModelInstance instance) {
        return multipartRequestHandler.configureRequestHeaders(requestSpec, request, instance);
    }

    protected String transformStreamChunk(final String chunk) {
        return requestSupport.getResponseTransformer().transformStreamChunk(chunk);
    }

    protected long calculateRequestSize(final Object request) {
        if (request == null) return 0;
        try { return request.toString().getBytes().length; } catch (Exception e) { return 0; }
    }

    // ==================== 辅助方法（委托） ====================

    protected ModelRouterProperties.ModelInstance selectInstance(
            final ModelServiceRegistry.ServiceType serviceType, final String modelName, final String clientIp) {
        return requestSupport.getInstanceSelector().selectInstance(serviceType, modelName, clientIp);
    }

    protected String getModelPath(final ModelServiceRegistry.ServiceType serviceType, final String modelName) {
        return requestSupport.getInstanceSelector().getModelPath(serviceType, modelName);
    }

    /**
     * 转发 path 兜底解析：registry.getModelPath 解析为空时，回退用实例自带的 path。
     *
     * <p>背景：按池名调用时，registry.getModelPath 的池回退需要租户上下文（PoolSelector 按
     * 租户隔离池），但本调用链在 boundedElastic 线程上没有 ThreadLocal 租户上下文，池查找落空
     * 返回 ""，导致转发打到上游 baseUrl 根路径（上游 404 → 降级 503）。实例选择发生在网关侧
     * （有租户上下文），选中的实例对象本身携带正确 path，与请求模型名无关，可直接兜底。</p>
     *
     * @param path     registry.getModelPath 的解析结果（可能为 null/空）
     * @param instance 本次转发选中的目标实例；为 null 或其 path 也为空时原样返回 path
     * @return 非空的转发 path（兜底后仍为空则原样返回，由下游按既有逻辑处理）
     */
    private String resolveForwardPath(final String path,
            final ModelRouterProperties.ModelInstance instance) {
        if ((path == null || path.isEmpty())
                && instance != null && instance.getPath() != null && !instance.getPath().isEmpty()) {
            return instance.getPath();
        }
        return path;
    }

    protected Object transformRequest(final Object request, final String adapterType) {
        return requestSupport.getResponseTransformer().transformRequest(request, adapterType);
    }

    protected String adaptModelName(final String originalModelName) {
        return requestSupport.getResponseTransformer().adaptModelName(originalModelName);
    }

    protected Object transformResponse(final Object responseData, final String adapterType) {
        return requestSupport.getResponseTransformer().transformResponse(responseData, adapterType);
    }

    protected String getAuthorizationHeader(final String authorization, final String adapterType) {
        return authorization;
    }

    protected void cacheSuccessfulResponse(final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final ResponseEntity<?> response, final ServerHttpRequest httpRequest) {
        ModelRouterProperties.ServiceConfig serviceConfig = getRegistry().getServiceConfig(serviceType);
        FallbackStrategy<ResponseEntity<?>> fallbackStrategy =
                getRegistry().getFallbackManager().getFallbackStrategy(serviceType.name(), serviceConfig);
        if (fallbackStrategy instanceof CacheFallbackStrategy) {
            ((CacheFallbackStrategy) fallbackStrategy).cacheResponse(serviceType, modelName, httpRequest, response);
        }
    }

    /**
     * #892：判定响应是否为流式（不可重放）——body 为响应式 Publisher（Flux/Mono，
     * 覆盖所有 serviceType 的流式路径），或 contentType 兼容 SSE。
     * 包可见供反证单测直接断言契约。
     */
    static boolean isStreamingResponse(final ResponseEntity<?> response) {
        if (response == null) {
            return false;
        }
        if (response.getBody() instanceof Publisher) {
            return true;
        }
        final MediaType contentType = response.getHeaders().getContentType();
        return contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
    }

    protected void logAdapterRetryEvent(final String adapterType,
            final ModelRouterProperties.ModelInstance instance, final int retryCount,
            final int maxRetries, final Throwable error) {
        resilienceSupport.getTracingManager().recordRetry(adapterType, instance, retryCount, maxRetries, error);
        AdapterMetricsRecorder metricsRecorder = resilienceSupport.getMetricsRecorder();
        if (metricsRecorder != null) {
            metricsRecorder.recordRetry(adapterType,
                    instance != null ? instance.getName() : "unknown", retryCount, error);
        }
    }

    protected void logAdapterTransformError(final String adapterType, final Throwable error) {
        resilienceSupport.getTracingManager().recordTransformError(adapterType, error);
        AdapterMetricsRecorder metricsRecorder = resilienceSupport.getMetricsRecorder();
        if (metricsRecorder != null) {
            metricsRecorder.recordError(adapterType, "unknown", "TRANSFORM_ERROR", error, 0, null);
        }
    }

    // ==================== 函数式接口 ====================

    @FunctionalInterface
    protected interface RequestProcessor<T> {
        Mono<? extends ResponseEntity<?>> process(T request, String authorization, WebClient client,
                String path, ModelRouterProperties.ModelInstance selectedInstance,
                ModelServiceRegistry.ServiceType serviceType);
    }
}
