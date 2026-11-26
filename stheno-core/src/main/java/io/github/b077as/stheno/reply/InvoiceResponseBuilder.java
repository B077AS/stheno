package io.github.b077as.stheno.reply;

import com.helger.peppolid.IParticipantIdentifier;
import com.helger.ubl21.UBL21Marshaller;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeBuilder;
import io.github.b077as.stheno.id.IdCheck;
import io.github.b077as.stheno.id.IdUsage;
import io.github.b077as.stheno.id.PeppolIds;
import io.github.b077as.stheno.inbound.InboundDocument;
import lombok.Value;
import oasis.names.specification.ubl.schema.xsd.applicationresponse_21.ApplicationResponseType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.ConditionType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.DocumentReferenceType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.DocumentResponseType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyLegalEntityType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.ResponseType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.StatusType;
import oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.EndpointIDType;
import oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.StatusReasonCodeType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Peppol Invoice Response (BIS Invoice Response 3.1, transaction T111): the buyer's business answer to an invoice
 * (received, in process, under query, conditionally accepted, rejected, accepted, paid). Structure and code lists follow
 * docs.peppol.eu/poacc/upgrade-3 (T111, UNCL4343 subset, OPStatusReason, OPStatusAction).
 * <p>
 * Stheno builds and checks the document; handing it to the Access Point is the application's job. Check the result with
 * {@code DocumentValidator} before sending it: the official Peppol T111 rules are the authority.
 */
public final class InvoiceResponseBuilder {

    public static final String CUSTOMIZATION_ID = "urn:fdc:peppol.eu:poacc:trns:invoice_response:3";
    public static final String PROFILE_ID = "urn:fdc:peppol.eu:poacc:bis:invoice_response:3";
    /** Peppol document type identifier value of the Invoice Response. */
    public static final String DOCUMENT_TYPE_ID =
            "urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2::ApplicationResponse##" + CUSTOMIZATION_ID + "::2.1";
    /** Peppol process identifier value of the Invoice Response. */
    public static final String PROCESS_ID = PROFILE_ID;

    /**
     * Invoice status codes (UNCL4343 subset for T111), declared in the order the BIS lets the status of one invoice advance (rule
     * OP-BR111-R012).
     */
    public enum Status {
        /** Message acknowledgement: the invoice was received. */
        AB,
        /** In process. */
        IP,
        /** Under query: the buyer needs more information. */
        UQ,
        /** Conditionally accepted. */
        CA,
        /** Rejected. */
        RE,
        /** Accepted. */
        AP,
        /** Paid. */
        PD
    }

    /** Status reasons (code list OPStatusReason). */
    public enum Reason {
        NON, REF, LEG, REC, QUA, DEL, PRI, QTY, ITM, PAY, UNR, FIN, PPD, OTH
    }

    /** Requested actions (code list OPStatusAction). */
    public enum Action {
        NOA, PIN, NIN, CNF, CNP, CNA, OTH
    }

    /** One {@code cac:Status} entry: a reason or an action, optional text and conditions (e.g. BT-48 = expected value). */
    @Value
    public static class StatusEntry {
        String listId;
        String code;
        String text;
        List<Condition> conditions;
    }

    /** {@code cac:Condition}: which invoice field (business term, e.g. {@code BT-48}) and the expected value. */
    @Value
    public static class Condition {
        String attributeId;
        String description;
    }

    private final Clock clock;
    private String id;
    private LocalDate issueDate;
    private LocalTime issueTime;
    private String note;
    private String senderId;
    private String senderName;
    private String receiverId;
    private String receiverName;
    private Status status;
    private LocalDate effectiveDate;
    private final List<StatusEntry> entries = new ArrayList<>();
    private String invoiceId;
    private LocalDate invoiceIssueDate;
    private String invoiceTypeCode;

    private InvoiceResponseBuilder(Clock clock) {
        this.clock = clock;
    }

    public static InvoiceResponseBuilder create() {
        return new InvoiceResponseBuilder(Clock.systemDefaultZone());
    }

