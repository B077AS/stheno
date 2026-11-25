package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.inbound.ReceivedMls;

/**
 * Told by the {@link DeliveryTracker} what happened to sent documents.
 * <p>
 * An exception thrown here reaches the caller of the tracker, so that nothing is lost silently: for a received MLS the Access Point
 * delivers it again and the listener is told again. A listener must therefore cope with being told the same thing more than once.
 */
public interface DeliveryListener {

    /** An MLS decided about a registered document: its status is now {@code CONFIRMED}, {@code UNCONFIRMED} or {@code REJECTED}. */
    default void onDecided(TrackedDocument document, DeliveryStatus status) {
    }

    /** A requested positive MLS did not arrive in time (told once per document, see {@link DeliveryStatus#OVERDUE}). */
    default void onOverdue(TrackedDocument document) {
    }

    /**
     * An MLS refers to a document that is not registered. It is kept: if the document is registered later (the MLS was faster),
     * {@link #onDecided} follows. Otherwise it may belong to another system, or was sent to the wrong place.
     */
    default void onUnknownDocument(ReceivedMls mls) {
    }

    /** A further MLS for a document that already has one. Only the first counts, so this one is ignored. */
    default void onIgnored(TrackedDocument document, ReceivedMls ignored) {
    }
}
