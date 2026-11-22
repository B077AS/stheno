package io.github.b077as.stheno.test;

import lombok.Value;

/**
 * A {@code POST /api/inbound/report} call the application made to the {@link FakeAccessPoint}.
 */
@Value
public class RecordedCountryReport {

    String instanceIdentifier;
    String receiverCountry;
    String token;
}
