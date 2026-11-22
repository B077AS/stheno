package io.github.b077as.stheno.reply;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeReader;
import io.github.b077as.stheno.inbound.InboundDocument;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceResponseBuilderTest {

    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    @Test
    void buildsTheDocumentAndItsEnvelope() {
        InvoiceResponseBuilder b = InvoiceResponseBuilder.create(Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC))
                .id("imr-7")
                .sender(TestDocs.SENDER, "Buyer NV")
                .receiver(TestDocs.RECEIVER, "Seller A/S")
                .invoice("inv-1", "380", null)
                .status(InvoiceResponseBuilder.Status.AP);
        Element root = b.build();
        assertThat(root.getLocalName()).isEqualTo("ApplicationResponse");
        assertThat(text(root, "CustomizationID")).isEqualTo(InvoiceResponseBuilder.CUSTOMIZATION_ID);
        assertThat(text(root, "IssueDate")).isEqualTo("2026-11-15");
        assertThat(text(root, "ResponseCode")).isEqualTo("AP");

        BuiltEnvelope env = b.buildEnvelope("BE");
        InboundDocument read = new EnvelopeReader().read(env.getXml(), null);
        assertThat(read.getDocumentTypeId()).isEqualTo("busdox-docid-qns::" + InvoiceResponseBuilder.DOCUMENT_TYPE_ID);
        assertThat(read.getProcessId()).isEqualTo("cenbii-procid-ubl::" + InvoiceResponseBuilder.PROCESS_ID);
        assertThat(read.getSenderId()).isEqualTo("iso6523-actorid-upis::" + TestDocs.SENDER);
    }

    @Test
    void missingDataIsReportedTogether() {
        assertThatThrownBy(() -> InvoiceResponseBuilder.create().sender("0208:0123456748", "B").build())
                .isInstanceOf(SthenoException.class)
                .hasMessageContaining("id is missing")
                .hasMessageContaining("receiver registration name is missing")
                .hasMessageContaining("invoice number is missing")
                .hasMessageContaining("status is missing")
                .hasMessageContaining("sender: ");
    }

    private static String text(Element root, String localName) {
        return root.getElementsByTagNameNS(CBC, localName).item(0).getTextContent();
    }
}
