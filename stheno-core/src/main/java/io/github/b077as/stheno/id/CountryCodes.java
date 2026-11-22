package io.github.b077as.stheno.id;

import java.util.regex.Pattern;

/**
 * Country code rules of the Peppol Business Message Envelope 2.0.2, chapter 2.5: ISO 3166-1 alpha-2, case sensitive regular expression
 * {@code [A-Z0-9][A-Z0-9]}; Kosovo may be {@code XK} or {@code 1A}.
 */
public final class CountryCodes {

    private static final Pattern ENVELOPE_COUNTRY = Pattern.compile("[A-Z0-9][A-Z0-9]");

    private CountryCodes() {
    }

    /** {@code true} if the value may be used as {@code COUNTRY_C1} (and as the C4 country reported to the Access Point). */
    public static boolean isValidEnvelopeCountry(String code) {
        return code != null && ENVELOPE_COUNTRY.matcher(code).matches();
    }
}
