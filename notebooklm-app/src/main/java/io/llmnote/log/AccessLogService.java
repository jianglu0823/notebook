package io.llmnote.log;

import io.llmnote.auth.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 访问日志落库:从请求提取客户端 IP(优先 X-Forwarded-For,应用在网关/funnel 之后)、
 * User-Agent 与推断的设备类型。写库异步、失败只告警,绝不影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogService {

    private final AccessLogRepository repo;

    /** 通用操作日志(由拦截器调用):动作 = METHOD + path 概要。 */
    public void logRequest(HttpServletRequest req, Principal principal, String action, int status) {
        AccessLog e = base(req, principal);
        e.setAction(action);
        e.setMethod(req.getMethod());
        e.setPath(truncate(req.getRequestURI(), 512));
        e.setStatus(status);
        save(e);
    }

    /** 登录 / 注册 等身份事件:显式携带用户名与结果。 */
    public void logAuth(HttpServletRequest req, String action, Long userId, String username, boolean guest) {
        AccessLog e = new AccessLog();
        e.setAction(action);
        e.setUserId(userId);
        e.setUsername(truncate(username, 64));
        e.setGuest(guest);
        e.setOwnerId(userId != null ? "u:" + userId : null);
        e.setMethod(req.getMethod());
        e.setPath(truncate(req.getRequestURI(), 512));
        String ua = req.getHeader("User-Agent");
        e.setIp(truncate(clientIp(req), 64));
        e.setUserAgent(truncate(ua, 512));
        e.setDeviceType(deviceType(ua));
        save(e);
    }

    private AccessLog base(HttpServletRequest req, Principal principal) {
        AccessLog e = new AccessLog();
        if (principal != null) {
            e.setOwnerId(principal.ownerId());
            e.setUserId(principal.userId());
            e.setGuest(principal.guest());
        }
        String ua = req.getHeader("User-Agent");
        e.setIp(truncate(clientIp(req), 64));
        e.setUserAgent(truncate(ua, 512));
        e.setDeviceType(deviceType(ua));
        return e;
    }

    private void save(AccessLog e) {
        try {
            repo.save(e);
        } catch (Exception ex) {
            log.warn("access log save failed: {}", ex.getMessage());
        }
    }

    /** 网关/tailscale funnel 之后,真实 IP 在 X-Forwarded-For 首段;回退 X-Real-IP、remoteAddr。 */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }

    /** 从 User-Agent 粗略推断设备类型,用于运营统计,非精确解析。 */
    static String deviceType(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown";
        }
        String s = ua.toLowerCase();
        if (s.contains("bot") || s.contains("spider") || s.contains("crawler") || s.contains("curl")) {
            return "Bot";
        }
        if (s.contains("ipad") || (s.contains("tablet") && !s.contains("mobile"))) {
            return "Tablet";
        }
        if (s.contains("mobi") || s.contains("iphone") || s.contains("android") || s.contains("phone")) {
            return "Mobile";
        }
        return "Desktop";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
