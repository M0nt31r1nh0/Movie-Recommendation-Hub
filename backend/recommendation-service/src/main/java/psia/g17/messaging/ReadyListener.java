package psia.g17.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import psia.g17.events.ReadyEvent;
import psia.g17.service.SseBroadcaster;

/**
 * Tells users whose results are ready, through their open SSE connections on this copy of the
 * service. Each copy reads recommendation.ready in its own consumer group (see application.properties),
 * so every copy sees every event.
 */
@ApplicationScoped
public class ReadyListener {

    private final SseBroadcaster broadcaster;

    public ReadyListener(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Incoming("recommendation-ready-in")
    public void onReady(ReadyEvent event) {
        broadcaster.send(event);
    }
}
