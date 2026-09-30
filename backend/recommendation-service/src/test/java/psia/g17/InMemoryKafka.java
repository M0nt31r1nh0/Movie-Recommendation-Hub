package psia.g17;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;

import java.util.HashMap;
import java.util.Map;

/** Replaces the service's Kafka topics with in-memory channels, so tests need no broker. */
public class InMemoryKafka implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        Map<String, String> config = new HashMap<>();
        config.putAll(InMemoryConnector.switchOutgoingChannelsToInMemory("recommendation-requests", "recommendation-ready"));
        config.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("recommendation-results", "recommendation-ready-in"));
        return config;
    }

    @Override
    public void stop() {
        InMemoryConnector.clear();
    }
}
