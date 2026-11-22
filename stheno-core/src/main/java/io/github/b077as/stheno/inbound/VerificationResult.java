package io.github.b077as.stheno.inbound;

import java.util.Locale;

/**
 * What the Access Point itself concluded when it verified the document before forwarding it (phoss-ap header
 * {@code X-Verification-Result}).
 */
public enum VerificationResult {
    /** The Access Point verified the document and it passed. */
    PASSED,
    /** The Access Point rejected the document but forwarded it anyway (phoss-ap {@code verification.inbound.rejection-forwarding}). */
    REJECTED,
    /** The Access Point could not verify the document (e.g. its validation service was down). */
    UNVERIFIED,
    /** The Access Point sent no verdict (verification disabled, or not a phoss-ap delivery). */
    NONE;

    /** Maps the phoss-ap header value ({@code passed}, {@code rejected}, {@code unverified}); anything else gives {@link #NONE}. */
    public static VerificationResult fromHeader(String value) {
        if (value == null) {
            return NONE;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "passed" -> PASSED;
            case "rejected" -> REJECTED;
            case "unverified" -> UNVERIFIED;
            default -> NONE;
        };
    }
}
