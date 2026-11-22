package io.github.b077as.stheno.inbound;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A document received from the application's Access Point: the data of the Peppol Business Message Envelope (SBDH), the payload and
 * what the Access Point said about the delivery.
 * <p>
 * Identifiers are given in their URI form ({@code scheme::value}).
 */
@Value
@Builder(toBuilder = true)
public class InboundDocument {

    /** SBDH {@code DocumentIdentification/InstanceIdentifier}: globally unique per envelope (not the invoice number). */
    String instanceIdentifier;
    /** Sender participant (C1), e.g. {@code iso6523-actorid-upis::0088:5798000000001}. */
    String senderId;
    /** Receiver participant (C4). */
    String receiverId;
    /** Document type identifier, e.g. {@code busdox-docid-qns::urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##...}. */
    String documentTypeId;
    /** Process identifier, e.g. {@code cenbii-procid-ubl::urn:fdc:peppol.eu:2017:poacc:billing:01:1.0}. */
    String processId;
    /** Country of the sender (C1), envelope scope {@code COUNTRY_C1}. */
    String countryC1;
    /** Alternative MLS receiver requested by the sender (envelope scope {@code MLS_TO}), or {@code null}. */
    String mlsTo;
    /** MLS usage requested by the sender (envelope scope {@code MLS_TYPE}: {@code FAILURE_ONLY} or {@code ALWAYS_SEND}), or {@code null}. */
    String mlsType;
    /** Envelope creation time (not the issue date of the business document). */
    OffsetDateTime creationDateTime;
    /** SBDH {@code Standard}: namespace of the business document root element. */
    String standard;
    /** SBDH {@code Type}: local name of the business document root element. */
    String type;
    /** SBDH {@code TypeVersion}, e.g. {@code 2.1}. */
    String typeVersion;
    /** Additional envelope attributes (keys are case sensitive). */
    Map<String, String> additionalAttributes;
    /** The business payload. */
    InboundPayload payload;
    /** The complete envelope as received. Do not modify the array. */
    byte[] rawEnvelope;
    /** What the Access Point said about the delivery. */
    DeliveryMetadata delivery;

    /**
     * {@code true} if this is a Peppol Message Level Status (MLS): the answer of another Access Point to a document this application sent.
     * Recognised by the MLS process identifier {@code urn:peppol:edec:mls}.
     */
    public boolean isMessageLevelStatus() {
        return processId != null && processId.endsWith("::" + PeppolDocumentKinds.MLS_PROCESS_ID);
    }
}
