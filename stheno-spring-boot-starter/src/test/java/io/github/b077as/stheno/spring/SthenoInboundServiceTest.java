package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.ap.ApCallResult;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeBuilder;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.InboundRouter;
import io.github.b077as.stheno.inbound.MessageKey;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.ReceivedMessageStore;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class SthenoInboundServiceTest {

    private static final String DOCUMENT_TYPE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017"
            + "#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";

    private final List<Object> events = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SthenoAutoConfiguration.class))
            .withInitializer(context -> context.addApplicationListener((ApplicationListener<PayloadApplicationEvent<?>>) e ->
                    events.add(e.getPayload())));

    @Test
    void documentWaitingForARetryStaysRegisteredAsUnfinished() {
        AtomicInteger calls = new AtomicInteger();
        runner.withBean(InboundRouter.class, () -> InboundRouter.builder().fallback(d -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("database down");
                }).build())
                .withPropertyValues("stheno.inbound.handler-retry-delay=1h")
                .run(context -> {
                    SthenoInboundService service = context.getBean(SthenoInboundService.class);
                    BuiltEnvelope env = envelope();
                    assertThat(service.accept(env.getXml(), Map.of()).getStatus()).isEqualTo(200);
                    await(e -> e instanceof SthenoEvents.HandlerFailed);

                    ReceivedMessageStore store = context.getBean(ReceivedMessageStore.class);
                    MessageKey key = new MessageKey(env.getInstanceIdentifier(), "iso6523-actorid-upis::0088:5798000000001");
                    waitUntil(() -> store.find(key).isPresent());
                    assertThat(store.find(key)).get().matches(r -> !r.isCompleted());
                    assertThat(service.accept(env.getXml(), Map.of()).getStatus()).isEqualTo(503);
                    assertThat(service.getPendingCount()).isEqualTo(1);
                    assertThat(calls).hasValue(1);
                });
    }

    @Test
    void documentNoThreadCanTakeIsNotConfirmed() {
        runner.withBean(InboundRouter.class, () -> InboundRouter.builder().fallback(d -> InboundOutcome.accepted(null)).build())
                .withBean(SthenoAutoConfiguration.HANDLER_EXECUTOR_BEAN, Executor.class, () -> task -> {
                    throw new RejectedExecutionException("queue full");
                })
                .run(context -> {
                    SthenoInboundService service = context.getBean(SthenoInboundService.class);
                    BuiltEnvelope env = envelope();
                    assertThat(service.accept(env.getXml(), Map.of()).getStatus()).isEqualTo(503);
                    // Forgotten again: the next delivery is not answered as a duplicate
                    assertThat(service.accept(env.getXml(), Map.of()).getBody()).contains("temporarily unavailable");
                    assertThat(service.getPendingCount()).isZero();
                });
    }

    @Test
    void lostDocumentsAreReported() {
        runner.withPropertyValues("stheno.inbound.processing-timeout=100ms")
                .run(context -> {
                    // As left behind by a handling that never finished
                    context.getBean(ReceivedMessageStore.class).register(new MessageKey("lost-1", "iso6523-actorid-upis::0088:abc"));
                    await(e -> e instanceof SthenoEvents.DocumentsUnfinished u
                            && u.getDocuments().get(0).getKey().getInstanceIdentifier().equals("lost-1"));
                });
    }

    @Test
    void rejectionWithoutMlsVerdictIsReported() {
        runner.withBean(InboundRouter.class, () -> InboundRouter.builder()
                        .fallback(d -> InboundOutcome.rejected(MlsIssue.syntax(null, "not valid"))).build())
                .run(context -> {
                    context.getBean(SthenoInboundService.class).accept(envelope().getXml(), Map.of());
                    await(e -> e instanceof SthenoEvents.RejectionNotDelivered r && r.getMlsVerdict() == ApCallResult.SKIPPED);
                });
    }

    @Test
    void unhandledDocumentsCanBeRejected() {
        runner.withPropertyValues("stheno.inbound.unhandled-documents=reject")
                .run(context -> {
                    assertThat(context.getBean(SthenoInboundService.class).accept(envelope().getXml(), Map.of()).getStatus())
                            .isEqualTo(200);
                    await(e -> e instanceof SthenoEvents.DocumentHandled h && h.getResult().getOutcome().getMlsResponseCode().equals("RE"));
                });
    }

    private void await(Predicate<Object> event) {
        waitUntil(() -> events.stream().anyMatch(event));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached in time").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static BuiltEnvelope envelope() {
        return EnvelopeBuilder.create()
                .instanceIdentifier(UUID.randomUUID().toString())
                .sender("0208:0123456749")
                .receiver("0088:5798000000001")
                .documentType(DOCUMENT_TYPE)
                .process("urn:fdc:peppol.eu:2017:poacc:billing:01:1.0")
                .senderCountry("BE")
                .payload(invoiceElement())
                .build();
    }

    private static Element invoiceElement() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            String xml = "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>";
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
