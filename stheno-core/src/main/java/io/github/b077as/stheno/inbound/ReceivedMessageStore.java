package io.github.b077as.stheno.inbound;

import java.time.Duration;
import java.util.List;
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

    /**
     * Messages registered longer ago than {@code olderThan} and still without a decision: a handler that is still running, or a
     * handling that was lost (crash). The latter have to be delivered again by the Access Point. At most 100, oldest first.
     * <p>
     * A store shared by several processes, or surviving a restart, should also let {@link #register} succeed again for such a message
     * after a processing timeout.
     */
    default List<ReceivedRecord> findUnfinished(Duration olderThan) {
        return List.of();
    }

    /**
     * Forgets the messages older than the store's retention, to be called regularly. A store that expires its entries by itself does
     * nothing.
     *
     * @return the number of messages forgotten
     */
    default int purgeExpired() {
        return 0;
    }
}
