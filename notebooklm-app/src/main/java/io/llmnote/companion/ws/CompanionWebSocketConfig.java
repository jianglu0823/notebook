package io.llmnote.companion.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 伴侣 WebSocket 路由:与 voice 通道同端口并行(/api/ws/companion)。 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class CompanionWebSocketConfig implements WebSocketConfigurer {

    private final CompanionWsHandler handler;
    private final CompanionHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/companion")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
