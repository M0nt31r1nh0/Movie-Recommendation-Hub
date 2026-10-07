package psia.g17.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the profiles database holds, for Prometheus: {@code profiles_stored}, by {@code what} (profiles,
 * follows, watched_movies, watchlist_entries, favorite_genres). Counted every {@code profiles.metrics.every}
 * rather than on each scrape, so Prometheus never waits on the database; unknown (NaN) until the first count.
 */
// Created at startup, so its metrics are published from the first scrape (beans are otherwise created on first use).
@Startup
@ApplicationScoped
public class ProfileStats {

    /** Each gauge's {@code what}, and the table it counts. */
    private static final Map<String, String> TABLES = Map.of(
            "profiles", "profiles.profile",
            "follows", "profiles.follow",
            "watched_movies", "profiles.watched_movie",
            "watchlist_entries", "profiles.watchlist",
            "favorite_genres", "profiles.favorite_genre");

    private final EntityManager em;
    private final Map<String, Double> counts = new ConcurrentHashMap<>();

    public ProfileStats(EntityManager em, MeterRegistry metrics) {
        this.em = em;
        TABLES.keySet().forEach(what -> {
            counts.put(what, Double.NaN);
            Gauge.builder("profiles.stored", counts, c -> c.get(what))
                    .description("Rows in the profiles database: profiles, follows, watched movies, watchlist entries, favourite genres")
                    .tag("what", what)
                    .register(metrics);
        });
    }

    @Scheduled(every = "${profiles.metrics.every:30s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void count() {
        try {
            QuarkusTransaction.requiringNew().run(() -> TABLES.forEach((what, table) -> counts.put(what,
                    ((Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).doubleValue())));
        } catch (RuntimeException e) {
            Log.warnf("Couldn't count the profiles database's rows: %s", e.getMessage());
        }
    }
}
