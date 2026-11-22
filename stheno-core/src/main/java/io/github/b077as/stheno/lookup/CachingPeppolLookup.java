package io.github.b077as.stheno.lookup;

import io.github.b077as.stheno.id.PeppolIds;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the answers of another {@link PeppolLookup}. Positive and negative answers have their own lifetime (a participant that cannot
 * receive today may register tomorrow). Failed lookups are never cached. Keys use the normalised participant id (case insensitive) and
 * the exact document type and process (case sensitive).
 */
public final class CachingPeppolLookup implements PeppolLookup {

    private final PeppolLookup delegate;
    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, ReceiverCapability> cache = new ConcurrentHashMap<>();

    public CachingPeppolLookup(PeppolLookup delegate, Duration positiveTtl, Duration negativeTtl, int maxEntries) {
        this(delegate, positiveTtl, negativeTtl, maxEntries, Clock.systemUTC());
    }

    CachingPeppolLookup(PeppolLookup delegate, Duration positiveTtl, Duration negativeTtl, int maxEntries, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.positiveTtl = Objects.requireNonNull(positiveTtl, "positiveTtl");
        this.negativeTtl = Objects.requireNonNull(negativeTtl, "negativeTtl");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ReceiverCapability lookup(String participantId, String documentTypeId, String processId) {
        String key = PeppolIds.normalizeParticipantId(participantId) + "|" + documentTypeId + "|" + processId;
        ReceiverCapability cached = cache.get(key);
        if (cached != null && !isExpired(cached)) {
            return cached;
        }
        ReceiverCapability fresh = delegate.lookup(participantId, documentTypeId, processId);
        if (cache.size() >= maxEntries) {
            cache.values().removeIf(this::isExpired);
            if (cache.size() >= maxEntries) {
                cache.clear();
            }
        }
        cache.put(key, fresh);
        return fresh;
    }

    /** Forgets all cached answers. */
    public void clear() {
        cache.clear();
    }

    private boolean isExpired(ReceiverCapability c) {
        Duration ttl = c.canReceive() ? positiveTtl : negativeTtl;
        return c.getCheckedAt().plus(ttl).isBefore(clock.instant());
    }
}
