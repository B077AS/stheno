package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;

/**
 * Told by the {@link DeliveryTracker} what happened to sent documents.
 * <p>
 * An exception thrown here reaches the caller of the tracker, so that nothing is lost silently: for a received MLS or Invoice Response
 * the Access Point delivers it again and the listener is told again. A listener must therefore cope with being told the same thing
 * more than once.
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

    /**
     * An Invoice Response about a sent invoice. {@code history.getStatus()} is the status of the invoice now.
     *
     * @param document the document carrying that invoice (the one sent last, if it was sent more than once)
     */
    default void onInvoiceResponse(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
    }

    /**
     * An Invoice Response that does not follow the ones before it (e.g. "rejected" after "accepted", or anything after "paid"). It is
     * kept but does not change the status: somebody should look at it.
     */
    default void onResponseOutOfSequence(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
    }

    /**
     * An Invoice Response about an invoice that is not registered. It is kept: if the invoice is registered later,
     * {@link #onInvoiceResponse} follows. Otherwise it may belong to another system, or its number does not match.
     */
    default void onResponseForUnknownInvoice(InvoiceReference invoice, TrackedResponse response) {
    }
}
