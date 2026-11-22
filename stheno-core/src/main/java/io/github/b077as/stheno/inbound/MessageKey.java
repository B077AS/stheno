package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.id.PeppolIds;
import lombok.Value;

/**
 * Identity of a received message for duplicate detection: the SBDH instance identifier (globally unique per envelope, Envelope 2.0.2)
 * together with the normalised receiver participant (case insensitive, Identifier Policy POLICY 2), so that applications serving
 * several receivers stay safe even if two senders reused an identifier.
 */
@Value
public class MessageKey {

    String instanceIdentifier;
    String receiverId;

    public static MessageKey of(InboundDocument document) {
        return new MessageKey(document.getInstanceIdentifier(), PeppolIds.normalizeParticipantId(document.getReceiverId()));
    }

    @Override
    public String toString() {
        return instanceIdentifier + " -> " + receiverId;
    }
}
