package psia.g17.resource;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.Test;
import psia.g17.InMemoryKafka;
import psia.g17.events.Kind;
import psia.g17.events.ReadyEvent;
import psia.g17.events.Status;
import psia.g17.service.SseBroadcaster;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithTestResource(InMemoryKafka.class)
class RecommendationEventsTest {

    private static final String ALICE = "a1111111-1111-1111-1111-111111111111";
    private static final String BOB = "b2222222-2222-2222-2222-222222222222";

    @TestHTTPResource("/recommendations/events")
    URL events;

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    SseBroadcaster broadcaster;

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = @Claim(key = "sub", value = ALICE))
    void tellsTheUsersOpenConnectionsWhenTheirRecommendationsAreReady() {
        List<InboundSseEvent> received = new CopyOnWriteArrayList<>();
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(client.target(events.toString())).build()) {
            source.register(received::add);
            source.open();
            await().atMost(Duration.ofSeconds(10)).until(() -> received.stream().anyMatch(e -> "connected".equals(e.getName())));

            UUID bobsRequest = UUID.randomUUID();
            UUID alicesRequest = UUID.randomUUID();
            connector.source("recommendation-ready-in").send(new ReadyEvent(bobsRequest, UUID.fromString(BOB), Kind.PROMPT, Status.DONE, null));
            connector.source("recommendation-ready-in").send(new ReadyEvent(alicesRequest, UUID.fromString(ALICE), Kind.PROMPT, Status.DONE, null));

            await().atMost(Duration.ofSeconds(10)).until(() -> received.stream().anyMatch(e -> SseBroadcaster.READY.equals(e.getName())));
            List<ReadyEvent> ready = received.stream().filter(e -> SseBroadcaster.READY.equals(e.getName()))
                    .map(e -> e.readData(ReadyEvent.class, MediaType.APPLICATION_JSON_TYPE)).toList();
            assertEquals(List.of(new ReadyEvent(alicesRequest, UUID.fromString(ALICE), Kind.PROMPT, Status.DONE, null)), ready,
                    "only the user's own events");
        } finally {
            client.close();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            broadcaster.send(new ReadyEvent(UUID.randomUUID(), UUID.fromString(ALICE), Kind.PROMPT, Status.DONE, null));
            assertTrue(broadcaster.connections(UUID.fromString(ALICE)) == 0, "closed connections are forgotten");
        });
    }
}
