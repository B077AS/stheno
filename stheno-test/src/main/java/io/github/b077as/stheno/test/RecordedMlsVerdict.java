package io.github.b077as.stheno.test;

import io.github.b077as.stheno.inbound.MlsIssue;
import lombok.Value;

import java.util.List;

/**
 * A {@code POST /api/mls/send} call the application made to the {@link FakeAccessPoint}.
 */
@Value
public class RecordedMlsVerdict {

    String instanceIdentifier;
    /** {@code AP}, {@code AB} or {@code RE}. */
    String responseCode;
    String responseText;
    List<MlsIssue> issues;
    /** The {@code X-Token} header the application sent. */
    String token;

    public boolean isAccepted() {
        return "AP".equals(responseCode);
    }

    public boolean isRejected() {
        return "RE".equals(responseCode);
    }
}
