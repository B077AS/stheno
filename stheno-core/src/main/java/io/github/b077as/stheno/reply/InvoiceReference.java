package io.github.b077as.stheno.reply;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.id.PeppolIds;
import io.github.b077as.stheno.inbound.InboundDocument;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What identifies an invoice or credit note between two Peppol participants. An Invoice Response names the invoice it answers only by
 * number, type code and optionally issue date, so the two participants are part of the identity: two senders may use the same number.
 * <p>
 * Participant identifiers are kept normalised ({@code scheme::value} in lower case), so equal references compare equal.
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InvoiceReference {

    private static final int MAX_LENGTH = 255;

    /** The participant that sent the invoice (in billing: the seller). */
    String invoiceSenderId;
    /** The participant that received it and answers it (in billing: the buyer). */
    String invoiceReceiverId;
    /** The invoice number ({@code cbc:ID}), case sensitive. */
    String invoiceNumber;
    /** The document type code, e.g. {@code 380} invoice, {@code 381} credit note. */
    String typeCode;
    /** The issue date of the invoice, or {@code null} if not known. */
    LocalDate issueDate;

    /**
     * @throws SthenoException {@code INVALID_INPUT} if a participant identifier cannot be parsed or a value is missing or longer than
     *                         255 characters
     */
    public static InvoiceReference of(String invoiceSenderId, String invoiceReceiverId, String invoiceNumber, String typeCode,
                                      LocalDate issueDateOrNull) {
        List<String> problems = new ArrayList<>();
        if (PeppolIds.parseParticipantId(invoiceSenderId) == null) {
            problems.add("'" + invoiceSenderId + "' (invoice sender) is not a Peppol participant identifier");
        }
        if (PeppolIds.parseParticipantId(invoiceReceiverId) == null) {
            problems.add("'" + invoiceReceiverId + "' (invoice receiver) is not a Peppol participant identifier");
        }
        require(invoiceNumber, "invoice number", problems);
        require(typeCode, "invoice type code", problems);
        if (!problems.isEmpty()) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "Not a usable invoice reference: " + String.join("; ", problems));
        }
        return new InvoiceReference(PeppolIds.normalizeParticipantId(invoiceSenderId),
                PeppolIds.normalizeParticipantId(invoiceReceiverId), invoiceNumber.strip(), typeCode.strip(), issueDateOrNull);
    }

    /**
     * The reference of a UBL invoice or credit note in an envelope. Empty for other documents and if the invoice lacks its number or
     * type code.
     */
    public static Optional<InvoiceReference> from(InboundDocument invoice) {
        if (invoice.getPayload() == null) {
            return Optional.empty();
        }
        return UblInvoiceHeader.read(invoice.getPayload().getXml()).flatMap(header -> usable(invoice.getSenderId(),
                invoice.getReceiverId(), header.getNumber(), header.getTypeCode(), header.getIssueDate()));
    }

    /**
     * The invoice a received Invoice Response answers: it was sent by the response's receiver to the response's sender, as the
     * envelope names them. Empty if the response does not say the invoice type code.
     */
    public static Optional<InvoiceReference> answeredBy(InvoiceResponse response, InboundDocument responseDocument) {
        return usable(responseDocument.getReceiverId(), responseDocument.getSenderId(), response.getInvoiceNumber(),
                response.getInvoiceTypeCode(), response.getInvoiceIssueDate());
    }

    private static Optional<InvoiceReference> usable(String sender, String receiver, String number, String typeCode, LocalDate date) {
        try {
            return Optional.of(of(sender, receiver, number, typeCode, date));
        } catch (SthenoException ex) {
            return Optional.empty();
        }
    }

    private static void require(String value, String what, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(what + " is missing");
        } else if (value.strip().length() > MAX_LENGTH) {
            problems.add(what + " is longer than " + MAX_LENGTH + " characters");
        }
    }
}
