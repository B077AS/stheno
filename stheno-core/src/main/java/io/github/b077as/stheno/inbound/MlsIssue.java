package io.github.b077as.stheno.inbound;

import lombok.Value;

import java.util.Objects;

/**
 * One problem reported in a Message Level Status: becomes one line response of the MLS.
 */
@Value
public class MlsIssue {

    /** Location value for issues without a specific location in the document. */
    public static final String NO_LOCATION = "NA";

    /** MLS status reason codes usable by a receiving application. */
    public enum Reason {
        /** Business rule violation, fatal. */
        BV,
        /** Business rule violation, warning (cannot cause a rejection on its own). */
        BW,
        /** Syntax violation. */
        SV,
        /** Failure of delivery: the document cannot be passed on to its final destination. */
        FD
    }

    Reason reason;
    /** XPath of the error location in the business document, or {@link #NO_LOCATION}. */
    String location;
    /** Human readable description. */
    String description;

    public MlsIssue(Reason reason, String location, String description) {
        this.reason = Objects.requireNonNull(reason, "reason");
        this.location = location == null || location.isBlank() ? NO_LOCATION : location;
        this.description = Objects.requireNonNull(description, "description");
    }

    public static MlsIssue businessRule(String location, String description) {
        return new MlsIssue(Reason.BV, location, description);
    }

    public static MlsIssue warning(String location, String description) {
        return new MlsIssue(Reason.BW, location, description);
    }

    public static MlsIssue syntax(String location, String description) {
        return new MlsIssue(Reason.SV, location, description);
    }

    public static MlsIssue deliveryFailure(String description) {
        return new MlsIssue(Reason.FD, NO_LOCATION, description);
    }
}
