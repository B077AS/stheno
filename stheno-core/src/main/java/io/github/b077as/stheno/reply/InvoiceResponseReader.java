package io.github.b077as.stheno.reply;

import com.helger.ubl21.UBL21Marshaller;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundPayload;
import io.github.b077as.stheno.inbound.InboundRouter;
import oasis.names.specification.ubl.schema.xsd.applicationresponse_21.ApplicationResponseType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.ConditionType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.DocumentReferenceType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.DocumentResponseType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.ResponseType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.StatusType;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Peppol Invoice Response (BIS Invoice Response 3, transaction T111).
 * <p>
 * It checks what is needed to understand the answer (UBL 2.1 schema, specification identifier, one status code and the number of one
 * invoice), not the official business rules: use {@code DocumentValidator} for those.
 * <p>
 * Thread-safe.
 */
public final class InvoiceResponseReader {

    /** {@code true} if the document has the Peppol Invoice Response document type. */
    public static boolean isInvoiceResponse(InboundDocument document) {
        return InboundRouter.hasDocumentType(document, InvoiceResponseBuilder.DOCUMENT_TYPE_ID);
    }

    /**
     * @throws SthenoException {@link SthenoException.ErrorCode#INVALID_DOCUMENT} if the payload is no readable Invoice Response
     */
    public InvoiceResponse read(InboundDocument document) {
        if (document.getPayload() == null || document.getPayload().getKind() != InboundPayload.Kind.XML) {
            throw invalid("it must have an XML payload");
        }
        return read(document.getPayload().getXml());
    }

    /**
     * @throws SthenoException {@link SthenoException.ErrorCode#INVALID_DOCUMENT} if the element is no readable Invoice Response
     */
    public InvoiceResponse read(Element root) {
        ApplicationResponseType ar = UBL21Marshaller.applicationResponse().read(root);
        if (ar == null) {
            throw invalid("it is not a schema valid UBL 2.1 ApplicationResponse");
        }
        String customization = ar.getCustomizationIDValue();
        if (customization == null || !customization.strip().startsWith(InvoiceResponseBuilder.CUSTOMIZATION_ID)) {
            throw invalid("its CustomizationID is '" + customization + "'");
        }
        if (ar.getDocumentResponseCount() != 1) {
            throw invalid("it must answer exactly one invoice, found " + ar.getDocumentResponseCount() + " DocumentResponse elements");
        }
        DocumentResponseType documentResponse = ar.getDocumentResponseAtIndex(0);
        ResponseType response = documentResponse.getResponse();
        if (documentResponse.getDocumentReferenceCount() != 1) {
            throw invalid("it must refer to exactly one invoice");
        }
        DocumentReferenceType reference = documentResponse.getDocumentReferenceAtIndex(0);
        String invoiceNumber = strip(reference.getIDValue());
        if (invoiceNumber == null) {
            throw invalid("it does not give the invoice number");
        }

        List<InvoiceResponseBuilder.StatusEntry> entries = new ArrayList<>();
        for (StatusType status : response.getStatus()) {
            List<InvoiceResponseBuilder.Condition> conditions = new ArrayList<>();
            for (ConditionType c : status.getCondition()) {
                conditions.add(new InvoiceResponseBuilder.Condition(strip(c.getAttributeIDValue()),
                        c.getDescriptionCount() == 0 ? null : strip(c.getDescriptionAtIndex(0).getValue())));
            }
            entries.add(new InvoiceResponseBuilder.StatusEntry(
                    status.getStatusReasonCode() == null ? null : status.getStatusReasonCode().getListID(),
                    strip(status.getStatusReasonCodeValue()),
                    status.getStatusReasonCount() == 0 ? null : strip(status.getStatusReasonAtIndex(0).getValue()),
                    List.copyOf(conditions)));
        }

        return InvoiceResponse.builder()
                .id(strip(ar.getIDValue()))
                .issueDate(ar.getIssueDateValueLocal())
                .issueTime(ar.getIssueTimeValueLocal())
                .note(ar.getNoteCount() == 0 ? null : strip(ar.getNoteAtIndex(0).getValue()))
                .sender(party(ar.getSenderParty()))
                .receiver(party(ar.getReceiverParty()))
                .status(status(response.getResponseCodeValue()))
                .effectiveDate(response.getEffectiveDateValueLocal())
                .statusEntries(List.copyOf(entries))
                .invoiceNumber(invoiceNumber)
                .invoiceTypeCode(strip(reference.getDocumentTypeCodeValue()))
                .invoiceIssueDate(reference.getIssueDateValueLocal())
                .build();
    }

    private static InvoiceResponseBuilder.Status status(String code) {
        String value = strip(code);
        for (InvoiceResponseBuilder.Status status : InvoiceResponseBuilder.Status.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw invalid("'" + value + "' is not an invoice status code");
    }

    private static InvoiceResponse.Party party(PartyType party) {
        if (party == null) {
            return null;
        }
        String participantId = null;
        if (party.getEndpointID() != null) {
            String scheme = strip(party.getEndpointID().getSchemeID());
            String value = strip(party.getEndpointID().getValue());
            if (scheme != null && value != null) {
                participantId = scheme + ":" + value;
            }
        }
        String name = party.getPartyLegalEntityCount() == 0 ? null
                : strip(party.getPartyLegalEntityAtIndex(0).getRegistrationNameValue());
        return new InvoiceResponse.Party(participantId, name);
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static SthenoException invalid(String why) {
        return new SthenoException(SthenoException.ErrorCode.INVALID_DOCUMENT, "Not a readable Peppol Invoice Response: " + why);
    }
}