    static InvoiceResponseBuilder create(Clock clock) {
        return new InvoiceResponseBuilder(clock);
    }

    /**
     * Starts the answer to a received UBL invoice or credit note. Taken over from it:
     * <ul>
     * <li>the participants, swapped, as the envelope names them</li>
     * <li>the invoice number, document type code and issue date</li>
     * <li>the legal names: the customer's for the sender of the response, the supplier's for its receiver. Not for self-billed
     * documents, where the roles differ: set the names with {@link #sender} and {@link #receiver}</li>
     * </ul>
     * What the invoice does not say stays empty and is reported by {@link #build()}. Still needed: {@link #id}, {@link #status} and,
     * for {@link #buildEnvelope}, the country of the sender.
     *
     * @throws SthenoException {@code INVALID_INPUT} if the document is no UBL invoice or credit note
     */
    public static InvoiceResponseBuilder replyTo(InboundDocument invoice) {
        Element root = invoice.getPayload() == null ? null : invoice.getPayload().getXml();
        UblInvoiceHeader header = UblInvoiceHeader.read(root).orElseThrow(() -> new SthenoException(
                SthenoException.ErrorCode.INVALID_INPUT, "An Invoice Response can only be prefilled from a UBL Invoice or CreditNote"));
        boolean selfBilled = header.getCustomizationId() != null && header.getCustomizationId().contains("selfbilling");
        return create()
                .sender(invoice.getReceiverId(), selfBilled ? null : header.getCustomerName())
                .receiver(invoice.getSenderId(), selfBilled ? null : header.getSupplierName())
                .invoice(header.getNumber(), header.getTypeCode(), header.getIssueDate());
    }

    /** Identifier of this response (unique for the sender). */
    public InvoiceResponseBuilder id(String value) {
        id = value;
        return this;
    }

    /** Issue date (default: today). */
    public InvoiceResponseBuilder issueDate(LocalDate value) {
        issueDate = value;
        return this;
    }

    public InvoiceResponseBuilder issueTime(LocalTime value) {
        issueTime = value;
        return this;
    }

    public InvoiceResponseBuilder note(String value) {
        note = value;
        return this;
    }

    /** The party sending the response (the buyer of the invoice): Peppol participant id and legal name. */
    public InvoiceResponseBuilder sender(String participantId, String registrationName) {
        senderId = participantId;
        senderName = registrationName;
        return this;
    }

    /** The party receiving the response (the seller who issued the invoice): Peppol participant id and legal name. */
    public InvoiceResponseBuilder receiver(String participantId, String registrationName) {
        receiverId = participantId;
        receiverName = registrationName;
        return this;
    }

    /** The answered invoice: its number and document type code ({@code 380} invoice, {@code 381} credit note, ...). */
    public InvoiceResponseBuilder invoice(String number, String typeCode, LocalDate issueDateOrNull) {
        invoiceId = number;
        invoiceTypeCode = typeCode;
        invoiceIssueDate = issueDateOrNull;
        return this;
    }

    public InvoiceResponseBuilder status(Status value) {
        status = value;
        return this;
    }

    /** Date the status applies from (e.g. payment date for {@link Status#PD}). */
    public InvoiceResponseBuilder effectiveDate(LocalDate value) {
        effectiveDate = value;
        return this;
    }

    /** Adds a reason (OPStatusReason) with optional text and conditions. */
    public InvoiceResponseBuilder reason(Reason code, String text, Condition... conditions) {
        entries.add(new StatusEntry("OPStatusReason", code.name(), text, List.of(conditions)));
        return this;
    }

    /** Adds a requested action (OPStatusAction) with optional text. */
    public InvoiceResponseBuilder action(Action code, String text) {
        entries.add(new StatusEntry("OPStatusAction", code.name(), text, List.of()));
        return this;
    }

