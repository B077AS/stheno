package io.github.b077as.stheno.ap;

import io.github.b077as.stheno.inbound.DeliveryMetadata;
import io.github.b077as.stheno.inbound.VerificationResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The HTTP contract of phoss-ap's inbound forwarding ({@code forwarding.mode=http_post_sync} or {@code http_post_async}, phoss-ap
 * 0.13+): the request headers it sends and the JSON answers it understands.
 */
public final class PhossApForwarding {

    /** Delivery source name used in {@link DeliveryMetadata#getSource()}. */
    public static final String SOURCE = "phoss-ap-http";

    public static final String HEADER_SBDH_INSTANCE_ID = "X-SBDH-Instance-ID";
    public static final String HEADER_TRANSACTION_ID = "X-phoss-AP-Transaction-ID";
    public static final String HEADER_VERIFICATION_RESULT = "X-Verification-Result";
    public static final String HEADER_VERIFICATION_DETAILS = "X-Verification-Details";
    public static final String HEADER_VERIFICATION_DETAILS_TRUNCATED = "X-Verification-Details-Truncated";

    private PhossApForwarding() {
    }

    /**
     * Builds the delivery metadata from the forwarding request headers (names are matched case insensitively). The base64 encoded
     * verification details are decoded; an undecodable value is kept out rather than failing the delivery.
     */
    public static DeliveryMetadata metadataFromHeaders(Map<String, String> headers) {
        Map<String, String> lower = new LinkedHashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));

        String details = lower.get(HEADER_VERIFICATION_DETAILS.toLowerCase(Locale.ROOT));
        String decoded = null;
        if (details != null && !details.isBlank()) {
            try {
                decoded = new String(Base64.getDecoder().decode(details.strip()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                decoded = null;
            }
        }
        return DeliveryMetadata.builder()
                .source(SOURCE)
                .accessPointTransactionId(lower.get(HEADER_TRANSACTION_ID.toLowerCase(Locale.ROOT)))
                .announcedInstanceIdentifier(lower.get(HEADER_SBDH_INSTANCE_ID.toLowerCase(Locale.ROOT)))
                .verificationResult(VerificationResult.fromHeader(lower.get(HEADER_VERIFICATION_RESULT.toLowerCase(Locale.ROOT))))
                .verificationDetailsJson(decoded)
                .verificationDetailsTruncated("true".equalsIgnoreCase(lower.get(HEADER_VERIFICATION_DETAILS_TRUNCATED.toLowerCase(Locale.ROOT))))
                .headers(lower)
                .build();
    }

    /**
     * Answer for {@code http_post_sync}: the document was received. phoss-ap reads {@code countryCodeC4} (may be {@code null}: then the
     * Access Point determines the country another way or waits for {@code /api/inbound/report}).
     */
    public static String syncReceived(String receiverCountry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("countryCodeC4", receiverCountry);
        body.put("status", "received");
        return Json.write(body);
    }

    /**
     * Answer for {@code http_post_sync}: the document can never be processed. phoss-ap stops retrying and sends an MLS {@code RE}
     * itself (without the application's issue details).
     */
    public static String syncRejectedNoRetry(String errorMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("retry", "none");
        body.put("errorMessage", errorMessage);
        return Json.write(body);
    }
}
