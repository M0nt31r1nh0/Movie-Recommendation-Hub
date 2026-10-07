package psia.g17.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import psia.g17.events.ReadyEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open SSE connections on this copy of the service, by user. Their number is
 * {@code recommendation_sse_connections}: about how many users have the app open.
 */
// Created at startup, so its metrics are published from the first scrape (beans are otherwise created on first use).
@Startup
@ApplicationScoped
public class SseBroadcaster {

    /** The event name the frontend listens for. */
    public static final String READY = "recommendations-ready";

    private record Connection(SseEventSink sink, Sse sse) {
    }

    private final Map<UUID, Set<Connection>> connections = new ConcurrentHashMap<>();

    public SseBroadcaster(MeterRegistry metrics) {
        Gauge.builder("recommendation.sse.connections", connections, c -> c.values().stream().mapToInt(Set::size).sum())
                .description("Open server-sent event connections: browsers with the app open")
                .register(metrics);
    }

    public void register(UUID userId, SseEventSink sink, Sse sse) {
        Connection connection = new Connection(sink, sse);
        connections.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(connection);
        // An immediate event, so the browser knows the stream is open.
        send(userId, connection, sse.newEventBuilder().name("connected").data("{}").mediaType(MediaType.APPLICATION_JSON_TYPE).build());
    }

    public void send(ReadyEvent event) {
        Set<Connection> open = connections.get(event.userId());
        if (open == null) return;
        for (Connection c : open) {
            send(event.userId(), c, c.sse().newEventBuilder()
                    .name(READY)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(ReadyEvent.class, event)
                    .build());
        }
    }

    public int connections(UUID userId) {
        Set<Connection> open = connections.get(userId);
        return open == null ? 0 : open.size();
    }

    /** Keeps idle connections from being cut by proxies, and notices closed ones. */
    @Scheduled(every = "${recommendation.sse.heartbeat:20s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void heartbeat() {
        connections.forEach((userId, open) -> open.forEach(c -> send(userId, c, c.sse().newEventBuilder().comment("keep-alive").build())));
    }

    private void send(UUID userId, Connection c, OutboundSseEvent event) {
        if (c.sink().isClosed()) {
            remove(userId, c);
            return;
        }
        c.sink().send(event).exceptionally(error -> {
            Log.debugf("SSE connection of %s closed: %s", userId, error.getMessage());
            remove(userId, c);
            return null;
        });
    }

    private void remove(UUID userId, Connection c) {
        connections.computeIfPresent(userId, (id, open) -> {
            open.remove(c);
            return open.isEmpty() ? null : open;
        });
    }
}
