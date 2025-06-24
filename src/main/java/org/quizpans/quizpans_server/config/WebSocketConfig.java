package org.quizpans.quizpans_server.config;


// Aktywacja obsługi WebSocket
// obsluga wiadomosci
// rozmiar wiadomosci
import org.quizpans.quizpans_server.online.websocket.LobbyWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LobbyWebSocketHandler lobbyWebSocketHandler;


    public WebSocketConfig(LobbyWebSocketHandler lobbyWebSocketHandler) {
        this.lobbyWebSocketHandler = lobbyWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(lobbyWebSocketHandler, "/lobby") // Endpoint dla lobby
                .setAllowedOrigins("*"); // zezwolenie na wszystkei zrodla
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024000); // 1MB
        container.setMaxBinaryMessageBufferSize(1024000);
        return container;
    }
}