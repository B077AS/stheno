package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.MlsHandler;
import io.github.b077as.stheno.inbound.ReceivedMls;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Follows what happens to the documents the application sent: register each one when it is handed to the Access Point
 * ({@link #track}), pass in the Message Level Status documents the Access Point forwards ({@link #onMls}, done by the
 * {@code InboundProcessor} when it has the tracker), and ask for the {@link DeliveryStatus}.
 * <p>
 * The rules of the Peppol MLS apply: a document keeps its <b>first</b> MLS; silence is normal unless a positive MLS was requested;
 * a missing MLS is never a reason to send a document again by itself. The tracker sends nothing.
 * <p>
 * Thread-safe.
 */
@Slf4j
public final class DeliveryTracker implements MlsHandler {

    /** When a requested positive MLS counts as overdue: the time after which Peppol suggests asking the other side. */
    public static final Duration DEFAULT_OVERDUE_AFTER = Duration.ofHours(24);

    private static final int OVERDUE_BATCH = 100;

    private final DeliveryStore store;
    private final List<DeliveryListener> listeners;
    private final Duration overdueAfter;
    private final Clock clock;

    public DeliveryTracker(DeliveryStore store, List<DeliveryListener> listeners) {
        this(store, listeners, DEFAULT_OVERDUE_AFTER, Clock.systemUTC());
    }

    public DeliveryTracker(DeliveryStore store, List<DeliveryListener> listeners, Duration overdueAfter, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.listeners = List.copyOf(listeners);
        if (overdueAfter == null || overdueAfter.isNegative() || overdueAfter.isZero()) {
            throw new IllegalArgumentException("The overdue time must be positive");
        }
        this.overdueAfter = overdueAfter;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a sent document. Call it as soon as the identifier is known: when the envelope was built by the application, before
     * handing it over; when the Access Point creates the envelope, with the identifier it returns.
     *
     * @param instanceIdentifier  SBDH instance identifier of the sent document
     * @param positiveMlsRequested {@code true} only if the document goes out with MLS usage {@code ALWAYS_SEND}
     * @return the record; if the MLS arrived before this call, it is already decided (and the listeners are told now)
     */
    public TrackedDocument track(String instanceIdentifier, boolean positiveMlsRequested) {
        if (instanceIdentifier == null || instanceIdentifier.isBlank() || instanceIdentifier.length() > 255) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "An instance identifier of 1 to 255 characters is needed to track a document");
        }
        TrackedDocument document = store.register(instanceIdentifier, positiveMlsRequested, clock.instant());
        if (document.getMls() != null) {
            DeliveryStatus status = statusOf(document);
            listeners.forEach(l -> l.onDecided(document, status));
        }
        return document;
    }

    /**
     * Registers an envelope the application built. A positive MLS counts as requested if the envelope asks for it
     * ({@code EnvelopeBuilder.mlsAlwaysSend(true)}): use {@link #track(String, boolean)} if the Access Point changes that.
     */
    public TrackedDocument track(BuiltEnvelope envelope) {
        return track(envelope.getInstanceIdentifier(), envelope.isPositiveMlsRequested());
    }

    /** Records a received MLS and tells the listeners. */
    public MlsCorrelation onMls(ReceivedMls mls) {
        String id = mls.getReferencedInstanceIdentifier();
        if (id == null || id.isBlank() || id.length() > 255) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT, "The MLS does not say which document it answers");
        }
        TrackedDocument document = store.recordMls(mls, clock.instant());
        ReceivedMls kept = document.getMls();
        if (!Objects.equals(cutId(kept.getMlsId()), cutId(mls.getMlsId())) || !kept.getResponseCode().equals(mls.getResponseCode())) {
            log.warn("Ignoring MLS {} ({}) for {}: it already has MLS {} ({}), and only the first one counts", mls.getMlsId(),
                    mls.getResponseCode(), id, kept.getMlsId(), kept.getResponseCode());
            listeners.forEach(l -> l.onIgnored(document, mls));
            return MlsCorrelation.IGNORED;
        }
        if (document.getSentAt() == null) {
            log.info("MLS {} refers to {}, which is not registered as sent (yet)", mls.getResponseCode(), id);
            listeners.forEach(l -> l.onUnknownDocument(mls));
            return MlsCorrelation.UNKNOWN_DOCUMENT;
        }
        DeliveryStatus status = statusOf(document);
        listeners.forEach(l -> l.onDecided(document, status));
        return MlsCorrelation.DECIDED;
    }

    /** {@link #onMls} for the inbound flow: an MLS that cannot be matched to anything is only logged, so that it is not retried. */
    @Override
    public void handle(ReceivedMls mls, InboundDocument document) {
        String id = mls.getReferencedInstanceIdentifier();
        if (id == null || id.isBlank() || id.length() > 255) {
            log.warn("MLS {} cannot be tracked: it does not name a document", mls.getMlsId());
            return;
        }
        onMls(mls);
    }

    public Optional<TrackedDocument> find(String instanceIdentifier) {
        return store.find(instanceIdentifier);
    }

    /** The status of a record right now. */
    public DeliveryStatus statusOf(TrackedDocument document) {
        return document.statusAt(clock.instant(), overdueAfter);
    }

    /** All overdue documents (at most 100, oldest first). */
    public List<TrackedDocument> findOverdue() {
        return store.findOverdue(overdueBefore(), false, OVERDUE_BATCH);
    }

    /**
     * Tells the listeners about documents that became overdue since the last call; call it regularly. A document is reported once; if
     * a listener fails, the next call tries again.
     *
     * @return the number of documents reported (at most 100 per call)
     */
    public int reportOverdue() {
        List<TrackedDocument> overdue = store.findOverdue(overdueBefore(), true, OVERDUE_BATCH);
        int reported = 0;
        for (TrackedDocument document : overdue) {
            try {
                listeners.forEach(l -> l.onOverdue(document));
            } catch (RuntimeException ex) {
                log.warn("Could not report {} as overdue, trying again later: {}", document.getInstanceIdentifier(), ex.toString());
                continue;
            }
            store.markOverdueReported(document.getInstanceIdentifier(), clock.instant());
            reported++;
        }
        return reported;
    }

    private Instant overdueBefore() {
        return clock.instant().minus(overdueAfter);
    }

    /** Stores may cut very long MLS identifiers to 255 characters. */
    private static String cutId(String id) {
        return id == null || id.length() <= 255 ? id : id.substring(0, 255);
    }
}