    /** Builds the UBL ApplicationResponse; all problems are reported together. */
    public Element build() {
        List<String> problems = new ArrayList<>();
        require(id, "id", problems);
        require(senderName, "sender registration name", problems);
        require(receiverName, "receiver registration name", problems);
        require(invoiceId, "invoice number", problems);
        require(invoiceTypeCode, "invoice type code", problems);
        if (status == null) {
            problems.add("status is missing");
        }
        IParticipantIdentifier sender = participant("sender", senderId, problems);
        IParticipantIdentifier receiver = participant("receiver", receiverId, problems);
        if (!problems.isEmpty()) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "The Invoice Response cannot be built: " + String.join("; ", problems));
        }

        ApplicationResponseType ar = new ApplicationResponseType();
        ar.setCustomizationID(CUSTOMIZATION_ID);
        ar.setProfileID(PROFILE_ID);
        ar.setID(id);
        ar.setIssueDate(issueDate != null ? issueDate : LocalDate.now(clock));
        if (issueTime != null) {
            ar.setIssueTime(issueTime.withNano(0));
        }
        if (note != null) {
            ar.addNote(new oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.NoteType(note));
        }
        ar.setSenderParty(party(sender, senderName));
        ar.setReceiverParty(party(receiver, receiverName));

        DocumentResponseType docResponse = new DocumentResponseType();
        ResponseType response = new ResponseType();
        response.setResponseCode(status.name());
        if (effectiveDate != null) {
            response.setEffectiveDate(effectiveDate);
        }
        for (StatusEntry entry : entries) {
            StatusType st = new StatusType();
            StatusReasonCodeType code = new StatusReasonCodeType(entry.getCode());
            code.setListID(entry.getListId());
            st.setStatusReasonCode(code);
            if (entry.getText() != null) {
                st.addStatusReason(new oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.StatusReasonType(entry.getText()));
            }
            for (Condition c : entry.getConditions()) {
                ConditionType ct = new ConditionType();
                ct.setAttributeID(c.getAttributeId());
                if (c.getDescription() != null) {
                    ct.addDescription(new oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.DescriptionType(c.getDescription()));
                }
                st.addCondition(ct);
            }
            response.addStatus(st);
        }
        docResponse.setResponse(response);

        DocumentReferenceType ref = new DocumentReferenceType();
        ref.setID(invoiceId);
        if (invoiceIssueDate != null) {
            ref.setIssueDate(invoiceIssueDate);
        }
        ref.setDocumentTypeCode(invoiceTypeCode);
        docResponse.addDocumentReference(ref);
        ar.addDocumentResponse(docResponse);

        Document doc = UBL21Marshaller.applicationResponse().getAsDocument(ar);
        if (doc == null) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT, "The Invoice Response does not match the UBL 2.1 schema");
        }
        return doc.getDocumentElement();
    }

    /** Builds the response and wraps it in a Peppol envelope (sender = this response's sender). */
    public BuiltEnvelope buildEnvelope(String senderCountry) {
        Element root = build();
        return EnvelopeBuilder.create()
                .sender(senderId)
                .receiver(receiverId)
                .documentType(DOCUMENT_TYPE_ID)
                .process(PROCESS_ID)
                .senderCountry(senderCountry)
                .payload(root)
                .build();
    }

    private static PartyType party(IParticipantIdentifier pid, String name) {
        PartyType party = new PartyType();
        String value = pid.getValue();
        EndpointIDType endpoint = new EndpointIDType(value.substring(5));
        endpoint.setSchemeID(value.substring(0, 4));
        party.setEndpointID(endpoint);
        PartyLegalEntityType legal = new PartyLegalEntityType();
        legal.setRegistrationName(name);
        party.addPartyLegalEntity(legal);
        return party;
    }

    private static IParticipantIdentifier participant(String role, String value, List<String> problems) {
        if (value == null) {
            problems.add(role + " participant id is missing");
            return null;
        }
        IdCheck check = PeppolIds.checkParticipantId(value, IdUsage.NEW);
        if (!check.isValid()) {
            problems.add(role + ": " + String.join("; ", check.getErrors()));
            return null;
        }
        return PeppolIds.parseParticipantId(value);
    }

    private static void require(String value, String what, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(what + " is missing");
        }
    }
}
