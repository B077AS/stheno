package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.inbound.ReceivedMls;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where a {@link DeliveryTracker} keeps the sent documents and their Message Level Status.
 * <p>
 * Implementations must be thread safe, and {@link #register} and {@link #recordMls} atomic: an MLS may arrive while the document is
 * still being registered, and neither may overwrite the other.
 */
public interface DeliveryStore {

    /**
     * Registers a document as sent. If an MLS for it arrived earlier, that record is completed. Registering a document again changes
     * nothing.
     *
     * @return the record afterwards
     */
    TrackedDocument register(String instanceIdentifier, boolean positiveMlsRequested, Instant now);

    /**
     * Records an MLS for the document it refers to, creating a record without {@code sentAt} if that document is not registered. A
     * document keeps the first MLS: a later one changes nothing.
     *
     * @return the record afterwards; its MLS tells whether this one or an earlier one is kept
     */
    TrackedDocument recordMls(ReceivedMls mls, Instant now);

    Optional<TrackedDocument> find(String instanceIdentifier);

    /**
     * Registered documents waiting for a requested positive MLS that were sent before {@code sentBefore}, oldest first.
     *
     * @param onlyUnreported leave out those already passed to {@link #markOverdueReported}
     * @param max            at most this many
     */
    List<TrackedDocument> findOverdue(Instant sentBefore, boolean onlyUnreported, int max);

    /** Remembers that the document was reported as overdue. */
    void markOverdueReported(String instanceIdentifier, Instant now);

    /** Forgets the records created before the given time. Returns their number. */
    int purge(Instant createdBefore);
}
