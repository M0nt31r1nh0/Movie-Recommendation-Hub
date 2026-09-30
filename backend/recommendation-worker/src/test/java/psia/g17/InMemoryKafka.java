package psia.g17;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;

import java.util.HashMap;
import java.util.Map;

/** Replaces the worker's Kafka topics with in-memory channels, so tests need no broker. */
public class InMemoryKafka implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        Map<String, String> config = new HashMap<>();
        config.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("recommendation-requests"));
        config.putAll(InMemoryConnector.switchOutgoingChannelsToInMemory("recommendation-results"));
        return config;
    }

    @Override
    public void stop() {
        InMemoryConnector.clear();
    }
}
