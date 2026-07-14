package com.sales.sync.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-connection WebSocket handler. Each authenticated session is
 * tracked in {@link #sessions} keyed by sessionId so {@link OrdersBroadcaster}
 * can broadcast order events to every connected client. Replace with STOMP
 * if fan-out becomes a concern; this minimal implementation is enough to
 * replace the frontend's previous Supabase Realtime subscription.
 */
@Component
public class OrdersWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        String userId = (String) session.getAttributes().get("userId");
        LOG.info("WS connected: sessionId={} userId={}", session.getId(), userId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        LOG.info("WS closed: sessionId={} status={}", session.getId(), status);
    }

    public Map<String, WebSocketSession> sessions() {
        return sessions;
    }

    public void send(WebSocketSession session, Object payload) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (IOException ex) {
            LOG.warn("WS send failed for session {}: {}", session.getId(), ex.getMessage());
        }
    }

    public void broadcast(String type, Object data) {
        Map<String, Object> envelope = Map.of("type", type, "data", data);
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            LOG.warn("WS broadcast serialize failed: {}", e.getMessage());
            return;
        }
        for (WebSocketSession s : sessions.values()) {
            if (!s.isOpen()) continue;
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(json));
                }
            } catch (IOException ex) {
                LOG.warn("WS broadcast failed for session {}: {}", s.getId(), ex.getMessage());
            }
        }
    }
}