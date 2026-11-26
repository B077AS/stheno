package io.github.b077as.stheno.test;

import io.github.b077as.stheno.ap.ApCallResult;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeBuilder;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.VerificationResult;
import io.github.b077as.stheno.lookup.PeppolLookup;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import io.github.b077as.stheno.test.app.TestApplication;
import io.github.b077as.stheno.test.app.TestApplication.Handlers;
import io.github.b077as.stheno.validation.DocumentValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Default settings: phoss-ap {@code http_post_async} forwarding, handlers in the background, both acknowledgements on.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.show-details=always")
class BackgroundAsyncEndToEndTest {

    private static final FakeAccessPoint AP = FakeAccessPoint.start();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void stheno(DynamicPropertyRegistry registry) {
        AP.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @AfterAll
    static void stop() {
        AP.close();
    }

    @BeforeEach
    void setUp() {
        AP.reset();
        Handlers.reset();
        AP.forwardTo(URI.create("http://localhost:" + port + "/peppol/inbound"));
    }

    @Test
    void acceptedInvoiceGetsMlsApAndReceiverCountry() {
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(d.getStatus()).isEqualTo(200);

        RecordedMlsVerdict verdict = AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT);
        assertThat(verdict.isAccepted()).isTrue();
        assertThat(verdict.getToken()).isEqualTo(FakeAccessPoint.DEFAULT_TOKEN);
        assertThat(AP.awaitCountryReport(d.getInstanceIdentifier(), WAIT).getReceiverCountry()).isEqualTo("DK");
        assertThat(Handlers.INVOICES).singleElement().satisfies(doc -> {
            assertThat(doc.getSenderId()).isEqualTo("iso6523-actorid-upis::" + FakeAccessPoint.SAMPLE_SENDER);
            assertThat(doc.getPayload().getRootName()).isEqualTo("Invoice");
            assertThat(doc.getDelivery().getAnnouncedInstanceIdentifier()).isEqualTo(d.getInstanceIdentifier());
        });
    }

    @Test
    void rejectedInvoiceGetsMlsReWithIssuesAndNoCountry() {
        Handlers.BEHAVIOUR.set(doc -> InboundOutcome.rejected("Unknown buyer reference",
                List.of(MlsIssue.businessRule("/Invoice/cbc:BuyerReference", "PO-4711 is not one of our orders"))));
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());

