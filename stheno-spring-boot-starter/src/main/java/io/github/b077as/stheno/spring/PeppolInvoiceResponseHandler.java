package io.github.b077as.stheno.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method that receives Invoice Responses: a buyer's business answers to invoices the application sent. Signature:
 * <pre>
 * InboundOutcome response(InvoiceResponse response)
 * InboundOutcome response(InvoiceResponse response, InboundDocument document)
 * </pre>
 * A response is a business document like any other, so the method decides its outcome. A buyer rejecting an invoice
 * ({@code response.getStatus() == RE}) is a response to <b>accept</b>. At most one such method, and then no
 * {@link PeppolInboundHandler} for the Invoice Response document type.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeppolInvoiceResponseHandler {
}
