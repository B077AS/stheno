package io.github.b077as.stheno.reply;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeReader;
import io.github.b077as.stheno.inbound.InboundDocument;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceResponseReaderTest {

    private static final String UBL = "urn:oasis:names:specification:ubl:schema:xsd:";
    private final InvoiceResponseReader reader = new InvoiceResponseReader();

    @Test
    void readsWhatTheBuilderWrote() {
        BuiltEnvelope env = InvoiceResponseBuilder.create()
                .id("imr-1")
                .issueDate(LocalDate.of(2026, 11, 20))
                .issueTime(LocalTime.of(14, 30, 5))
                .note("See our purchase order")
                .sender(TestDocs.RECEIVER, "Buyer A/S")
                .receiver(TestDocs.SENDER, "Seller NV")
                .invoice("INV-0001", "380", LocalDate.of(2026, 11, 1))
                .status(InvoiceResponseBuilder.Status.RE)
                .effectiveDate(LocalDate.of(2026, 11, 21))
                .reason(InvoiceResponseBuilder.Reason.REF, "Order reference not found",
                        new InvoiceResponseBuilder.Condition("BT-13", "PO-4711"),
                        new InvoiceResponseBuilder.Condition("BT-10", null))
                .action(InvoiceResponseBuilder.Action.NIN, null)
                .buildEnvelope("DK");
        InboundDocument document = new EnvelopeReader().read(env.getXml(), null);
        assertThat(InvoiceResponseReader.isInvoiceResponse(document)).isTrue();

        InvoiceResponse r = reader.read(document);
        assertThat(r.getId()).isEqualTo("imr-1");
        assertThat(r.getIssueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(r.getIssueTime()).isEqualTo(LocalTime.of(14, 30, 5));
        assertThat(r.getNote()).isEqualTo("See our purchase order");
        assertThat(r.getSender()).isEqualTo(new InvoiceResponse.Party(TestDocs.RECEIVER, "Buyer A/S"));
        assertThat(r.getReceiver()).isEqualTo(new InvoiceResponse.Party(TestDocs.SENDER, "Seller NV"));
        assertThat(r.getStatus()).isEqualTo(InvoiceResponseBuilder.Status.RE);
        assertThat(r.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(r.getInvoiceNumber()).isEqualTo("INV-0001");
        assertThat(r.getInvoiceTypeCode()).isEqualTo("380");
        assertThat(r.getInvoiceIssueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(r.isFinal()).isTrue();
        assertThat(r.isPartiallyPaid()).isFalse();
        assertThat(r.getStatusEntries()).containsExactly(
                new InvoiceResponseBuilder.StatusEntry("OPStatusReason", "REF", "Order reference not found", java.util.List.of(
                        new InvoiceResponseBuilder.Condition("BT-13", "PO-4711"),
                        new InvoiceResponseBuilder.Condition("BT-10", null))),
                new InvoiceResponseBuilder.StatusEntry("OPStatusAction", "NIN", null, java.util.List.of()));

        assertThat(InvoiceReference.answeredBy(r, document)).contains(InvoiceReference.of(TestDocs.SENDER, TestDocs.RECEIVER,
                "INV-0001", "380", LocalDate.of(2026, 11, 1)));
    }

    @Test
    void partialPaymentIsNotFinal() {
        InvoiceResponse r = reader.read(response(InvoiceResponseBuilder.Status.PD)
                .reason(InvoiceResponseBuilder.Reason.PPD, "50 EUR").build());
        assertThat(r.isPartiallyPaid()).isTrue();
        assertThat(r.isFinal()).isFalse();
        assertThat(reader.read(response(InvoiceResponseBuilder.Status.PD).build()).isFinal()).isTrue();
        assertThat(reader.read(response(InvoiceResponseBuilder.Status.AP).build()).getStatusEntries()).isEmpty();
    }

    @Test
    void unreadableResponsesAreRefused() {
        assertInvalid(TestDocs.invoiceElement(), "ApplicationResponse");

        Element otherKind = response(InvoiceResponseBuilder.Status.AP).build();
        element(otherKind, "CustomizationID").setTextContent("urn:peppol:edec:mls:1.0");
        assertInvalid(otherKind, "CustomizationID");

        Element unknownStatus = response(InvoiceResponseBuilder.Status.AP).build();
        element(unknownStatus, "ResponseCode").setTextContent("XX");
        assertInvalid(unknownStatus, "not an invoice status code");

        Element twoInvoices = response(InvoiceResponseBuilder.Status.AP).build();
        Element documentResponse = (Element) twoInvoices.getElementsByTagNameNS(UBL + "CommonAggregateComponents-2", "DocumentResponse")
                .item(0);
        twoInvoices.appendChild(documentResponse.cloneNode(true));
        assertInvalid(twoInvoices, "exactly one invoice");
    }

    @Test
    void replyToTakesOverWhatTheInvoiceSays() {
        BuiltEnvelope invoice = TestDocs.namedInvoiceEnvelope("invoice-1");
        InboundDocument received = new EnvelopeReader().read(invoice.getXml(), null);

        InvoiceResponse r = reader.read(InvoiceResponseBuilder.replyTo(received).id("imr-1")
                .status(InvoiceResponseBuilder.Status.AB).build());

        InvoiceReference reference = InvoiceReference.from(received).orElseThrow();
        assertThat(r.getInvoiceNumber()).isEqualTo(reference.getInvoiceNumber());
        assertThat(r.getInvoiceTypeCode()).isEqualTo(reference.getTypeCode());
        assertThat(r.getInvoiceIssueDate()).isEqualTo(reference.getIssueDate());
        assertThat(r.getSender().getParticipantId()).isEqualTo(TestDocs.RECEIVER);
        assertThat(r.getReceiver().getParticipantId()).isEqualTo(TestDocs.SENDER);
        assertThat(r.getSender().getRegistrationName()).isEqualTo("Buyer A/S");
        assertThat(r.getReceiver().getRegistrationName()).isEqualTo("Seller NV");
    }

    @Test
    void replyToReadsACreditNoteAndInventsNothing() {
        InboundDocument creditNote = received(TestDocs.parse(("<CreditNote xmlns='" + UBL + "CreditNote-2' xmlns:cbc='" + UBL
                + "CommonBasicComponents-2'><cbc:ID>CN-7</cbc:ID><cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode></CreditNote>")
                .getBytes()));
        assertThat(InvoiceReference.from(creditNote)).contains(
                InvoiceReference.of(TestDocs.SENDER, TestDocs.RECEIVER, "CN-7", "381", null));
        assertThatThrownBy(() -> InvoiceResponseBuilder.replyTo(creditNote).id("x").status(InvoiceResponseBuilder.Status.AP).build())
                .hasMessageContaining("sender registration name is missing")
                .hasMessageContaining("receiver registration name is missing")
                .hasMessageNotContaining("invoice");
    }

    @Test
    void replyToLeavesTheNamesOfASelfBilledInvoiceToTheApplication() {
        Element root = TestDocs.namedInvoiceElement();
        element(root, "CustomizationID").setTextContent("urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:selfbilling:3.0");
        assertThatThrownBy(() -> InvoiceResponseBuilder.replyTo(received(root)).id("x").status(InvoiceResponseBuilder.Status.AP).build())
                .hasMessageContaining("sender registration name is missing")
                .hasMessageContaining("receiver registration name is missing");
    }

    @Test
    void anInvoiceDateWithATimeZoneIsRead() {
        InboundDocument invoice = received(TestDocs.invoiceElement());
        assertThat(InvoiceReference.from(invoice).orElseThrow().getIssueDate()).isEqualTo(LocalDate.of(2011, 8, 22));
    }

    @Test
    void onlyInvoicesAndCreditNotesCanBeAnsweredAndReferenced() {
        InboundDocument other = received(response(InvoiceResponseBuilder.Status.AP).build());
        assertThat(InvoiceReference.from(other)).isEmpty();
        assertThatThrownBy(() -> InvoiceResponseBuilder.replyTo(other))
                .isInstanceOf(SthenoException.class)
                .hasMessageContaining("UBL Invoice or CreditNote");
    }

    @Test
    void referencesCompareParticipantsWithoutCase() {
        assertThat(InvoiceReference.of("0088:ABC", "iso6523-actorid-upis::0208:0123456749", " 1 ", "380", null))
                .isEqualTo(InvoiceReference.of("iso6523-actorid-upis::0088:abc", "0208:0123456749", "1", "380", null));
        assertThatThrownBy(() -> InvoiceReference.of("nonsense", "0208:0123456749", "", "380", null))
                .hasMessageContaining("invoice sender")
                .hasMessageContaining("invoice number is missing");
    }

    private void assertInvalid(Element root, String why) {
        assertThatThrownBy(() -> reader.read(root))
                .isInstanceOf(SthenoException.class)
                .hasMessageContaining(why)
                .satisfies(ex -> assertThat(((SthenoException) ex).getCode()).isEqualTo(SthenoException.ErrorCode.INVALID_DOCUMENT));
    }

    private static InboundDocument received(Element root) {
        return InboundDocument.builder()
                .senderId(TestDocs.SENDER)
                .receiverId(TestDocs.RECEIVER)
                .payload(io.github.b077as.stheno.inbound.InboundPayload.xml(root))
                .build();
    }

    private static InvoiceResponseBuilder response(InvoiceResponseBuilder.Status status) {
        return InvoiceResponseBuilder.create()
                .id("imr-1")
                .sender(TestDocs.RECEIVER, "Buyer")
                .receiver(TestDocs.SENDER, "Seller")
                .invoice("INV-0001", "380", null)
                .status(status);
    }

    private static Element element(Element root, String basicComponent) {
        return (Element) root.getElementsByTagNameNS(UBL + "CommonBasicComponents-2", basicComponent).item(0);
    }
}
