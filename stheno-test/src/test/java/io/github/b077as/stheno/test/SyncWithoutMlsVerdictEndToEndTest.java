package io.github.b077as.stheno.test;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MLS switch off (phoss-ap decides the MLS itself, {@code mls.sending.trigger=auto}): a rejection can only travel in the sync
 * forwarding answer as {@code retry: none}, and Stheno makes no {@code /api/mls/send} call.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stheno.inbound.forwarding-mode=sync",
        "stheno.inbound.processing=inline",
        "stheno.access-point.mls-verdict=false"})
class SyncWithoutMlsVerdictEndToEndTest {

    private static final FakeAccessPoint AP = FakeAccessPoint.start();

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
        AP.forwardTo(URI.create("http://localhost:" + port + "/peppol/inbound"));
    }

    @Test
    void rejectionIsAnsweredWithRetryNone() {
        Handlers.BEHAVIOUR.set(doc -> InboundOutcome.rejected(MlsIssue.businessRule(null, "Unknown supplier")));
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(d.getStatus()).isEqualTo(200);
        assertThat(d.isNoRetry()).isTrue();
        assertThat(d.field("errorMessage")).isEqualTo("Unknown supplier");
        assertThat(AP.noMlsVerdictWithin(d.getInstanceIdentifier(), Duration.ofMillis(500))).isTrue();
    }

    @Test
    void acceptanceCarriesTheCountryAndSendsNoVerdict() {
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(d.getCountryCodeC4()).isEqualTo("DK");
        assertThat(AP.noMlsVerdictWithin(d.getInstanceIdentifier(), Duration.ofMillis(500))).isTrue();
        assertThat(AP.getCountryReports()).isEmpty();
    }
}
