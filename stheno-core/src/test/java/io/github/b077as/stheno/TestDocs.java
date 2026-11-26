package io.github.b077as.stheno;

import com.helger.peppol.mls.PeppolMLSBuilder;
import com.helger.peppol.mls.PeppolMLSMarshaller;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeBuilder;
import io.github.b077as.stheno.inbound.PeppolDocumentKinds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Shared test data. Participant ids use the Belgian enterprise number scheme 0208 with a valid mod 97 check digit (0123456749),
 * because peppol-commons checks its value.
 */
public final class TestDocs {

    public static final String SENDER = "0208:0123456749";
    public static final String RECEIVER = "0088:5798000000001";
    public static final String INVOICE_DOCTYPE =
            "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";
    public static final String BILLING_PROCESS = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
    public static final String MLS_DOCTYPE =
            "urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2::ApplicationResponse##urn:peppol:edec:mls:1.0::2.1";

    private TestDocs() {
    }

    public static byte[] resource(String path) {
        try (InputStream in = TestDocs.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static Element invoiceElement() {
        return parse(resource("external/peppol-ubl/test-invoice.xml"));
    }

    public static Element parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
            return doc.getDocumentElement();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Only the header and the parties of a BIS 3 invoice, with legal names (the peppol-testfiles invoice has none). Not a valid
     * invoice.
     */
    public static Element namedInvoiceElement() {
        String ubl = "urn:oasis:names:specification:ubl:schema:xsd:";
        return parse(("<Invoice xmlns='" + ubl + "Invoice-2' xmlns:cac='" + ubl + "CommonAggregateComponents-2' xmlns:cbc='" + ubl
                + "CommonBasicComponents-2'>"
                + "<cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0</cbc:CustomizationID>"
                + "<cbc:ID>INV-0001</cbc:ID><cbc:IssueDate>2026-11-15</cbc:IssueDate><cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>"
                + "<cac:AccountingSupplierParty><cac:Party><cac:PartyLegalEntity><cbc:RegistrationName>Seller NV</cbc:RegistrationName>"
                + "</cac:PartyLegalEntity></cac:Party></cac:AccountingSupplierParty>"
                + "<cac:AccountingCustomerParty><cac:Party><cac:PartyLegalEntity><cbc:RegistrationName>Buyer A/S</cbc:RegistrationName>"
                + "</cac:PartyLegalEntity></cac:Party></cac:AccountingCustomerParty></Invoice>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** An envelope around {@link #namedInvoiceElement()}. */
    public static BuiltEnvelope namedInvoiceEnvelope(String instanceId) {
        return envelope(instanceId, namedInvoiceElement());
    }

    /** A complete invoice envelope with the given instance identifier. */
    public static BuiltEnvelope invoiceEnvelope(String instanceId) {
        return envelope(instanceId, invoiceElement());
    }

    private static BuiltEnvelope envelope(String instanceId, Element invoice) {
        return EnvelopeBuilder.create()
                .sender(SENDER)
                .receiver(RECEIVER)
                .documentType(INVOICE_DOCTYPE)
                .process(BILLING_PROCESS)
                .senderCountry("BE")
                .instanceIdentifier(instanceId)
                .payload(invoice)
                .build();
    }

    /** An MLS envelope answering the document with the given instance identifier. */
    public static BuiltEnvelope mlsEnvelope(String answeredInstanceId, boolean rejection) {
        PeppolMLSBuilder mls = (rejection ? PeppolMLSBuilder.rejection() : PeppolMLSBuilder.acceptance())
                .randomID()
                .issueDateTimeNow()
                .senderParticipantID(PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme("0242:000111"))
                .receiverParticipantID(PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme("0242:000222"))
                .referenceId(answeredInstanceId);
        if (rejection) {
            mls.addLineResponse(l -> l.errorField("/Invoice/cbc:ID").addResponse(r -> r.statusReasonCodeBusinessRuleViolationFatal()
                    .description("Invoice number already used")));
        }
        Element root = new PeppolMLSMarshaller().getAsDocument(mls.build()).getDocumentElement();
        return EnvelopeBuilder.create()
                .sender("0242:000111")
                .receiver("0242:000222")
                .documentType(MLS_DOCTYPE)
                .process(PeppolDocumentKinds.MLS_PROCESS_ID)
                .senderCountry("BE")
                .payload(root)
                .build();
    }
}
