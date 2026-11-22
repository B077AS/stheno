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
    public void onAcknowledged(SthenoEvents.AccessPointAcknowledged event) {
        registry.counter("stheno.ap.acknowledgements", "result", event.getAcknowledgement().isFailed() ? "failed" : "ok").increment();
    }
}
