package io.github.b077as.stheno.reply;

import lombok.Value;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The few header fields of a UBL invoice or credit note an Invoice Response refers to. Read from the XML as it is: a missing field
 * stays {@code null}.
 */
@Value
class UblInvoiceHeader {

    private static final String INVOICE_NS = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    private static final String CREDIT_NOTE_NS = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    String customizationId;
    String number;
    String typeCode;
    LocalDate issueDate;
    /** Legal name of the {@code AccountingSupplierParty}. */
    String supplierName;
    /** Legal name of the {@code AccountingCustomerParty}. */
    String customerName;

    /** Empty if the element is neither a UBL {@code Invoice} nor a UBL {@code CreditNote}. */
    static Optional<UblInvoiceHeader> read(Element root) {
        if (root == null) {
            return Optional.empty();
        }
        String typeElement;
        if (INVOICE_NS.equals(root.getNamespaceURI()) && "Invoice".equals(root.getLocalName())) {
            typeElement = "InvoiceTypeCode";
        } else if (CREDIT_NOTE_NS.equals(root.getNamespaceURI()) && "CreditNote".equals(root.getLocalName())) {
            typeElement = "CreditNoteTypeCode";
        } else {
            return Optional.empty();
        }
        return Optional.of(new UblInvoiceHeader(
                text(root, "CustomizationID"),
                text(root, "ID"),
                text(root, typeElement),
                date(text(root, "IssueDate")),
                partyName(root, "AccountingSupplierParty"),
                partyName(root, "AccountingCustomerParty")));
    }

    private static String partyName(Element root, String role) {
        Element legal = child(child(child(root, CAC, role), CAC, "Party"), CAC, "PartyLegalEntity");
        return legal == null ? null : text(legal, "RegistrationName");
    }

    private static String text(Element parent, String basicComponent) {
        Element element = child(parent, CBC, basicComponent);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent().strip();
        return text.isEmpty() ? null : text;
    }

    /** The first direct child with this name, or {@code null} (also if the parent is {@code null}). */
    private static Element child(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && namespace.equals(e.getNamespaceURI()) && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            // xsd:date may carry a time zone ("2026-11-15Z")
            return LocalDate.parse(text, DateTimeFormatter.ISO_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
