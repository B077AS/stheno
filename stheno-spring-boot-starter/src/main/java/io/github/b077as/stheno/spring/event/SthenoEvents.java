package io.github.b077as.stheno.spring.event;

import io.github.b077as.stheno.ap.Acknowledgement;
import io.github.b077as.stheno.ap.ApCallResult;
import io.github.b077as.stheno.delivery.DeliveryStatus;
import io.github.b077as.stheno.delivery.TrackedDocument;
import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.inbound.ReceivedRecord;
import io.github.b077as.stheno.inbound.Reception;
import lombok.Value;

import java.util.List;

/**
 * Spring application events of the inbound flow; listen with {@code @EventListener}. Events are published on the thread that did the
 * work (request thread or background thread).
 */
public final class SthenoEvents {

    private SthenoEvents() {
    }

    /** A new document was received and registered (before any handler ran). */
    @Value
    public static class DocumentReceived {
        InboundDocument document;
    }

    /** A document was delivered again and ignored. */
    @Value
    public static class DuplicateReceived {
        Reception reception;
    }

    /** A handler decided about a document (or an MLS was taken over). */
    @Value
    public static class DocumentHandled {
        HandlingResult result;
    }

    /** A handler failed with an exception. {@link #isGivingUp()} is {@code true} when no further attempt will be made. */
    @Value
    public static class HandlerFailed {
        HandlingResult result;
        int attempt;
        boolean givingUp;
    }

    /** A Message Level Status for a document the application sent was received. */
    @Value
    public static class MlsReceived {
        ReceivedMls mls;
        InboundDocument document;
    }

    /**
     * The application rejected a document, but the Access Point did not take that MLS verdict: an MLS was already decided (e.g. its
     * fallback {@code AB}), MLS sending is disabled there, the verdict is switched off, or every attempt failed. The sender may
     * believe the document was delivered.
     */
    @Value
    public static class RejectionNotDelivered {
        InboundDocument document;
        InboundOutcome outcome;
        ApCallResult mlsVerdict;
    }

    /**
     * Documents received longer ago than the processing timeout and still without a decision: their handling was lost (e.g. the
     * application stopped) or a handler is very slow. Lost ones have to be delivered again by the Access Point. Published again at
     * every check while they stay unfinished.
     */
    @Value
    public static class DocumentsUnfinished {
        List<ReceivedRecord> documents;
    }

    /**
     * Delivery tracking: an MLS decided about a document the application sent ({@code CONFIRMED}, {@code UNCONFIRMED} or
     * {@code REJECTED}, see the document's MLS for the reasons). May be published again for the same document, e.g. after a listener
     * failed.
     */
    @Value
    public static class DeliveryDecided {
        TrackedDocument document;
        DeliveryStatus status;
    }

    /** Delivery tracking: a requested positive MLS did not arrive in time. Ask the Access Point; do not just send again. */
    @Value
    public static class DeliveryOverdue {
        TrackedDocument document;
    }

    /** Delivery tracking: an MLS refers to a document that is not registered (yet). */
    @Value
    public static class MlsForUnknownDocument {
        ReceivedMls mls;
    }

    /** Delivery tracking: a further MLS for a document was ignored, only the first one counts. */
    @Value
    public static class MlsIgnored {
        TrackedDocument document;
        ReceivedMls ignored;
    }

    /** The Access Point was told about a handled document (or the attempt failed, see {@link Acknowledgement#isFailed()}). */
    @Value
    public static class AccessPointAcknowledged {
        InboundDocument document;
        Acknowledgement acknowledgement;
        int attempt;
        boolean givingUp;
    }
}
