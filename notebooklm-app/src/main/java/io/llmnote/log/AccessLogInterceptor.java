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
 * 记录写操作(POST/PUT/DELETE)到 access_log。登录/注册由 AuthController 显式记录,这里跳过避免重复。
 * 只读请求(GET)不记,避免日志膨胀。
 */
@Component
@RequiredArgsConstructor
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AccessLogService accessLogService;

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler,
                                @Nullable Exception ex) {
        if (!WRITE_METHODS.contains(req.getMethod())) {
            return;
        }
        String uri = req.getRequestURI();
        if (uri != null && uri.startsWith("/api/auth/")) {
            return; // 登录/注册单独记录
        }
        Principal principal = CurrentPrincipalHolder.get();
        accessLogService.logRequest(req, principal, action(req.getMethod(), uri), resp.getStatus());
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
