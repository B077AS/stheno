package io.github.b077as.stheno.inbound;

/**
 * Application code that is told about a Message Level Status received for a document the application sent earlier. An MLS is never
 * answered with another MLS, so there is no decision to return.
 */
@FunctionalInterface
public interface MlsHandler {

    void handle(ReceivedMls mls, InboundDocument document) throws Exception;
}
