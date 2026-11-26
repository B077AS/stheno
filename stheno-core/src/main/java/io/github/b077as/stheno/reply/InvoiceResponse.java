package io.github.b077as.stheno.reply;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A received Peppol Invoice Response (T111): the buyer's business answer to an invoice, as read by {@link InvoiceResponseReader}.
 * <p>
 * {@link #getStatus() status} and {@link #getInvoiceNumber() invoiceNumber} are always there. Everything else is what the document
 * says and may be {@code null}: reading does not check the official rules (use {@code DocumentValidator} for that).
 */
@Value
// The default name of the generated builder would hide the class InvoiceResponseBuilder
@Builder(builderClassName = "Fields")
public class InvoiceResponse {

    /** A party of the response. */
    @Value
    public static class Party {
        /** Endpoint id as {@code <scheme code>:<value>}, or {@code null}. */
        String participantId;
        /** Legal registration name, or {@code null}. */
        String registrationName;
    }

    /** Identifier of the response itself (not of the invoice). */
    String id;
    LocalDate issueDate;
    /** Time of day as written in the document; a time zone, if given, is not applied. */
    LocalTime issueTime;
    String note;
    /** Who sent the response (the buyer, or a service provider acting for it). */
    Party sender;
    /** Who the response is for (the party that sent the invoice). */
    Party receiver;
    /** The status of the invoice. */
    InvoiceResponseBuilder.Status status;
    /** Date the status applies from, e.g. the payment date. */
    LocalDate effectiveDate;
    /** Reasons and requested actions; empty if none. */
    List<InvoiceResponseBuilder.StatusEntry> statusEntries;
    /** Number of the answered invoice. */
    String invoiceNumber;
    /** Document type code of the answered invoice, e.g. {@code 380}. */
    String invoiceTypeCode;
    /** Issue date of the answered invoice. */
    LocalDate invoiceIssueDate;

    /** {@code true} for {@code PD} with the reason {@code PPD}: part of the amount was paid, further responses may follow. */
    public boolean isPartiallyPaid() {
        return isPartialPayment(status, statusEntries);
    }

    /** {@code true} for {@code RE} and for a full {@code PD}: no further response may be sent about this invoice. */
    public boolean isFinal() {
        return isFinal(status, statusEntries);
    }

    /** Whether the status and its entries mean "partially paid". */
    public static boolean isPartialPayment(InvoiceResponseBuilder.Status status, List<InvoiceResponseBuilder.StatusEntry> entries) {
        return status == InvoiceResponseBuilder.Status.PD && entries != null && entries.stream()
                .anyMatch(e -> "OPStatusReason".equals(e.getListId()) && InvoiceResponseBuilder.Reason.PPD.name().equals(e.getCode()));
    }

    /** Whether the status and its entries close the invoice (BIS Invoice Response rule OP-BR111-R004). */
    public static boolean isFinal(InvoiceResponseBuilder.Status status, List<InvoiceResponseBuilder.StatusEntry> entries) {
        return status == InvoiceResponseBuilder.Status.RE
                || status == InvoiceResponseBuilder.Status.PD && !isPartialPayment(status, entries);
    }
}