        RecordedMlsVerdict verdict = AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT);
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.getResponseText()).isEqualTo("Unknown buyer reference");
        assertThat(verdict.getIssues()).singleElement().satisfies(i -> {
            assertThat(i.getReason()).isEqualTo(MlsIssue.Reason.BV);
            assertThat(i.getLocation()).isEqualTo("/Invoice/cbc:BuyerReference");
        });
        assertThat(AP.getCountryReports()).isEmpty();
    }

    @Test
    void duplicateDeliveryReachesTheHandlerOnce() {
        BuiltEnvelope env = FakeAccessPoint.sampleInvoiceEnvelope();
        assertThat(AP.deliver(env).getStatus()).isEqualTo(200);
        AP.awaitMlsVerdict(env.getInstanceIdentifier(), WAIT);
        assertThat(AP.deliver(env).getStatus()).isEqualTo(200);

        assertThat(AP.noMlsVerdictWithin("other", Duration.ofMillis(300))).isTrue();
        assertThat(Handlers.INVOICES).hasSize(1);
        assertThat(AP.getMlsVerdicts()).hasSize(1);
        assertThat(Handlers.EVENTS).anyMatch(e -> e instanceof SthenoEvents.DuplicateReceived);
    }

    @Test
    void duplicateIsNotConfirmedWhileTheFirstDeliveryIsUndecided() throws Exception {
        CountDownLatch handlerMayFinish = new CountDownLatch(1);
        Handlers.BEHAVIOUR.set(doc -> {
            handlerMayFinish.await(10, TimeUnit.SECONDS);
            return InboundOutcome.accepted("DK");
        });
        BuiltEnvelope env = FakeAccessPoint.sampleInvoiceEnvelope();
        assertThat(AP.deliver(env).getStatus()).isEqualTo(200);
        assertThat(AP.deliver(env).getStatus()).isEqualTo(503);

        handlerMayFinish.countDown();
        AP.awaitMlsVerdict(env.getInstanceIdentifier(), WAIT);
        assertThat(AP.deliver(env).getStatus()).isEqualTo(200);
        assertThat(Handlers.INVOICES).hasSize(1);
    }

    @Test
    void documentWithoutHandlerIsLeftToTheAccessPoint() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element creditNoteRoot = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream("<CreditNote xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2\"/>"
                        .getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        BuiltEnvelope creditNote = EnvelopeBuilder.create()
                .sender(FakeAccessPoint.SAMPLE_SENDER)
                .receiver(FakeAccessPoint.SAMPLE_RECEIVER)
                .documentType("urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2::CreditNote##urn:cen.eu:en16931:2017"
                        + "#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1")
                .process(FakeAccessPoint.BIS3_BILLING_PROCESS)
                .senderCountry("BE")
                .payload(creditNoteRoot)
                .build();
        assertThat(AP.deliver(creditNote).getStatus()).isEqualTo(503);
        // Nothing was remembered: the next delivery is not a duplicate
        assertThat(AP.deliver(creditNote).getStatus()).isEqualTo(503);
        assertThat(AP.noMlsVerdictWithin(creditNote.getInstanceIdentifier(), Duration.ofMillis(300))).isTrue();
    }

    @Test
    void rejectionAfterTheAccessPointDecidedTheMlsIsReported() {
        AP.respondToMlsVerdictWith(409);
        Handlers.BEHAVIOUR.set(doc -> InboundOutcome.rejected(MlsIssue.businessRule(null, "BR-01 violated")));
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());

        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline
                && Handlers.EVENTS.stream().noneMatch(e -> e instanceof SthenoEvents.RejectionNotDelivered)) {
            Thread.onSpinWait();
        }
        assertThat(Handlers.EVENTS).filteredOn(e -> e instanceof SthenoEvents.RejectionNotDelivered).singleElement()
                .satisfies(e -> {
                    SthenoEvents.RejectionNotDelivered event = (SthenoEvents.RejectionNotDelivered) e;
                    assertThat(event.getDocument().getInstanceIdentifier()).isEqualTo(d.getInstanceIdentifier());
                    assertThat(event.getMlsVerdict()).isEqualTo(ApCallResult.ALREADY_DONE);
                });
    }

    @Test
    void failingHandlerIsRetriedInTheBackground() {
        AtomicInteger calls = new AtomicInteger();
        Handlers.BEHAVIOUR.set(doc -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database down");
            }
            return InboundOutcome.accepted("DK");
        });
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(d.getStatus()).isEqualTo(200);

        assertThat(AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT).isAccepted()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(Handlers.EVENTS).anyMatch(e -> e instanceof SthenoEvents.HandlerFailed f && !f.isGivingUp());
    }

    @Test
    void receivedMlsGoesToTheMlsHandlerAndIsNotAnswered() {
        Delivery d = AP.deliverMls("my-sent-invoice", "RE", MlsIssue.businessRule("/Invoice/cbc:ID", "Duplicate invoice number"));
        assertThat(d.getStatus()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (Handlers.MLS.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(Handlers.MLS).singleElement().satisfies(mls -> {
            assertThat(mls.isRejected()).isTrue();
            assertThat(mls.getReferencedInstanceIdentifier()).isEqualTo("my-sent-invoice");
            assertThat(mls.getIssues()).singleElement().extracting(MlsIssue::getDescription).isEqualTo("Duplicate invoice number");
        });
        assertThat(AP.noMlsVerdictWithin(d.getInstanceIdentifier(), Duration.ofMillis(500))).isTrue();
        assertThat(Handlers.INVOICES).isEmpty();
    }

    @Test
    void brokenEnvelopeIsRefusedWith400() {
        Delivery d = AP.deliver("<not-an-envelope/>".getBytes(StandardCharsets.UTF_8), "x");
        assertThat(d.getStatus()).isEqualTo(400);
        assertThat(d.field("status")).isEqualTo("rejected");
    }

    @Test
    void accessPointVerificationReachesTheHandler() {
        AP.verification("rejected", "[{\"rule\":\"BR-01\"}]");
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT);
        assertThat(Handlers.INVOICES).singleElement().satisfies(doc -> {
            assertThat(doc.getDelivery().getVerificationResult()).isEqualTo(VerificationResult.REJECTED);
            assertThat(doc.getDelivery().getVerificationDetailsJson()).isEqualTo("[{\"rule\":\"BR-01\"}]");
        });
    }

    @Test
    void failingAcknowledgementIsRetriedAndThenGivenUp() {
        AP.respondToMlsVerdictWith(500);
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline
                && Handlers.EVENTS.stream().noneMatch(e -> e instanceof SthenoEvents.AccessPointAcknowledged a && a.isGivingUp())) {
            Thread.onSpinWait();
        }
        assertThat(AP.getMlsVerdicts()).hasSize(3).allMatch(v -> v.getInstanceIdentifier().equals(d.getInstanceIdentifier()));
        assertThat(Handlers.EVENTS).filteredOn(e -> e instanceof SthenoEvents.AccessPointAcknowledged).hasSize(3);
    }

    @Test
    void healthShowsTheInboundFlow() throws Exception {
        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.body()).contains("\"stheno\"").contains("\"handlers\":3").contains("\"mlsVerdict\":true");
    }

    @Test
    void optionalFeaturesAreOffByDefault() {
        assertThat(context.getBeanNamesForType(PeppolLookup.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DocumentValidator.class)).isEmpty();
    }
}
