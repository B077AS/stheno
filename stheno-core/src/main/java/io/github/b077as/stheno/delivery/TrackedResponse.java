package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.reply.InvoiceResponse;
import io.github.b077as.stheno.reply.InvoiceResponseBuilder;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An Invoice Response about a sent invoice, as kept by a {@link DeliveryStore}.
 */
@Value
@Builder(toBuilder = true)
public class TrackedResponse {

    /** SBDH instance identifier of the envelope the response came in. */
    String instanceIdentifier;
    InvoiceResponseBuilder.Status status;
    /** Reasons and requested actions; empty if none. */
    List<InvoiceResponseBuilder.StatusEntry> statusEntries;
    /** Identifier the response gives itself, or {@code null}. */
    String responseId;
    /** Issue date of the response, or {@code null}. */
    LocalDate issueDate;
    /** Date the status applies from, or {@code null}. */
    LocalDate effectiveDate;
    String note;
    /** When it was recorded. */
    Instant receivedAt;

    /** {@code true} for {@code PD} with the reason {@code PPD}. */
    public boolean isPartiallyPaid() {
        return InvoiceResponse.isPartialPayment(status, statusEntries);
    }

    /** {@code true} for {@code RE} and for a full {@code PD}. */
    public boolean isFinal() {
        return InvoiceResponse.isFinal(status, statusEntries);
    }
}
