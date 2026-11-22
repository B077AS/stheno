package io.github.b077as.stheno.validation;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.reply.InvoiceResponseBuilder;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses the official Peppol rule sets of phive-rules-peppol as the oracle.
 */
class DocumentValidatorTest {

    private static final DocumentValidator VALIDATOR = new DocumentValidator();

    @Test
    void invoiceResponseBuiltByStthenoPassesTheOfficialRules() {
        Element response = InvoiceResponseBuilder.create()
                .id("imr-1")
                .issueDate(LocalDate.of(2026, 11, 15))
                .sender(TestDocs.SENDER, "Buyer NV")
                .receiver(TestDocs.RECEIVER, "Seller A/S")
                .invoice("inv-99876", "380", LocalDate.of(2026, 11, 1))
                .status(InvoiceResponseBuilder.Status.RE)
                .reason(InvoiceResponseBuilder.Reason.REF, "Order reference not found",
                        new InvoiceResponseBuilder.Condition("BT-13", "PO-4711"))
                .action(InvoiceResponseBuilder.Action.NIN, "Please issue a new invoice")
                .note("See our purchase order")
                .build();

        assertThat(VALIDATOR.findRuleSet(response)).isEqualTo("eu.peppol.bis3:invoice-message-response:latest");
        ValidationReport report = VALIDATOR.validate(response);
        assertThat(report.getErrors()).as(report.getFindings().toString()).isEmpty();
        assertThat(report.isValid()).isTrue();
    }

    @Test
    void everyStatusCodeProducesAValidResponse() {
        for (InvoiceResponseBuilder.Status status : InvoiceResponseBuilder.Status.values()) {
            InvoiceResponseBuilder b = InvoiceResponseBuilder.create()
                    .id("imr-" + status)
                    .sender(TestDocs.SENDER, "Buyer NV")
                    .receiver(TestDocs.RECEIVER, "Seller A/S")
                    .invoice("inv-1", "380", null)
                    .status(status);
            if (status == InvoiceResponseBuilder.Status.RE || status == InvoiceResponseBuilder.Status.UQ
                    || status == InvoiceResponseBuilder.Status.CA) {
                b.reason(InvoiceResponseBuilder.Reason.OTH, "explained in the note");
            }
            if (status == InvoiceResponseBuilder.Status.PD) {
                b.effectiveDate(LocalDate.of(2026, 11, 20));
            }
            ValidationReport report = VALIDATOR.validate(b.build());
            assertThat(report.getErrors()).as(status + ": " + report.getFindings()).isEmpty();
        }
    }

    @Test
    void brokenInvoiceGivesErrorsMappedToMlsIssues() {
        Element invoice = InvoiceResponseBuilder.create().id("to-be-removed").sender(TestDocs.SENDER, "B").receiver(TestDocs.RECEIVER, "S")
                .invoice("1", "380", null).status(InvoiceResponseBuilder.Status.AP).build();
        // Remove the mandatory response identifier: a schema error (cbc:ID is required by UBL)
        Node id = null;
        for (Node n = invoice.getFirstChild(); n != null && id == null; n = n.getNextSibling()) {
            if ("ID".equals(n.getLocalName())) {
                id = n;
            }
        }
        assertThat(id).isNotNull();
        invoice.removeChild(id);

        ValidationReport report = VALIDATOR.validate(invoice);
        assertThat(report.isValid()).isFalse();
        assertThat(report.getRuleSet()).contains("invoice-message-response");
        List<MlsIssue> issues = report.toMlsIssues();
        assertThat(issues).anyMatch(i -> i.getReason() == MlsIssue.Reason.SV);
        assertThat(issues).allMatch(i -> !i.getDescription().isBlank() && i.getLocation() != null);
    }

    @Test
    void unknownDocumentHasNoRuleSet() {
        Element unknown = TestDocs.parse("<x:Foo xmlns:x='urn:example:none'/>".getBytes());
        assertThat(VALIDATOR.findRuleSet(unknown)).isNull();
        assertThatThrownBy(() -> VALIDATOR.validate(unknown))
                .isInstanceOf(SthenoException.class)
                .satisfies(ex -> assertThat(((SthenoException) ex).getCode()).isEqualTo(SthenoException.ErrorCode.VALIDATION_UNAVAILABLE));
        assertThatThrownBy(() -> VALIDATOR.validate(unknown, "no.such:ruleset:1.0")).hasMessageContaining("not registered");
    }

    @Test
    void warmUpPreparesTheRequestedRuleSets() {
        Element response = InvoiceResponseBuilder.create().id("x").sender(TestDocs.SENDER, "B").receiver(TestDocs.RECEIVER, "S")
                .invoice("1", "380", null).status(InvoiceResponseBuilder.Status.AP).build();
        assertThat(VALIDATOR.warmUp(List.of(VALIDATOR.findRuleSet(response)))).isEqualTo(1);
    }
}
