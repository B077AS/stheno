package io.github.b077as.stheno.inbound;

import lombok.Value;
import lombok.With;

import java.time.Instant;

/**
 * What the {@link ReceivedMessageStore} remembers about a received message.
 */
@Value
@With
public class ReceivedRecord {

    MessageKey key;
    Instant receivedAt;
    /** The application's decision, or {@code null} while the message is still being handled. */
    InboundOutcome outcome;

    public boolean isCompleted() {
        return outcome != null;
    }
}
