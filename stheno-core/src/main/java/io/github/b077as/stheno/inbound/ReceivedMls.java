package io.github.b077as.stheno.inbound;

import lombok.Value;

import java.util.List;

/**
 * A Peppol Message Level Status received for a document the application sent: the other side's Access Point says whether the document
 * was delivered ({@code AP}), delivered without confirmation ({@code AB}) or rejected ({@code RE}).
 */
@Value
public class ReceivedMls {

    /** {@code AP}, {@code AB} or {@code RE}. */
    String responseCode;
    /** SBDH instance identifier of the document this MLS answers (see {@code BuiltEnvelope#getInstanceIdentifier()}). */
    String referencedInstanceIdentifier;
    /** Optional response text. */
    String responseText;
    /** Line responses (reason, location, description). */
    List<MlsIssue> issues;
    /** Id of the MLS document itself. */
    String mlsId;
    /** Service provider that sent the MLS (normally the receiver's Access Point, a {@code 0242} SPID). */
    String senderId;

    public boolean isAccepted() {
        return "AP".equals(responseCode);
    }

    public boolean isRejected() {
        return "RE".equals(responseCode);
    }
}
