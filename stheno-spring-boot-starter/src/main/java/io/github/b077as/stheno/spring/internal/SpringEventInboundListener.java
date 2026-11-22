package io.github.b077as.stheno.spring.internal;

import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundListener;
import io.github.b077as.stheno.inbound.Reception;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes the inbound flow as Spring application events ({@link SthenoEvents}). Handler failures are published by the inbound
 * service, which knows about retries.
 */
public class SpringEventInboundListener implements InboundListener {

    private final ApplicationEventPublisher publisher;

    public SpringEventInboundListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onReceived(InboundDocument document) {
        publisher.publishEvent(new SthenoEvents.DocumentReceived(document));
    }

    @Override
    public void onDuplicate(Reception reception) {
        publisher.publishEvent(new SthenoEvents.DuplicateReceived(reception));
    }

    @Override
    public void onHandled(HandlingResult result) {
        publisher.publishEvent(new SthenoEvents.DocumentHandled(result));
        if (result.getMls() != null) {
            publisher.publishEvent(new SthenoEvents.MlsReceived(result.getMls(), result.getDocument()));
        }
    }
}
