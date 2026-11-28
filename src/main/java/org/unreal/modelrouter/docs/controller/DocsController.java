/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.docs.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内置帮助文档接口。
 *
 * <p>文档 markdown 随应用打包在 {@code classpath:/docs/{lang}/**} 下
 * （zh / en 双语，内容直接维护在 {@code src/main/resources/docs/{zh,en}/} 下，按控制台菜单组织），
 * 本控制器仅负责按路径读取单篇文档原文，目录结构与展示顺序由前端
 * 静态导航配置（docsNav）管理，接口保持最小面。</p>
 *
 * <p>路径安全：{@code path} 仅允许 {@code [a-zA-Z0-9_./-]} 且不得包含
 * {@code ..} 段，杜绝路径穿越读取 classpath 内其他文件。</p>
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    /** 允许的路径字符：字母数字、下划线、点、斜杠、连字符 */
    private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9_./-]+$");

    /** 合法语言：zh / en（与 resources/docs 目录一致） */
    private static final Pattern SAFE_LANG = Pattern.compile("^[a-zA-Z0-9_-]+$");

    /**
     * 企业版专属文档 path（2026-11-28 方案 B：部署形态过滤）。
     *
     * <p>与前端 docsNav 的 {@code ENTERPRISE_ONLY_DOC_PATHS} 三处同口径
     * （前端导航 / URL 白名单 / 本接口）：纯 OSS 部署不提供这两页——导航隐藏、
     * 接口 fail-closed 拒绝，避免 OSS 用户读到纯企业版内容（租户管理 / 企业管理）。</p>
     *
     * <p>共用页里「企业版还会多一个××」的一句话标注不在过滤之列（有意保留）：
     * OSS 界面上真实存在商业版徽章与灰态锁，文档需要解释它们。</p>
     */
    private static final Set<String> ENTERPRISE_ONLY_DOC_PATHS =
            Set.of("system/tenants", "system/enterprise");

    /**
     * 企业部署探测：企业自包含 UI 模块 {@code static/admin/enterprise/module.mjs}
     * 随企业 jar 交付（A2 前端分离架构的锚点文件），纯 OSS 构建没有该资源。
     * classpath 资源探测比类名探测稳——module.mjs 是企业 UI 模块的核心交付物，
     * 存在即视为企业部署。进程内静态判定一次，功能位在一次会话内不会变（换 license 需重启），
     * 与前端 enterprise store 的「只请求一次」口径一致。
     */
    private static final boolean ENTERPRISE_MODULE_PRESENT =
            new ClassPathResource("static/admin/enterprise/module.mjs").exists();

    /**
     * 读取单篇文档原文。
     *
     * @param lang 语言：zh（默认）或 en
     * @param path 相对 docs/{lang} 的文档路径（不含 .md 后缀），如 {@code configuration/rate-limiting}
     * @return 文档 markdown 原文；路径非法或文件不存在返回 error 响应
     */
    @GetMapping("/content")
    // 【2026-11-10 用户决策】帮助文档只对运维/管理员开放，普通用户用「接入指南」(/guide)。
    // 鉴权由 FixedRoleAccessPolicy 的 /api/docs/content = OPS 规则承担。
    // 【不要加 @PreAuthorize】本项目开启 @EnableReactiveMethodSecurity，方法级注解仅对
    // 返回 Publisher（Mono/Flux）的方法生效；本方法是同步 ResponseEntity，加了会在真实
    // 请求时抛 500（"must return an instance of Publisher ... to support Reactor Context"）。
    // 同 ApiCallHistoryController / MyServiceInstanceController 的既有约定。
    public ResponseEntity<RouterResponse<String>> getContent(
            @RequestParam(name = "lang", defaultValue = "zh") final String lang,
            @RequestParam(name = "path") final String path) {

        String normalizedLang = normalizeLang(lang);
        String normalizedPath = normalizePath(path);
        if (normalizedLang == null || normalizedPath == null) {
            return ResponseEntity.badRequest().body(
                    RouterResponse.error("文档路径不合法", "DOC_PATH_INVALID"));
        }

        // 方案 B：纯 OSS 部署不提供企业版专属页（与前端导航过滤同口径）。
        // 返回与「文档不存在」完全一致的响应，不在错误码上区分——不让接口成为
        // 探测部署形态的旁路（虽然这两页内容本身并不敏感，口径上仍按不存在处理）。
        if (!ENTERPRISE_MODULE_PRESENT && ENTERPRISE_ONLY_DOC_PATHS.contains(normalizedPath)) {
            log.info("Enterprise-only doc requested on community deployment: {}", normalizedPath);
            return ResponseEntity.ok(
                    RouterResponse.error("文档不存在: " + normalizedPath, "DOC_NOT_FOUND"));
        }

        ClassPathResource resource = new ClassPathResource("docs/" + normalizedLang + "/" + normalizedPath + ".md");
        if (!resource.exists() || !resource.isReadable()) {
            log.warn("Docs not found: lang={}, path={}", normalizedLang, normalizedPath);
            return ResponseEntity.ok(
                    RouterResponse.error("文档不存在: " + normalizedPath, "DOC_NOT_FOUND"));
        }

        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            // 注意：RouterResponse.success(T) 与 success(String) 存在重载歧义，
            // 传 String 会命中 success(String message) 把内容放进 message、data 为 null，
            // 因此必须使用双参 success(data, message) 显式指定。
            return ResponseEntity.ok(RouterResponse.success(content, "OK"));
        } catch (Exception e) {
            log.error("Failed to read docs: lang={}, path={}", normalizedLang, normalizedPath, e);
            return ResponseEntity.internalServerError().body(
                    RouterResponse.error("读取文档失败", "DOC_READ_ERROR"));
        }
    }

    /**
     * 规范化语言参数：仅允许 zh / en。
     */
    private String normalizeLang(final String lang) {
        if (lang == null || lang.isBlank()) {
            return "zh";
        }
        String trimmed = lang.trim().toLowerCase(Locale.ROOT);
        if (!"zh".equals(trimmed) && !"en".equals(trimmed)) {
            return null;
        }
        return trimmed;
    }

    /**
     * 规范化文档路径：去首尾斜杠、去 .md 后缀、拒绝 {@code ..} 穿越与非法字符。
     *
     * @return 规范化后的相对路径（不含 .md），非法返回 null
     */
    private String normalizePath(final String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim();
        // 去 .md 后缀
        if (p.toLowerCase(Locale.ROOT).endsWith(".md")) {
            p = p.substring(0, p.length() - 3);
        }
        // 去首尾斜杠
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return null;
        }
        // 字符集校验
        if (!SAFE_PATH.matcher(p).matches()) {
            return null;
        }
        // 拒绝 .. 段（路径穿越）
        for (String segment : p.split("/")) {
            if ("..".equals(segment) || ".".equals(segment)) {
                return null;
            }
        }
        return p;
    }
}
