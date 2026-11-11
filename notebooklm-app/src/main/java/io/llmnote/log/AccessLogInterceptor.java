package io.llmnote.log;

import io.llmnote.auth.CurrentPrincipalHolder;
import io.llmnote.auth.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 记录所有非基础设施访问到 access_log:写操作(POST/PUT/DELETE/PATCH)和读操作(GET)。
 * 登录/注册由 AuthController 显式记录,这里跳过避免重复。
 * OPTIONS/HEAD/TRACE 视为基础设施请求(CORS 预检、健康检查等),不记。
 * 前台轮询端点(/api/world/feed、论坛 feed、xhs 异步阶段等)按路径前缀跳过,
 * 避免每 2-30s 一次的请求淹没操作日志。
 */
@Component
@RequiredArgsConstructor
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final Set<String> LOGGED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    /** 前台 setInterval/setTimeout 轮询的端点,按 URI 前缀匹配跳过。 */
    private static final Set<String> SKIP_PATH_PREFIXES = Set.of(
            "/api/world/feed",
            "/api/world/forum/posts",
            "/api/studio/xhs/",
            "/api/studio/writing/",
            "/api/world/meetings/",
            "/api/news"
    );

    private final AccessLogService accessLogService;

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler,
                                @Nullable Exception ex) {
        if (!LOGGED_METHODS.contains(req.getMethod())) {
            return;
        }
        String uri = req.getRequestURI();
        if (uri != null) {
            if (uri.startsWith("/api/auth/")) {
                return; // 登录/注册单独记录
            }
            if (isPollingPath(uri)) {
                return; // 前台轮询,不记
            }
        }
        Principal principal = CurrentPrincipalHolder.get();
        accessLogService.logRequest(req, principal, action(req.getMethod(), uri), resp.getStatus());
    }

    private static boolean isPollingPath(String uri) {
        for (String prefix : SKIP_PATH_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 由 METHOD + 资源类型概括动作名,如 POST_NOTEBOOKS、DELETE_NOTES、POST_PODCASTS。 */
    static String action(String method, String uri) {
        String resource = "API";
        if (uri != null) {
            String[] parts = uri.split("/");
            // 取最后一个非数字路径段作为资源名(跳过 id)
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i];
                if (!p.isBlank() && !p.chars().allMatch(Character::isDigit)) {
                    resource = p.toUpperCase();
                    break;
                }
            }
        }
        return method + "_" + resource;
    }
}
