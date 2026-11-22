package io.github.b077as.stheno.envelope;

import lombok.Value;

import java.nio.charset.StandardCharsets;

/**
 * An envelope built by {@link EnvelopeBuilder}, ready to be handed to the application's Access Point (Stheno never transmits it).
 */
@Value
public class BuiltEnvelope {

    /** The SBDH instance identifier of this envelope (correlates the MLS answer later). */
    String instanceIdentifier;
    /** The complete XML of the envelope (UTF-8). Do not modify the array. */
    byte[] xml;

    public String getXmlAsString() {
        return new String(xml, StandardCharsets.UTF_8);
    }
}
