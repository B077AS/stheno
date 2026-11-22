package io.github.b077as.stheno.inbound;

import java.util.Optional;

/**
 * Remembers received messages so that a document delivered again (Access Point retries, replays, duplicate deliveries) is handed to
 * the application only once.
 * <p>
 * Implementations must make {@link #register} atomic: of two concurrent registrations of the same key exactly one may succeed. Use a
 * shared implementation (e.g. a database with a unique key) when the application runs on several nodes.
 */
public interface ReceivedMessageStore {

    /**
     * Registers a message as received.
     *
     * @return empty if the message is new (the caller now owns it), otherwise the existing record
     */
    Optional<ReceivedRecord> register(MessageKey key);

    /** Stores the application's decision for a registered message. */
    void complete(MessageKey key, InboundOutcome outcome);

    /** Forgets a registered message whose handling failed, so that a new delivery of it is handled again. */
    void release(MessageKey key);

    /** Looks up a message. */
    Optional<ReceivedRecord> find(MessageKey key);
}
