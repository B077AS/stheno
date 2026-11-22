package io.github.b077as.stheno.spring.internal;

import io.github.b077as.stheno.spring.SthenoInboundService;
import io.github.b077as.stheno.spring.SthenoProperties;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Health details of the inbound flow. Always {@code UP} (the endpoint keeps accepting documents); failing acknowledgements to the
 * Access Point are shown in the details ({@code consecutiveAcknowledgementFailures}) so that monitoring can alert on them.
 */
public class SthenoHealthIndicator extends AbstractHealthIndicator {

    private final SthenoInboundService service;
    private final HandlerMethodRegistrar registrar;
    private final SthenoProperties properties;

    public SthenoHealthIndicator(SthenoInboundService service, HandlerMethodRegistrar registrar, SthenoProperties properties) {
        this.service = service;
        this.registrar = registrar;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("handlers", registrar.getHandlerCount())
                .withDetail("pendingDocuments", service.getPendingCount())
                .withDetail("forwardingMode", properties.getInbound().getForwardingMode())
                .withDetail("processing", properties.getInbound().getProcessing())
                .withDetail("acknowledging", service.isAcknowledging())
                .withDetail("reportReceiverCountry", service.isAcknowledging() && properties.getAccessPoint().isReportReceiverCountry())
                .withDetail("mlsVerdict", service.isAcknowledging() && properties.getAccessPoint().isMlsVerdict())
                .withDetail("consecutiveAcknowledgementFailures", service.getConsecutiveAcknowledgementFailures());
        if (service.getLastAcknowledgementFailure() != null) {
            builder.withDetail("lastAcknowledgementFailure", service.getLastAcknowledgementFailure().toString());
        }
    }
}
