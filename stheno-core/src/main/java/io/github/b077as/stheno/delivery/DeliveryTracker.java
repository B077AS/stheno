package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeReader;
import io.github.b077as.stheno.inbound.DeliveryTracking;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;
import io.github.b077as.stheno.reply.InvoiceResponse;
import io.github.b077as.stheno.reply.InvoiceResponseReader;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Follows what happens to the documents the application sent: register each one when it is handed to the Access Point
 * ({@link #track}), pass in what the Access Point forwards (the {@code InboundProcessor} does that when it has the tracker), and ask
 * for the {@link DeliveryStatus} and, for invoices and credit notes, the {@link ResponseHistory}.
 * <p>
 * Two different answers are followed. The Message Level Status ({@link #onMls}) says whether the document was <b>delivered</b>: a
 * document keeps its first one; silence is normal unless a positive MLS was requested; a missing MLS is never a reason to send a
 * document again by itself. The Invoice Response ({@link #onInvoiceResponse}) says what the <b>buyer</b> does with the invoice: there
 * can be several, and none at all is normal. The tracker sends nothing.
 * <p>
 * Thread-safe.
 */
@Slf4j
public final class DeliveryTracker implements DeliveryTracking {

    /** When a requested positive MLS counts as overdue: the time after which Peppol suggests asking the other side. */
    public static final Duration DEFAULT_OVERDUE_AFTER = Duration.ofHours(24);

    private static final int OVERDUE_BATCH = 100;

    private final DeliveryStore store;
    private final List<DeliveryListener> listeners;
    private final Duration overdueAfter;
    private final Clock clock;
    private final EnvelopeReader envelopeReader = new EnvelopeReader();
    private final InvoiceResponseReader responseReader = new InvoiceResponseReader();

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
     * <p>
     * Invoice Responses can only be matched if the invoice is known: use {@link #track(String, boolean, InvoiceReference)} then.
     *
     * @param instanceIdentifier  SBDH instance identifier of the sent document
     * @param positiveMlsRequested {@code true} only if the document goes out with MLS usage {@code ALWAYS_SEND}
     * @return the record; if an MLS arrived before this call, it is already decided (and the listeners are told now)
     */
    public TrackedDocument track(String instanceIdentifier, boolean positiveMlsRequested) {
        return track(instanceIdentifier, positiveMlsRequested, null);
    }

    /**
     * Like {@link #track(String, boolean)}, for an invoice or credit note whose responses should be followed.
     *
     * @param invoice the invoice in the document as the envelope and the invoice name it, or {@code null}
     */
    public TrackedDocument track(String instanceIdentifier, boolean positiveMlsRequested, InvoiceReference invoice) {
        if (!usableId(instanceIdentifier)) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "An instance identifier of 1 to 255 characters is needed to track a document");
        }
        TrackedDocument document = store.register(instanceIdentifier, positiveMlsRequested, invoice, clock.instant());
        if (document.getMls() != null) {
            DeliveryStatus status = statusOf(document);
            listeners.forEach(l -> l.onDecided(document, status));
        }
        // Responses that came before the registration
        ResponseHistory history = responsesTo(document);
        history.getResponses().forEach(response -> tell(document, response, history));
        return document;
    }

    /**
     * Registers an envelope the application built. A positive MLS counts as requested if the envelope asks for it
     * ({@code EnvelopeBuilder.mlsAlwaysSend(true)}): use {@link #track(String, boolean, InvoiceReference)} if the Access Point changes
     * that. For a UBL invoice or credit note, its responses are followed too.
     */
    public TrackedDocument track(BuiltEnvelope envelope) {
        InvoiceReference invoice = InvoiceReference.from(envelopeReader.read(envelope.getXml(), null)).orElse(null);
        return track(envelope.getInstanceIdentifier(), envelope.isPositiveMlsRequested(), invoice);
    }

    /** Records a received MLS and tells the listeners. */
    public MlsCorrelation onMls(ReceivedMls mls) {
        String id = mls.getReferencedInstanceIdentifier();
        if (!usableId(id)) {
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

    /**
     * Records a received Invoice Response and tells the listeners. It belongs to the invoice with its number and type code that went
     * from the participant now receiving the response to the participant now sending it.
     *
     * @param response         what {@link InvoiceResponseReader} read
     * @param responseDocument the envelope it came in
     * @throws SthenoException {@code INVALID_INPUT} if the response cannot identify an invoice (e.g. no invoice type code)
     */
    public ResponseCorrelation onInvoiceResponse(InvoiceResponse response, InboundDocument responseDocument) {
        InvoiceReference invoice = InvoiceReference.answeredBy(response, responseDocument).orElse(null);
        if (invoice == null || response.getStatus() == null || !usableId(responseDocument.getInstanceIdentifier())) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "The Invoice Response does not say enough to find the invoice it answers");
        }
        TrackedResponse kept = store.recordResponse(invoice, TrackedResponse.builder()
                .instanceIdentifier(responseDocument.getInstanceIdentifier())
                .status(response.getStatus())
                .statusEntries(response.getStatusEntries() == null ? List.of() : response.getStatusEntries())
                .responseId(response.getId())
                .issueDate(response.getIssueDate())
                .effectiveDate(response.getEffectiveDate())
                .note(response.getNote())
                .receivedAt(clock.instant())
                .build());
        List<TrackedDocument> documents = store.findByInvoice(invoice);
        if (documents.isEmpty()) {
            log.info("Invoice Response {} for invoice {}, which is not registered as sent (yet)", kept.getStatus(),
                    invoice.getInvoiceNumber());
            listeners.forEach(l -> l.onResponseForUnknownInvoice(invoice, kept));
            return ResponseCorrelation.UNKNOWN_INVOICE;
        }
        TrackedDocument document = documents.get(0);
        return tell(document, kept, responsesTo(document));
    }

    /** {@link #onMls} for the inbound flow: an MLS that cannot be matched to anything is only logged, so that it is not retried. */
    @Override
    public void handle(ReceivedMls mls, InboundDocument document) {
        if (!usableId(mls.getReferencedInstanceIdentifier())) {
            log.warn("MLS {} cannot be tracked: it does not name a document", mls.getMlsId());
            return;
        }
        onMls(mls);
    }

    /**
     * {@link #onInvoiceResponse} for the inbound flow; other documents are not of interest. A response that cannot be read or matched
     * is only logged: the application took it over, so it is not retried.
     */
    @Override
    public void accepted(InboundDocument document) {
        if (!InvoiceResponseReader.isInvoiceResponse(document)) {
            return;
        }
        InvoiceResponse response;
        try {
            response = responseReader.read(document);
        } catch (SthenoException ex) {
            log.warn("Invoice Response {} cannot be tracked: {}", document.getInstanceIdentifier(), ex.getMessage());
            return;
        }
        if (InvoiceReference.answeredBy(response, document).isEmpty() || !usableId(document.getInstanceIdentifier())) {
            log.warn("Invoice Response {} cannot be tracked: it does not say enough to find the invoice it answers",
                    document.getInstanceIdentifier());
            return;
        }
        onInvoiceResponse(response, document);
    }

    public Optional<TrackedDocument> find(String instanceIdentifier) {
        return store.find(instanceIdentifier);
    }

    /** The status of a record right now: whether the document was delivered. */
    public DeliveryStatus statusOf(TrackedDocument document) {
        return document.statusAt(clock.instant(), overdueAfter);
    }

    /** The Invoice Responses about the invoice in a record; empty if there are none or the invoice is not known. */
    public ResponseHistory responsesTo(TrackedDocument document) {
        return ResponseHistory.of(document.getInvoice() == null || document.getSentAt() == null ? List.of()
                : store.findResponses(document.getInvoice()));
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

    private ResponseCorrelation tell(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
        boolean outOfSequence = history.getOutOfSequence().stream()
                .anyMatch(r -> r.getInstanceIdentifier().equals(response.getInstanceIdentifier()));
        if (outOfSequence) {
            log.warn("Invoice Response {} for invoice {} does not follow the earlier ones; its status stays {}", response.getStatus(),
                    document.getInvoice().getInvoiceNumber(), history.getStatus());
            listeners.forEach(l -> l.onResponseOutOfSequence(document, response, history));
            return ResponseCorrelation.OUT_OF_SEQUENCE;
        }
        listeners.forEach(l -> l.onInvoiceResponse(document, response, history));
        return ResponseCorrelation.APPLIED;
    }

    private Instant overdueBefore() {
        return clock.instant().minus(overdueAfter);
    }

    private static boolean usableId(String id) {
        return id != null && !id.isBlank() && id.length() <= 255;
    }

    /** Stores may cut very long MLS identifiers to 255 characters. */
    private static String cutId(String id) {
        return id == null || id.length() <= 255 ? id : id.substring(0, 255);
    }
}
