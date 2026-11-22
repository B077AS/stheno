package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.id.CountryCodes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.List;
import java.util.Objects;

/**
 * What the application decided about a received document. Stheno turns it into the answer for the Access Point: the Message Level
 * Status response code and, for Peppol Reporting, the country of the receiver (C4).
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InboundOutcome {

    /** The decision. */
    public enum Status {
        /** The application took over the document: MLS {@code AP} ("delivered with confirmation"). */
        ACCEPTED,
        /**
         * The application received the document but cannot confirm its acceptance: MLS {@code AB}. Rarely what an application wants;
         * use {@link #ACCEPTED} once the document is stored.
         */
        ACKNOWLEDGED,
        /** The application refuses the document (content problems): MLS {@code RE} with issues; the Access Point must not retry. */
        REJECTED
    }

    Status status;
    /** Country of the receiver (C4) for Peppol Reporting; {@code null} if the application does not know it. */
    String receiverCountry;
    /** Issues for the MLS line responses: mandatory (non empty) for {@link Status#REJECTED}, optional otherwise. */
    List<MlsIssue> issues;
    /** Optional human readable text for the MLS response. */
    String text;

    public static InboundOutcome accepted(String receiverCountry) {
        return new InboundOutcome(Status.ACCEPTED, checkCountry(receiverCountry), List.of(), null);
    }

    public static InboundOutcome accepted(String receiverCountry, String text, List<MlsIssue> warnings) {
        return new InboundOutcome(Status.ACCEPTED, checkCountry(receiverCountry), List.copyOf(warnings), text);
    }

    public static InboundOutcome acknowledged(String receiverCountry) {
        return new InboundOutcome(Status.ACKNOWLEDGED, checkCountry(receiverCountry), List.of(), null);
    }

    public static InboundOutcome rejected(String text, List<MlsIssue> issues) {
        Objects.requireNonNull(issues, "issues");
        if (issues.isEmpty()) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "A rejection needs at least one issue (MLS requires line responses for the response code RE)");
        }
        return new InboundOutcome(Status.REJECTED, null, List.copyOf(issues), text);
    }

    public static InboundOutcome rejected(MlsIssue issue) {
        return rejected(issue.getDescription(), List.of(issue));
    }

    /** The MLS response code for this outcome: {@code AP}, {@code AB} or {@code RE}. */
    public String getMlsResponseCode() {
        return switch (status) {
            case ACCEPTED -> "AP";
            case ACKNOWLEDGED -> "AB";
            case REJECTED -> "RE";
        };
    }

    private static String checkCountry(String country) {
        if (country != null && !CountryCodes.isValidEnvelopeCountry(country)) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "Receiver country '" + country + "' is not an ISO 3166-1 alpha-2 code (upper case, e.g. 'BE')");
        }
        return country;
    }
}
