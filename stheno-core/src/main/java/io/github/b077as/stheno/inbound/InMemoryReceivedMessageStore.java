package io.github.b077as.stheno.inbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ReceivedMessageStore} in memory, for tests and single node applications. Entries older than the retention are dropped; the
 * retention must be longer than the Access Point keeps retrying or can replay a delivery. Content is lost on restart. There is no
 * processing timeout: an unfinished registration is always one this process is still working on.
 */
public final class InMemoryReceivedMessageStore implements ReceivedMessageStore {

    private final Map<MessageKey, ReceivedRecord> records = new ConcurrentHashMap<>();
    private final Duration retention;
    private final Clock clock;

    public InMemoryReceivedMessageStore(Duration retention) {
        this(retention, Clock.systemUTC());
    }

    public InMemoryReceivedMessageStore(Duration retention, Clock clock) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("The retention must be positive");
        }
        this.retention = retention;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<ReceivedRecord> register(MessageKey key) {
        Instant now = clock.instant();
        purge(now);
        AtomicReference<ReceivedRecord> existing = new AtomicReference<>();
        records.compute(key, (k, old) -> {
            if (old != null && !isExpired(old, now)) {
                existing.set(old);
                return old;
            }
            return new ReceivedRecord(k, now, null);
        });
        return Optional.ofNullable(existing.get());
    }

    @Override
    public void complete(MessageKey key, InboundOutcome outcome) {
        records.computeIfPresent(key, (k, old) -> old.withOutcome(outcome));
    }

    @Override
    public void release(MessageKey key) {
        records.remove(key);
    }

    @Override
    public Optional<ReceivedRecord> find(MessageKey key) {
        ReceivedRecord rec = records.get(key);
        return rec == null || isExpired(rec, clock.instant()) ? Optional.empty() : Optional.of(rec);
    }

    @Override
    public List<ReceivedRecord> findUnfinished(Duration olderThan) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(olderThan);
        return records.values().stream()
                .filter(r -> !r.isCompleted() && !isExpired(r, now) && r.getReceivedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(ReceivedRecord::getReceivedAt))
                .limit(100)
                .toList();
    }

    @Override
    public int purgeExpired() {
        int before = records.size();
        purge(clock.instant());
        return Math.max(0, before - records.size());
    }

    /** Number of remembered messages (expired ones may still be counted until the next registration or purge). */
    public int size() {
        return records.size();
    }

    private boolean isExpired(ReceivedRecord rec, Instant now) {
        return rec.getReceivedAt().plus(retention).isBefore(now);
    }

    private void purge(Instant now) {
        records.values().removeIf(r -> isExpired(r, now));
    }
}
