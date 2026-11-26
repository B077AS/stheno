package io.github.b077as.stheno.inbound;

/**
 * Follows the documents the application sent through what comes back (normally a {@code DeliveryTracker}): it is told about every
 * received MLS and about every business document the application took over.
 */
public interface DeliveryTracking extends MlsHandler {

    /**
     * Called after the application's handler took a business document over ({@code AP} or {@code AB}, not for a rejected one). If it
     * throws, nothing is recorded, the document can be delivered again and the handler is called again.
     */
    default void accepted(InboundDocument document) throws Exception {
    }
}
