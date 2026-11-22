package io.github.b077as.stheno.inbound;

import lombok.Value;

/**
 * Result of the first, fast step of receiving: the envelope was read and registered (or recognised as a duplicate).
 */
@Value
public class Reception {

    InboundDocument document;
    /** {@code true} if this message was received before (within the store's retention). */
    boolean duplicate;
    /** The earlier record for a duplicate (its outcome is {@code null} if the first delivery is still being handled). */
    ReceivedRecord previous;
}
