package io.github.b077as.stheno.id;

import lombok.Value;

import java.util.List;

/**
 * Result of checking one Peppol identifier.
 */
@Value
public class IdCheck {

    /**
     * The identifier in its URI form ({@code scheme::value}). {@code null} when the identifier could not be parsed at all.
     */
    String normalized;
    /** Problems that make the identifier unusable. Empty when {@link #isValid()}. */
    List<String> errors;
    /** Things worth knowing that do not make the identifier unusable (e.g. a deprecated scheme in a received document). */
    List<String> warnings;

    public boolean isValid() {
        return errors.isEmpty();
    }

    static IdCheck of(String normalized, List<String> errors, List<String> warnings) {
        return new IdCheck(normalized, List.copyOf(errors), List.copyOf(warnings));
    }
}
