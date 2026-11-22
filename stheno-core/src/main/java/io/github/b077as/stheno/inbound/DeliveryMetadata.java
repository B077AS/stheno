package io.github.b077as.stheno.inbound;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * What the Access Point told about a delivery, besides the envelope itself (for phoss-ap: the HTTP headers of the forwarding call).
 */
@Value
@Builder(toBuilder = true)
public class DeliveryMetadata {

    /** Name of the delivery channel, e.g. {@code phoss-ap-http} or {@code test}. */
    @Builder.Default
    String source = "unknown";
    /** The Access Point's own transaction id (phoss-ap {@code X-phoss-AP-Transaction-ID}), if sent. */
    String accessPointTransactionId;
    /** SBDH instance id as announced by the Access Point (phoss-ap {@code X-SBDH-Instance-ID}), if sent. */
    String announcedInstanceIdentifier;
    /** The Access Point's verification verdict. */
    @Builder.Default
    VerificationResult verificationResult = VerificationResult.NONE;
    /** The Access Point's verification findings as a JSON array (decoded from {@code X-Verification-Details}), if sent. */
    String verificationDetailsJson;
    /** {@code true} if the Access Point dropped findings to fit them into the header. */
    boolean verificationDetailsTruncated;
    /** All transport headers / metadata entries as received (names in lower case). */
    @Singular
    Map<String, String> headers;

    public static DeliveryMetadata of(String source) {
        return DeliveryMetadata.builder().source(source).build();
    }
}
