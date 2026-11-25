package io.github.b077as.stheno.delivery;

/**
 * What the {@link DeliveryTracker} did with a received MLS.
 */
public enum MlsCorrelation {
    /** It decides about a registered document (also reported when the same MLS is processed again). */
    DECIDED,
    /** No document with that identifier is registered (yet); the MLS is kept. */
    UNKNOWN_DOCUMENT,
    /** The document already has another MLS; only the first one counts. */
    IGNORED
}
