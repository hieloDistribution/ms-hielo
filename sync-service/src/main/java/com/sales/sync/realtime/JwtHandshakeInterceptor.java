package com.sales.sync.realtime;

import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.TokenExpiredException;
import com.sales.sync.auth.security.TokenInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validates the JWT passed in the {@code token} query parameter of the
 * WebSocket handshake. On success the parsed principal is stashed as a
 * WebSocket session attribute so the controller can resolve the userId.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = null;
        if (request instanceof ServletServerHttpRequest servlet) {
            token = servlet.getServletRequest().getParameter("token");
        }
        if (token == null || token.isBlank()) {
            LOG.warn("WS handshake rejected: missing token");
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            JwtService.ParsedToken parsed = jwtService.parse(token);
            attributes.put("userId", parsed.userId().toString());
            String firstRoleName = parsed.roles().isEmpty()
                    ? ""
                    : parsed.roles().iterator().next().name();
            attributes.put("role", firstRoleName);
            attributes.put("roles",
                    parsed.roles().stream().map(Enum::name).collect(Collectors.joining(",")));
            attributes.put("email", parsed.email());
            return true;
        } catch (TokenInvalidException | TokenExpiredException ex) {
            LOG.warn("WS handshake rejected: invalid token ({})", ex.getMessage());
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
