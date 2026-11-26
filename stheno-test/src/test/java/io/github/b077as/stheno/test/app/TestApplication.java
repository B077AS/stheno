package io.github.b077as.stheno.test.app;

import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceResponse;
import io.github.b077as.stheno.spring.PeppolInboundHandler;
import io.github.b077as.stheno.spring.PeppolInvoiceResponseHandler;
import io.github.b077as.stheno.spring.PeppolMlsHandler;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import io.github.b077as.stheno.test.FakeAccessPoint;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A small application using the starter the way a real one would: handlers for BIS 3 invoices, Invoice Responses and MLS answers.
 */
@SpringBootApplication
public class TestApplication {

    /** What the invoice handler does; tests replace it. */
    @FunctionalInterface
    public interface Behaviour {
        InboundOutcome handle(InboundDocument document) throws Exception;
    }

    @Component
    public static class Handlers {

        public static final AtomicReference<Behaviour> BEHAVIOUR = new AtomicReference<>();
        public static final List<InboundDocument> INVOICES = new CopyOnWriteArrayList<>();
        public static final List<ReceivedMls> MLS = new CopyOnWriteArrayList<>();
        public static final List<Object> EVENTS = new CopyOnWriteArrayList<>();

        public static final List<InvoiceResponse> RESPONSES = new CopyOnWriteArrayList<>();

        public static void reset() {
            BEHAVIOUR.set(doc -> InboundOutcome.accepted("DK"));
            RESPONSES.clear();
            INVOICES.clear();
            MLS.clear();
            EVENTS.clear();
        }

        @PeppolInboundHandler(documentType = FakeAccessPoint.BIS3_INVOICE_DOCUMENT_TYPE)
        public InboundOutcome invoice(InboundDocument document) throws Exception {
            INVOICES.add(document);
            return BEHAVIOUR.get().handle(document);
        }

        @PeppolMlsHandler
        public void mls(ReceivedMls mls) {
            MLS.add(mls);
        }

        @PeppolInvoiceResponseHandler
        public InboundOutcome response(InvoiceResponse response) {
            RESPONSES.add(response);
            return InboundOutcome.accepted("BE");
        }

        @EventListener
        public void onEvent(SthenoEvents.InvoiceResponseTracked event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.HandlerFailed event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.AccessPointAcknowledged event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.DuplicateReceived event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.RejectionNotDelivered event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.DeliveryDecided event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.MlsForUnknownDocument event) {
            EVENTS.add(event);
        }

        @EventListener
        public void onEvent(SthenoEvents.MlsIgnored event) {
            EVENTS.add(event);
        }
    }
}
