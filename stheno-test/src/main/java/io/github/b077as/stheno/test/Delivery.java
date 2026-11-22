package io.github.b077as.stheno.test;

import lombok.Value;

import java.util.Map;

/**
 * The application's answer to one forwarding call of the {@link FakeAccessPoint}.
 */
@Value
public class Delivery {

    /** SBDH instance identifier of the delivered envelope. */
    String instanceIdentifier;
    int status;
    String body;

    /** {@code true} for a 2xx answer, which phoss-ap counts as "delivered". */
    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    /** {@code countryCodeC4} of a sync forwarding answer, or {@code null}. */
    public String getCountryCodeC4() {
        return field("countryCodeC4");
    }

    /** {@code true} if the answer tells phoss-ap not to retry ({@code "retry":"none"}, sync forwarding). */
    public boolean isNoRetry() {
        return "none".equals(field("retry"));
    }

    /** A top level string field of the JSON answer, or {@code null}. */
    public String field(String name) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> json = MiniJson.parseObject(body);
            Object v = json.get(name);
            return v == null ? null : v.toString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
