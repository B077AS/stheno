package io.github.b077as.stheno.inbound;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.w3c.dom.Element;

/**
 * The business payload of a received envelope. Peppol Business Message Envelope 2.0.2 allows XML (the normal case), Base64 encoded
 * binary content and plain text content, the last two always with a MIME type (chapter 2.3).
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InboundPayload {

    /** Kind of payload. */
    public enum Kind {
        XML,
        BINARY,
        TEXT
    }

    Kind kind;
    /** The root element of the business document, for {@link Kind#XML}. */
    Element xml;
    /** The decoded bytes, for {@link Kind#BINARY}. Do not modify the array. */
    byte[] binary;
    /** The text, for {@link Kind#TEXT}. */
    String text;
    /** MIME type of a binary or text payload; {@code application/xml} for XML. */
    String mimeType;
    /** Optional source encoding of a binary text payload (envelope attribute {@code encoding}). */
    String encoding;

    public static InboundPayload xml(Element root) {
        return new InboundPayload(Kind.XML, root, null, null, "application/xml", null);
    }

    public static InboundPayload binary(byte[] data, String mimeType, String encoding) {
        return new InboundPayload(Kind.BINARY, null, data, null, mimeType, encoding);
    }

    public static InboundPayload text(String text, String mimeType) {
        return new InboundPayload(Kind.TEXT, null, null, text, mimeType, null);
    }

    /** Namespace URI of the XML root element, or {@code null} for non-XML payloads. */
    public String getRootNamespace() {
        return xml != null ? xml.getNamespaceURI() : null;
    }

    /** Local name of the XML root element (e.g. {@code Invoice}), or {@code null} for non-XML payloads. */
    public String getRootName() {
        return xml != null ? xml.getLocalName() : null;
    }
}
