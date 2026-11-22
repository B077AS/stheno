package io.github.b077as.stheno.test;

import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.test.app.TestApplication;
import io.github.b077as.stheno.test.app.TestApplication.Handlers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * phoss-ap {@code http_post_sync} forwarding, handlers inline, header authentication, custom path.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stheno.inbound.forwarding-mode=sync",
        "stheno.inbound.processing=inline",
        "stheno.inbound.path=/ap/forward",
        "stheno.inbound.auth-header-name=Authorization",
        "stheno.inbound.auth-header-value=Bearer s3cret"})
class SyncInlineEndToEndTest {

    private static final FakeAccessPoint AP = FakeAccessPoint.start();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

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
        AP.forwardTo(URI.create("http://localhost:" + port + "/ap/forward")).header("Authorization", "Bearer s3cret");
    }

    @Test
    void countryTravelsInTheAnswerAndTheVerdictViaTheApi() {
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(d.getStatus()).isEqualTo(200);
        assertThat(d.getCountryCodeC4()).isEqualTo("DK");
        assertThat(Handlers.INVOICES).hasSize(1);
        assertThat(AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT).isAccepted()).isTrue();
        // Already in the sync answer: not reported a second time
        assertThat(AP.getCountryReports()).isEmpty();
    }

    @Test
    void rejectionWithMlsVerdictOnIsAnsweredReceivedAndReportedWithIssues() {
        Handlers.BEHAVIOUR.set(doc -> InboundOutcome.rejected(MlsIssue.businessRule(null, "Unknown supplier")));
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(d.getStatus()).isEqualTo(200);
        assertThat(d.isNoRetry()).isFalse();
        assertThat(d.getCountryCodeC4()).isNull();
        RecordedMlsVerdict verdict = AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT);
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.getIssues()).singleElement().extracting(MlsIssue::getLocation).isEqualTo(MlsIssue.NO_LOCATION);
    }

    @Test
    void handlerExceptionMakesTheAccessPointRetry() {
        AtomicInteger calls = new AtomicInteger();
        Handlers.BEHAVIOUR.set(doc -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database down");
            }
            return InboundOutcome.accepted("DK");
        });
        BuiltEnvelope env = FakeAccessPoint.sampleInvoiceEnvelope();
        assertThat(AP.deliver(env).getStatus()).isEqualTo(503);
        Delivery retry = AP.deliver(env);
        assertThat(retry.getStatus()).isEqualTo(200);
        assertThat(retry.getCountryCodeC4()).isEqualTo("DK");
        assertThat(calls).hasValue(2);
    }

    @Test
    void duplicateGetsTheSameAnswer() {
        BuiltEnvelope env = FakeAccessPoint.sampleInvoiceEnvelope();
        AP.deliver(env);
        Delivery again = AP.deliver(env);
        assertThat(again.getCountryCodeC4()).isEqualTo("DK");
        assertThat(Handlers.INVOICES).hasSize(1);
    }

    @Test
    void wrongOrMissingAuthHeaderIsRefused() {
        AP.reset();
        AP.forwardTo(URI.create("http://localhost:" + port + "/ap/forward"));
        assertThat(AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope()).getStatus()).isEqualTo(401);
        AP.header("Authorization", "Bearer wrong");
        assertThat(AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope()).getStatus()).isEqualTo(401);
        assertThat(Handlers.INVOICES).isEmpty();
    }

    @Test
    void brokenEnvelopeInSyncModeStopsTheRetries() {
        Delivery d = AP.deliver("<garbage".getBytes(), "x");
        assertThat(d.getStatus()).isEqualTo(200);
        assertThat(d.isNoRetry()).isTrue();
    }
}
