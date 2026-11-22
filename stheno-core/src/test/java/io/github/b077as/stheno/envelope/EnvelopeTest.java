package io.github.b077as.stheno.envelope;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.inbound.DeliveryMetadata;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeTest {

    private final EnvelopeReader reader = new EnvelopeReader();

    @Test
    void xmlRoundTrip() {
        OffsetDateTime created = OffsetDateTime.of(2026, 11, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        BuiltEnvelope env = EnvelopeBuilder.create()
                .sender(TestDocs.SENDER)
                .receiver(TestDocs.RECEIVER)
                .documentType(TestDocs.INVOICE_DOCTYPE)
                .process(TestDocs.BILLING_PROCESS)
                .senderCountry("BE")
                .creationDateTime(created)
                .mlsTo("0242:000111")
                .mlsAlwaysSend(true)
                .attribute("MyKey", "my value")
                .payload(TestDocs.invoiceElement())
                .build();

        InboundDocument doc = reader.read(env.getXml(), DeliveryMetadata.of("test"));
        assertThat(doc.getInstanceIdentifier()).isEqualTo(env.getInstanceIdentifier()).isNotBlank();
        assertThat(doc.getSenderId()).isEqualTo("iso6523-actorid-upis::" + TestDocs.SENDER);
        assertThat(doc.getReceiverId()).isEqualTo("iso6523-actorid-upis::" + TestDocs.RECEIVER);
        assertThat(doc.getDocumentTypeId()).isEqualTo("busdox-docid-qns::" + TestDocs.INVOICE_DOCTYPE);
        assertThat(doc.getProcessId()).isEqualTo("cenbii-procid-ubl::" + TestDocs.BILLING_PROCESS);
        assertThat(doc.getCountryC1()).isEqualTo("BE");
        assertThat(doc.getMlsTo()).isEqualTo("iso6523-actorid-upis::0242:000111");
        assertThat(doc.getMlsType()).isEqualTo("ALWAYS_SEND");
        assertThat(doc.getCreationDateTime()).isEqualTo(created);
        assertThat(doc.getStandard()).isEqualTo("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2");
        assertThat(doc.getType()).isEqualTo("Invoice");
        assertThat(doc.getTypeVersion()).isEqualTo("2.1");
        assertThat(doc.getAdditionalAttributes()).containsEntry("MyKey", "my value");
        assertThat(doc.getPayload().getKind()).isEqualTo(InboundPayload.Kind.XML);
        assertThat(doc.getPayload().getRootName()).isEqualTo("Invoice");
        assertThat(doc.isMessageLevelStatus()).isFalse();
        assertThat(doc.getDelivery().getSource()).isEqualTo("test");
    }

    @Test
    void binaryRoundTrip() {
        byte[] pdf = "%PDF-1.7 not really a pdf".getBytes(StandardCharsets.US_ASCII);
        BuiltEnvelope env = nonXml().binaryPayload(pdf, "application/pdf", null).build();
        InboundPayload payload = reader.read(env.getXml(), null).getPayload();
        assertThat(payload.getKind()).isEqualTo(InboundPayload.Kind.BINARY);
        assertThat(payload.getBinary()).isEqualTo(pdf);
        assertThat(payload.getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void textRoundTrip() {
        String edifact = "UNB+UNOA:2+9930711378399:14+7798032711116:14+160927:2252+EW861380947'";
        BuiltEnvelope env = nonXml().textPayload(edifact, "application/edifact").build();
        InboundPayload payload = reader.read(env.getXml(), null).getPayload();
        assertThat(payload.getKind()).isEqualTo(InboundPayload.Kind.TEXT);
        assertThat(payload.getText()).isEqualTo(edifact);
        assertThat(payload.getMimeType()).isEqualTo("application/edifact");
    }

    @Test
    void allProblemsAreReportedTogether() {
        assertThatThrownBy(() -> EnvelopeBuilder.create()
                .sender("0208:0123456748")
                .documentType(TestDocs.INVOICE_DOCTYPE)
                .process(TestDocs.BILLING_PROCESS)
                .senderCountry("be")
                .attribute("COUNTRY_C1", "IT")
                .payload(TestDocs.invoiceElement())
                .build())
                .isInstanceOf(SthenoException.class)
                .satisfies(ex -> assertThat(((SthenoException) ex).getCode()).isEqualTo(SthenoException.ErrorCode.INVALID_INPUT))
                .hasMessageContaining("sender: ")
                .hasMessageContaining("receiver is missing")
                .hasMessageContaining("sender country 'be'")
                .hasMessageContaining("'COUNTRY_C1' cannot be used");
    }

    @Test
    void senderCountryIsNeverDefaulted() {
        assertThatThrownBy(() -> EnvelopeBuilder.create()
                .sender(TestDocs.SENDER)
                .receiver(TestDocs.RECEIVER)
                .documentType(TestDocs.INVOICE_DOCTYPE)
                .process(TestDocs.BILLING_PROCESS)
                .payload(TestDocs.invoiceElement())
                .build())
                .hasMessageContaining("COUNTRY_C1");
    }

    @Test
    void nonXmlPayloadNeedsStandardTypeVersionAndMime() {
        assertThatThrownBy(() -> EnvelopeBuilder.create()
                .sender(TestDocs.SENDER)
                .receiver(TestDocs.RECEIVER)
                .documentType(TestDocs.INVOICE_DOCTYPE)
                .process(TestDocs.BILLING_PROCESS)
                .senderCountry("BE")
                .binaryPayload(new byte[] {1, 2, 3}, "", null)
                .build())
                .hasMessageContaining("standard, type and typeVersion")
                .hasMessageContaining("MIME type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"good.xml", "good-bis3.xml", "good-bis3-full.xml", "good-bis3-with-country_c1.xml",
            "good-bis3-with-mls-to.xml", "good-bis3-with-mls-type.xml", "good-additional-scopes.xml", "good-with-timezone.xml",
            "good-type-version-20.xml", "good-v11.xml", "good-hybrid-invoice.xml", "good-order-with-qty-ph.xml", "good-to-9915-test.xml",
            "good-gxs1.xml"})
    void officialGoodEnvelopes(String file) {
        InboundDocument doc = reader.read(TestDocs.resource("external/sbdh/good/" + file), null);
        assertThat(doc.getInstanceIdentifier()).isNotBlank();
        assertThat(doc.getPayload()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-invalid-business-message.xml", "bad-invalid-document-type-identifier.xml",
            "bad-invalid-header-version.xml", "bad-invalid-instance-identifier.xml", "bad-invalid-process-identifier.xml",
            "bad-invalid-receiver-authority.xml", "bad-invalid-receiver-value.xml", "bad-invalid-sender-authority.xml",
            "bad-invalid-sender-value.xml", "bad-invalid-standard.xml", "bad-invalid-type-version.xml", "bad-invalid-type.xml",
            "bad-no-business-message.xml", "bad-no-business-scope.xml", "bad-no-country-c1.xml", "bad-no-document-type-identifier.xml",
            "bad-no-process-identifier.xml", "bad-no-sbdh.xml", "bad-no-xml.txt", "bad-too-few-receivers.xml", "bad-too-few-scopes.xml",
            "bad-too-few-senders.xml", "bad-too-many-receivers.xml", "bad-too-many-senders.xml", "bad-mls-to-empty.xml",
            "bad-mls-to-no-value.xml", "bad-invalid-creation-date-and-time.xml"})
    void officialBadEnvelopesAreRefused(String file) {
        assertThatThrownBy(() -> reader.read(TestDocs.resource("external/sbdh/bad/" + file), null))
                .isInstanceOf(SthenoException.class)
                .satisfies(ex -> assertThat(((SthenoException) ex).getCode()).isEqualTo(SthenoException.ErrorCode.INVALID_ENVELOPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-mls-to-invalid-value.xml", "bad-mls-to-no-scheme.xml"})
    void invalidMlsToIsOnlyAWarning(String file) {
        // MLS spec: "If this element is not present or invalid, MLS responses MUST be sent to the" default receiver; peppol-commons warns only
        InboundDocument doc = reader.read(TestDocs.resource("external/sbdh/bad/" + file), null);
        assertThat(doc.getInstanceIdentifier()).isNotBlank();
    }

    @Test
    void attributeWithoutValueIsKept() {
        // Envelope 2.0.2 chapter 2.7: "The attribute value MAY be omitted"
        BuiltEnvelope env = EnvelopeBuilder.create()
                .sender(TestDocs.SENDER)
                .receiver(TestDocs.RECEIVER)
                .documentType(TestDocs.INVOICE_DOCTYPE)
                .process(TestDocs.BILLING_PROCESS)
                .senderCountry("BE")
                .attribute("IndicatorAttribute", null)
                .payload(TestDocs.invoiceElement())
                .build();
        assertThat(reader.read(env.getXml(), null).getAdditionalAttributes()).containsKey("IndicatorAttribute");
    }

    @Test
    void invalidMlsTypeIsOnlyAWarning() {
        // MLS 1.1.0 section 4.2: an invalid MLS_TYPE is read as the default, the document must not be rejected (peppol-commons 13.1.1)
        InboundDocument doc = reader.read(TestDocs.resource("external/sbdh/bad/bad-mls-type-invalid-value.xml"), null);
        assertThat(doc.getMlsType()).isNull();
    }

    @Test
    void emptyInputIsRefused() {
        assertThatThrownBy(() -> reader.read(new byte[0], null)).isInstanceOf(SthenoException.class);
    }

    private static EnvelopeBuilder nonXml() {
        return EnvelopeBuilder.create()
                .sender(TestDocs.SENDER)
                .receiver(TestDocs.RECEIVER)
                .documentType(TestDocs.INVOICE_DOCTYPE)
                .process(TestDocs.BILLING_PROCESS)
                .senderCountry("BE")
                .standard("urn:example:binary")
                .type("Attachment")
                .typeVersion("1.0");
    }
}
