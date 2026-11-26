package io.github.b077as.stheno.delivery;

/**
 * What a {@link DeliveryTracker} did with a received Invoice Response.
 */
public enum ResponseCorrelation {
    /** It belongs to a sent invoice and is part of its status. */
    APPLIED,
    /** It belongs to a sent invoice but does not follow the responses before it: kept, the status is unchanged. */
    OUT_OF_SEQUENCE,
    /** No sent invoice matches it (yet): kept in case the invoice is registered later. */
    UNKNOWN_INVOICE
}
