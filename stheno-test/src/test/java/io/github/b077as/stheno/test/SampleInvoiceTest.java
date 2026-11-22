package io.github.b077as.stheno.test;

import io.github.b077as.stheno.validation.DocumentValidator;
import io.github.b077as.stheno.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The sample invoice shipped for tests must pass the official Peppol BIS 3 rules. */
class SampleInvoiceTest {

    @Test
    void sampleInvoiceIsValidPeppolBis3() {
        DocumentValidator validator = new DocumentValidator();
        ValidationReport report = validator.validate(FakeAccessPoint.sampleInvoice());
        assertThat(report.getRuleSet()).startsWith("eu.peppol.bis3:invoice:");
        assertThat(report.getErrors()).as(report.getFindings().toString()).isEmpty();
    }
}
