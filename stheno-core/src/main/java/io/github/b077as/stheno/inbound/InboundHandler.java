package io.github.b077as.stheno.inbound;

/**
 * Application code that processes a received business document and says whether it accepts it.
 * <p>
 * Throwing an exception means "could not process right now" (e.g. the database is down): no decision is recorded, the message is
 * released for a new delivery and no acknowledgement is sent to the Access Point. Return {@link InboundOutcome#rejected} instead when
 * the document itself is unacceptable.
 */
@FunctionalInterface
public interface InboundHandler {

    InboundOutcome handle(InboundDocument document) throws Exception;
}
