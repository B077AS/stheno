package io.github.b077as.stheno.inbound;

import lombok.Value;

/**
 * Result of handing a received document to the application.
 */
@Value
public class HandlingResult {

    /** How it went. */
    public enum Status {
        /** The application decided ({@link #getOutcome()} is set). */
        HANDLED,
        /** The message was received before; the application was not called again ({@link #getOutcome()} is the earlier one, if known). */
        DUPLICATE,
        /** The application failed with an exception ({@link #getFailure()}); nothing was decided and the message was released. */
        FAILED
    }

    Status status;
    InboundDocument document;
    InboundOutcome outcome;
    /** The parsed MLS if the document was a Message Level Status. */
    ReceivedMls mls;
    Throwable failure;

    /** {@code true} if an acknowledgement (C4 country, MLS verdict) may be sent to the Access Point for this result. */
    public boolean isAcknowledgeable() {
        return status == Status.HANDLED && outcome != null && !document.isMessageLevelStatus();
    }
}
