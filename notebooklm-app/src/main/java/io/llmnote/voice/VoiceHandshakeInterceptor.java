package io.llmnote.voice;

import io.llmnote.auth.JwtService;
import io.llmnote.config.NotebookLmProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 握手鉴权:优先 ?token= 自签 JWT(注册用户),否则读游客 cookie;都没有则拒绝握手。 */
@Component
public class VoiceHandshakeInterceptor implements HandshakeInterceptor {

    static final String OWNER_ID = "ownerId";

    private final JwtService jwtService;
    private final String guestCookieName;

    public VoiceHandshakeInterceptor(JwtService jwtService, NotebookLmProperties props) {
        this.jwtService = jwtService;
        this.guestCookieName = props.getAuth().getGuestCookieName();
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletReq)) return false;
        HttpServletRequest req = servletReq.getServletRequest();

        String token = req.getParameter("token");
        if (token != null && !token.isBlank()) {
            Long userId = jwtService.parseUserId(token.trim());
            if (userId != null) {
                attributes.put(OWNER_ID, "u:" + userId);
                return true;
            }
        }

        String guest = readGuestCookie(req);
        if (guest != null && !guest.isBlank()) {
            attributes.put(OWNER_ID, "g:" + guest);
            return true;
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String readGuestCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (guestCookieName.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
