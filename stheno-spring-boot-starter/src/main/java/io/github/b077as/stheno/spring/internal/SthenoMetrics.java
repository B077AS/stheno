package io.github.b077as.stheno.spring.internal;

import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundListener;
import io.github.b077as.stheno.inbound.Reception;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

/**
 * Micrometer counters:
 * <ul>
 * <li>{@code stheno.inbound.received} (tag {@code kind}: {@code document} / {@code mls})</li>
 * <li>{@code stheno.inbound.duplicates}</li>
 * <li>{@code stheno.inbound.handled} (tag {@code outcome}: {@code AP} / {@code AB} / {@code RE} / {@code MLS})</li>
 * <li>{@code stheno.inbound.handler.failures} (tag {@code giving_up})</li>
 * <li>{@code stheno.ap.acknowledgements} (tag {@code result}: {@code ok} / {@code failed})</li>
 * <li>{@code stheno.ap.rejections.undelivered}</li>
 * <li>delivery tracking: {@code stheno.delivery.decided} (tag {@code status}), {@code stheno.delivery.overdue},
 * {@code stheno.delivery.mls.unknown}, {@code stheno.delivery.mls.ignored}, {@code stheno.delivery.responses} (tag
 * {@code status}), {@code stheno.delivery.responses.out-of-sequence}, {@code stheno.delivery.responses.unknown} (counted again when
 * an event is published again)</li>
 * </ul>
 * Document types are not used as tags (unbounded cardinality).
 */
public class SthenoMetrics implements InboundListener {

    private final MeterRegistry registry;

    public SthenoMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onReceived(InboundDocument document) {
        registry.counter("stheno.inbound.received", "kind", document.isMessageLevelStatus() ? "mls" : "document").increment();
    }

    @Override
    public void onDuplicate(Reception reception) {
        registry.counter("stheno.inbound.duplicates").increment();
    }

    @Override
    public void onHandled(HandlingResult result) {
        String outcome = result.getMls() != null ? "MLS" : result.getOutcome().getMlsResponseCode();
        registry.counter("stheno.inbound.handled", "outcome", outcome).increment();
    }

    @EventListener
    public void onHandlerFailed(SthenoEvents.HandlerFailed event) {
        registry.counter("stheno.inbound.handler.failures", "giving_up", Boolean.toString(event.isGivingUp())).increment();
    }

    @EventListener
    public void onRejectionNotDelivered(SthenoEvents.RejectionNotDelivered event) {
        registry.counter("stheno.ap.rejections.undelivered").increment();
    }

    @EventListener
    public void onDeliveryDecided(SthenoEvents.DeliveryDecided event) {
        registry.counter("stheno.delivery.decided", "status", event.getStatus().name()).increment();
    }

    @EventListener
    public void onDeliveryOverdue(SthenoEvents.DeliveryOverdue event) {
        registry.counter("stheno.delivery.overdue").increment();
    }

    @EventListener
    public void onUnknownDocument(SthenoEvents.MlsForUnknownDocument event) {
        registry.counter("stheno.delivery.mls.unknown").increment();
    }

    @EventListener
    public void onMlsIgnored(SthenoEvents.MlsIgnored event) {
        registry.counter("stheno.delivery.mls.ignored").increment();
    }

    @EventListener
    public void onInvoiceResponse(SthenoEvents.InvoiceResponseTracked event) {
        registry.counter("stheno.delivery.responses", "status", event.getResponse().getStatus().name()).increment();
    }

    @EventListener
    public void onResponseOutOfSequence(SthenoEvents.InvoiceResponseOutOfSequence event) {
        registry.counter("stheno.delivery.responses.out-of-sequence").increment();
    }

    @EventListener
    public void onResponseForUnknownInvoice(SthenoEvents.InvoiceResponseForUnknownInvoice event) {
        registry.counter("stheno.delivery.responses.unknown").increment();
    }

    @EventListener
    public void onAcknowledged(SthenoEvents.AccessPointAcknowledged event) {
        registry.counter("stheno.ap.acknowledgements", "result", event.getAcknowledgement().isFailed() ? "failed" : "ok").increment();
    }
}
