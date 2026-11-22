package io.github.b077as.stheno.inbound;

/**
 * Observer of the inbound flow (logging, metrics, Spring events). All methods are optional. Exceptions thrown by a listener are
 * ignored by the processor.
 */
public interface InboundListener {

    default void onReceived(InboundDocument document) {
    }

    default void onDuplicate(Reception reception) {
    }

    default void onHandled(HandlingResult result) {
    }

    default void onHandlerFailed(HandlingResult result) {
    }
}
