package io.github.b077as.stheno.envelope;

import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.sbdh.PeppolSBDHDataReadException;
import com.helger.peppol.sbdh.PeppolSBDHDataReader;
import com.helger.peppol.sbdh.spec12.BinaryContentType;
import com.helger.peppol.sbdh.spec12.TextContentType;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.DeliveryMetadata;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundPayload;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Reads a received Peppol Business Message Envelope (SBDH 2.0.2) with peppol-commons' {@link PeppolSBDHDataReader}, including its
 * value checks, and turns it into an {@link InboundDocument}. XML, binary and text payloads are supported.
 * <p>
 * Thread-safe: a new peppol-commons reader is created per call.
 */
public final class EnvelopeReader {

    /**
     * @param envelope the complete envelope bytes as received
     * @param delivery what the Access Point said about the delivery
     * @throws SthenoException {@link SthenoException.ErrorCode#INVALID_ENVELOPE} if the bytes are no valid Peppol envelope
     */
    public InboundDocument read(byte[] envelope, DeliveryMetadata delivery) {
        if (envelope == null || envelope.length == 0) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE, "The received envelope is empty");
        }

        PeppolSBDHData data;
        try {
            data = new PeppolSBDHDataReader(PeppolIdentifierFactory.INSTANCE).extractData(new ByteArrayInputStream(envelope));
        } catch (PeppolSBDHDataReadException ex) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE,
                    "Not a valid Peppol Business Message Envelope (" + ex.getErrorCode() + "): " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE,
                    "The received data could not be read as a Peppol Business Message Envelope: " + ex.getMessage(), ex);
        }

        return InboundDocument.builder()
                .instanceIdentifier(data.getInstanceIdentifier())
                .senderId(data.getSenderAsIdentifier().getURIEncoded())
                .receiverId(data.getReceiverAsIdentifier().getURIEncoded())
                .documentTypeId(data.getDocumentTypeAsIdentifier().getURIEncoded())
                .processId(data.getProcessAsIdentifier().getURIEncoded())
                .countryC1(data.getCountryC1())
                .mlsTo(data.hasMLSToValue() ? join(data.getMLSToScheme(), data.getMLSToValue()) : null)
                .mlsType(data.hasMLSType() ? data.getMLSType().getID() : null)
                .creationDateTime(data.hasCreationDateAndTime() ? data.getCreationDateAndTime().toOffsetDateTime() : null)
                .standard(data.getStandard())
                .type(data.getType())
                .typeVersion(data.getTypeVersion())
                // Not Map.copyOf: an attribute may have no value (Envelope 2.0.2, chapter 2.7)
                .additionalAttributes(Collections.unmodifiableMap(new LinkedHashMap<>(data.additionalAttributes())))
                .payload(payload(data))
                .rawEnvelope(envelope)
                .delivery(delivery != null ? delivery : DeliveryMetadata.of("unknown"))
                .build();
    }

    private static InboundPayload payload(PeppolSBDHData data) {
        if (data.isNonXMLPayload()) {
            BinaryContentType binary = data.getBusinessMessageAsBinaryContent();
            if (binary != null) {
                return InboundPayload.binary(binary.getValue(), binary.getMimeType(), binary.getEncoding());
            }
            TextContentType text = data.getBusinessMessageAsTextContent();
            if (text != null) {
                return InboundPayload.text(text.getValue(), text.getMimeType());
            }
            throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE,
                    "The envelope declares a non-XML payload that is neither BinaryContent nor TextContent");
        }
        return InboundPayload.xml(data.getBusinessMessage());
    }

    private static String join(String scheme, String value) {
        return scheme != null ? scheme + "::" + value : value;
    }
}
