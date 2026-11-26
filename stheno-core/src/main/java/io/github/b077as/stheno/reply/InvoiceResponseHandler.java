package io.github.b077as.stheno.reply;

import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundHandler;
import io.github.b077as.stheno.inbound.InboundOutcome;

import java.util.Objects;

/**
 * Application code for received Invoice Responses. A response is a business document like any other: the handler decides its
 * {@link InboundOutcome} (the MLS verdict and the receiver country). Note that a buyer rejecting your invoice
 * ({@link InvoiceResponseBuilder.Status#RE}) is a response you accept.
 * <p>
 * Without Spring, add it to the router with
 * {@code router.when(InvoiceResponseReader::isInvoiceResponse, InvoiceResponseHandler.asInboundHandler(handler))}.
 */
@FunctionalInterface
public interface InvoiceResponseHandler {

    InboundOutcome handle(InvoiceResponse response, InboundDocument document) throws Exception;

    /**
     * The handler as an {@link InboundHandler} that reads the response first. A response that cannot be read fails like a handler
     * exception: nothing is decided and it can be delivered again.
     */
    static InboundHandler asInboundHandler(InvoiceResponseHandler handler) {
        Objects.requireNonNull(handler, "handler");
        InvoiceResponseReader reader = new InvoiceResponseReader();
        return document -> handler.handle(reader.read(document), document);
    }
}
