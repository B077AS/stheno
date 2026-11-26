package io.github.b077as.stheno.spring.internal;

import io.github.b077as.stheno.delivery.DeliveryListener;
import io.github.b077as.stheno.delivery.DeliveryStatus;
import io.github.b077as.stheno.delivery.ResponseHistory;
import io.github.b077as.stheno.delivery.TrackedDocument;
import io.github.b077as.stheno.delivery.TrackedResponse;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes what the delivery tracker reports as Spring events. Exceptions of event listeners pass through on purpose, so that the
 * tracker tells them again.
 */
public class SpringEventDeliveryListener implements DeliveryListener {

    private final ApplicationEventPublisher publisher;

    public SpringEventDeliveryListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onDecided(TrackedDocument document, DeliveryStatus status) {
        publisher.publishEvent(new SthenoEvents.DeliveryDecided(document, status));
    }

    @Override
    public void onOverdue(TrackedDocument document) {
        publisher.publishEvent(new SthenoEvents.DeliveryOverdue(document));
    }

    @Override
    public void onUnknownDocument(ReceivedMls mls) {
        publisher.publishEvent(new SthenoEvents.MlsForUnknownDocument(mls));
    }

    @Override
    public void onIgnored(TrackedDocument document, ReceivedMls ignored) {
        publisher.publishEvent(new SthenoEvents.MlsIgnored(document, ignored));
    }

    @Override
    public void onInvoiceResponse(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
        publisher.publishEvent(new SthenoEvents.InvoiceResponseTracked(document, response, history));
    }

    @Override
    public void onResponseOutOfSequence(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
        publisher.publishEvent(new SthenoEvents.InvoiceResponseOutOfSequence(document, response, history));
    }

    @Override
    public void onResponseForUnknownInvoice(InvoiceReference invoice, TrackedResponse response) {
        publisher.publishEvent(new SthenoEvents.InvoiceResponseForUnknownInvoice(invoice, response));
    }
}
