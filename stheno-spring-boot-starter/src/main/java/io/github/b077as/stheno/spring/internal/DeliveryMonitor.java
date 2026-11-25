package io.github.b077as.stheno.spring.internal;

import io.github.b077as.stheno.delivery.DeliveryStore;
import io.github.b077as.stheno.delivery.DeliveryTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Regularly reports overdue documents and purges old delivery records.
 */
@Slf4j
public class DeliveryMonitor implements SmartInitializingSingleton, DisposableBean {

    private static final Duration FIRST_CHECK = Duration.ofSeconds(30);

    private final DeliveryTracker tracker;
    private final DeliveryStore store;
    private final Duration retention;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    public DeliveryMonitor(DeliveryTracker tracker, DeliveryStore store, Duration retention, Duration interval) {
        this.tracker = tracker;
        this.store = store;
        this.retention = retention;
        this.interval = interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stheno-delivery");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts once all beans (and their event listeners) exist. */
    @Override
    public void afterSingletonsInstantiated() {
        Duration first = interval.compareTo(FIRST_CHECK) < 0 ? interval : FIRST_CHECK;
        scheduler.scheduleWithFixedDelay(this::check, first.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    private void check() {
        // Never throws: an exception would end the schedule
        try {
            tracker.reportOverdue();
        } catch (RuntimeException ex) {
            log.warn("Could not look for overdue documents: {}", ex.getMessage());
        }
        try {
            store.purge(Instant.now().minus(retention));
        } catch (RuntimeException ex) {
            log.warn("Could not purge old delivery records: {}", ex.getMessage());
        }
    }
}
