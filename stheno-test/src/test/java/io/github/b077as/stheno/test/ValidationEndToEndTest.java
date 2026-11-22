package io.github.b077as.stheno.test;

import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.test.app.TestApplication;
import io.github.b077as.stheno.test.app.TestApplication.Handlers;
import io.github.b077as.stheno.validation.DocumentValidator;
import io.github.b077as.stheno.validation.ValidationReport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation switched on: the handler validates with the official rules and rejects invalid invoices with the findings as MLS issues.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stheno.validation.enabled=true",
        "stheno.validation.warm-up=eu.peppol.bis3:invoice:latest"})
class ValidationEndToEndTest {

    private static final FakeAccessPoint AP = FakeAccessPoint.start();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Autowired
    DocumentValidator validator;

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
        Handlers.BEHAVIOUR.set(doc -> {
            ValidationReport report = validator.validate(doc);
            return report.isValid() ? InboundOutcome.accepted("DK") : InboundOutcome.rejected("Invalid invoice", report.toMlsIssues());
        });
    }

    @Test
    void validInvoiceIsAccepted() {
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT).isAccepted()).isTrue();
    }

    @Test
    void invalidInvoiceIsRejectedWithTheRuleFindings() {
        Element invoice = FakeAccessPoint.sampleInvoice();
        // Wrong total: BR-CO-15 (TaxInclusiveAmount = TaxExclusiveAmount + TaxAmount)
        Node total = invoice.getElementsByTagNameNS("urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2",
                "TaxInclusiveAmount").item(0);
        total.setTextContent("999.00");
        Delivery d = AP.deliver(FakeAccessPoint.sampleEnvelope(invoice));

        RecordedMlsVerdict verdict = AP.awaitMlsVerdict(d.getInstanceIdentifier(), WAIT);
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.getIssues()).anyMatch(i -> i.getReason() == MlsIssue.Reason.BV && i.getDescription().contains("BR-CO-15"));
    }
}
