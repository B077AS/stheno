package io.github.b077as.stheno.lookup;

import lombok.Value;

import java.time.Instant;

/**
 * Answer to "can this participant receive this document type in this process over Peppol?".
 */
@Value
public class ReceiverCapability {

    /** The answer. */
    public enum Status {
        /** The participant is registered and has a Peppol AS4 endpoint for this document type and process. */
        CAN_RECEIVE,
        /** The participant is not registered in the Peppol network (no SML/DNS entry). */
        NOT_REGISTERED,
        /** The participant is registered but does not accept this document type. */
        DOCUMENT_TYPE_NOT_SUPPORTED,
        /** The document type is accepted, but not in this process or not over Peppol AS4. */
        PROCESS_NOT_SUPPORTED
    }

    String participantId;
    String documentTypeId;
    String processId;
    Status status;
    /** URL of the receiving Access Point, for {@link Status#CAN_RECEIVE}; informative only (Stheno never sends). */
    String endpointUrl;
    Instant checkedAt;

    public boolean canReceive() {
        return status == Status.CAN_RECEIVE;
    }
}
