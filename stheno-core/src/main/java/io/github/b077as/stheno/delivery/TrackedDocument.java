package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * A sent document and the Message Level Status that answered it, as kept by a {@link DeliveryStore}.
 */
@Value
public class TrackedDocument {

    /** SBDH instance identifier of the sent document. */
    String instanceIdentifier;
    /**
     * When the application registered it as sent; {@code null} if an MLS arrived for an identifier that was not registered (not yet,
     * or never).
     */
    Instant sentAt;
    /** {@code true} if a positive MLS was requested for it (MLS usage {@code ALWAYS_SEND}). */
    boolean positiveMlsRequested;
    /** The MLS that counts, which is the first one received; {@code null} if none arrived. */
    ReceivedMls mls;
    /** When that MLS was recorded. */
    Instant mlsReceivedAt;
    /** The invoice or credit note in the document, to match Invoice Responses; {@code null} if not registered. */
    InvoiceReference invoice;

    public DeliveryStatus statusAt(Instant now, Duration overdueAfter) {
        if (mls != null) {
            if (mls.isRejected()) {
                return DeliveryStatus.REJECTED;
            }
            return mls.isAccepted() ? DeliveryStatus.CONFIRMED : DeliveryStatus.UNCONFIRMED;
        }
        if (!positiveMlsRequested) {
            return DeliveryStatus.NO_REJECTION;
        }
        return sentAt != null && sentAt.plus(overdueAfter).isBefore(now) ? DeliveryStatus.OVERDUE : DeliveryStatus.AWAITING;
    }
}
