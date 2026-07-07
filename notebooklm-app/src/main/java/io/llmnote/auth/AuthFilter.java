package io.llmnote.auth;

import io.llmnote.config.NotebookLmProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 解析请求主体:
 * 1. 有效 Bearer JWT → 注册用户;
 * 2. 否则读游客 cookie;无则生成 uuid 并 Set-Cookie(HttpOnly) → 游客。
 * 主体放入 CurrentPrincipalHolder,请求结束清理。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final long GUEST_COOKIE_MAX_AGE = 60L * 60 * 24 * 365; // 1 年

    private final JwtService jwtService;
    private final String guestCookieName;

    public AuthFilter(JwtService jwtService, NotebookLmProperties props) {
        this.jwtService = jwtService;
        this.guestCookieName = props.getAuth().getGuestCookieName();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        try {
            Principal principal = resolveUser(req);
            if (principal == null) {
                principal = resolveGuest(req, resp);
            }
            CurrentPrincipalHolder.set(principal);
            chain.doFilter(req, resp);
        } finally {
            CurrentPrincipalHolder.clear();
        }
    }

    private Principal resolveUser(HttpServletRequest req) {
        String jwt = bearerToken(req);
        if (jwt == null) {
            // 媒体标签(如 <audio src>)无法带 Authorization 头,允许 token 查询参数作为兜底。
            jwt = req.getParameter("token");
        }
        if (jwt != null && !jwt.isBlank()) {
            Long userId = jwtService.parseUserId(jwt.trim());
            if (userId != null) {
                return Principal.ofUser(userId);
            }
        }
        return null;
    }

    private String bearerToken(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private Principal resolveGuest(HttpServletRequest req, HttpServletResponse resp) {
        String uuid = readGuestCookie(req);
        if (uuid == null || uuid.isBlank()) {
            uuid = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(guestCookieName, uuid);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge((int) GUEST_COOKIE_MAX_AGE);
            cookie.setAttribute("SameSite", "Lax");
            resp.addCookie(cookie);
        }
        return Principal.ofGuest(uuid);
    }

    private String readGuestCookie(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (guestCookieName.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
