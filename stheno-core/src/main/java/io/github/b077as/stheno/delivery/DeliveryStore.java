package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Where a {@link DeliveryTracker} keeps the sent documents, their Message Level Status and the Invoice Responses about them.
 * <p>
 * Implementations must be thread safe, and {@link #register}, {@link #recordMls} and {@link #recordResponse} atomic: an answer may
 * arrive while the document is still being registered, and neither may overwrite the other.
 * <p>
 * Two {@link InvoiceReference}s mean the same invoice if participants, number and type code are equal and the issue dates do not
 * contradict each other (one of them may be unknown).
 */
public interface DeliveryStore {

    /**
     * Registers a document as sent. If an MLS for it arrived earlier, that record is completed. Registering a document again changes
     * nothing.
     *
     * @param invoice the invoice in the document, or {@code null}
     * @return the record afterwards
     */
    TrackedDocument register(String instanceIdentifier, boolean positiveMlsRequested, InvoiceReference invoice, Instant now);

    /**
     * Records an MLS for the document it refers to, creating a record without {@code sentAt} if that document is not registered. A
     * document keeps the first MLS: a later one changes nothing.
     *
     * @return the record afterwards; its MLS tells whether this one or an earlier one is kept
     */
    TrackedDocument recordMls(ReceivedMls mls, Instant now);

    Optional<TrackedDocument> find(String instanceIdentifier);

    /**
     * Records an Invoice Response about an invoice, whether or not that invoice is registered. A response is identified by the invoice
     * sender and the response's instance identifier: recording it again changes nothing.
     *
     * @return the response as kept
     */
    TrackedResponse recordResponse(InvoiceReference invoice, TrackedResponse response);

    /** The responses about an invoice, in the order of arrival. */
    List<TrackedResponse> findResponses(InvoiceReference invoice);

    /** The registered documents carrying this invoice, the one sent last first. */
    List<TrackedDocument> findByInvoice(InvoiceReference invoice);

    /**
     * Registered documents waiting for a requested positive MLS that were sent before {@code sentBefore}, oldest first.
     *
     * @param onlyUnreported leave out those already passed to {@link #markOverdueReported}
     * @param max            at most this many
     */
    List<TrackedDocument> findOverdue(Instant sentBefore, boolean onlyUnreported, int max);

    /** Remembers that the document was reported as overdue. */
    void markOverdueReported(String instanceIdentifier, Instant now);

    /**
     * Forgets the records created before the given time and the responses received before it.
     *
     * @return the number of document records forgotten
     */
    int purge(Instant createdBefore);

    /** Whether two issue dates can belong to the same invoice. */
    static boolean compatibleDates(LocalDate a, LocalDate b) {
        return a == null || b == null || a.equals(b);
    }
}
