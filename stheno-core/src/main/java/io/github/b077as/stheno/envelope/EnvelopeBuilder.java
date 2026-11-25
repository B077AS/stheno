package io.github.b077as.stheno.envelope;

import com.helger.datetime.xml.XMLOffsetDateTime;
import com.helger.mime.IMimeType;
import com.helger.mime.parse.MimeTypeParser;
import com.helger.mime.parse.MimeTypeParserException;
import com.helger.peppol.sbdh.CPeppolSBDH;
import com.helger.peppol.sbdh.EPeppolMLSType;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.sbdh.PeppolSBDHDataWriter;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.sbdh.SBDMarshaller;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.id.CountryCodes;
import io.github.b077as.stheno.id.IdCheck;
import io.github.b077as.stheno.id.IdUsage;
import io.github.b077as.stheno.id.PeppolIds;
import org.w3c.dom.Element;

import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a Peppol Business Message Envelope (SBDH 2.0.2) around a business document, e.g. an Invoice Response the application wants to
 * hand to its Access Point. Stheno only builds and checks the envelope; transmitting it is the job of the application and its Access
 * Point.
 * <p>
 * Nothing is defaulted silently: sender, receiver, document type, process and the sender country ({@code COUNTRY_C1}) must be given.
 * The only generated values are the instance identifier (a random UUID, as the specification recommends) and the creation time, both
 * overridable. All problems are collected and reported together.
 */
public final class EnvelopeBuilder {

    private static final String UBL_NS_PREFIX = "urn:oasis:names:specification:ubl:schema:xsd:";
    private static final String CII_NS = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
    private static final Set<String> RESERVED_KEYS = Set.of(
            CPeppolSBDH.SCOPE_DOCUMENT_TYPE_ID,
            CPeppolSBDH.SCOPE_PROCESS_ID,
            CPeppolSBDH.SCOPE_COUNTRY_C1,
            CPeppolSBDH.SCOPE_MLS_TO,
            CPeppolSBDH.SCOPE_MLS_TYPE,
            "COUNTRY_C4",
            "TECHNICAL_VALIDATION_URL",
            "TECHNICAL_VALIDATION_REQUIRED");

    private String senderId;
    private String receiverId;
    private String documentTypeId;
    private String processId;
    private String countryC1;
    private String instanceIdentifier;
    private OffsetDateTime creationDateTime;
    private String mlsTo;
    private EPeppolMLSType mlsType;
    private String typeVersion;
    private String standard;
    private String type;
    private Element xmlPayload;
    private byte[] binaryPayload;
    private String textPayload;
    private String mimeType;
    private Charset binaryTextEncoding;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public static EnvelopeBuilder create() {
        return new EnvelopeBuilder();
    }

    /** Sender participant (C1), with or without scheme, e.g. {@code 0088:5798000000001}. */
    public EnvelopeBuilder sender(String participantId) {
        senderId = participantId;
        return this;
    }

    /** Receiver participant (C4), with or without scheme. */
    public EnvelopeBuilder receiver(String participantId) {
        receiverId = participantId;
        return this;
    }

    /** Document type identifier, with or without the {@code busdox-docid-qns} scheme. */
    public EnvelopeBuilder documentType(String id) {
        documentTypeId = id;
        return this;
    }

    /** Process identifier, with or without the {@code cenbii-procid-ubl} scheme. */
    public EnvelopeBuilder process(String id) {
        processId = id;
        return this;
    }

    /** Country of the sender (C1), ISO 3166-1 alpha-2 upper case. Mandatory in every envelope. */
    public EnvelopeBuilder senderCountry(String iso3166Alpha2) {
        countryC1 = iso3166Alpha2;
        return this;
    }

    /** Overrides the generated UUID instance identifier. Must be globally unique. */
    public EnvelopeBuilder instanceIdentifier(String id) {
        instanceIdentifier = id;
        return this;
    }

    /** Overrides the creation time (default: now). */
    public EnvelopeBuilder creationDateTime(OffsetDateTime dateTime) {
        creationDateTime = dateTime;
        return this;
    }

    /** Optional alternative receiver of the MLS answer (participant id with or without scheme, normally a {@code 0242} SPID). */
    public EnvelopeBuilder mlsTo(String participantId) {
        mlsTo = participantId;
        return this;
    }

    /** Optional requested MLS usage: {@code true} = {@code ALWAYS_SEND}, {@code false} = {@code FAILURE_ONLY}. */
    public EnvelopeBuilder mlsAlwaysSend(boolean alwaysSend) {
        mlsType = alwaysSend ? EPeppolMLSType.ALWAYS_SEND : EPeppolMLSType.FAILURE_ONLY;
        return this;
    }

    /** XML business document. {@code Standard}, {@code Type} and, for UBL and CII, {@code TypeVersion} are taken from it. */
    public EnvelopeBuilder payload(Element root) {
        xmlPayload = root;
        return this;
    }

    /** Binary business document (Base64 encoded into {@code BinaryContent}). Needs {@link #standard}, {@link #type}, {@link #typeVersion}. */
    public EnvelopeBuilder binaryPayload(byte[] data, String mime, Charset textEncodingOrNull) {
        binaryPayload = data;
        mimeType = mime;
        binaryTextEncoding = textEncodingOrNull;
        return this;
    }

    /** Plain text business document ({@code TextContent}). Needs {@link #standard}, {@link #type}, {@link #typeVersion}. */
    public EnvelopeBuilder textPayload(String text, String mime) {
        textPayload = text;
        mimeType = mime;
        return this;
    }

    /** SBDH {@code TypeVersion}; needed for non-XML payloads and XML that is neither UBL (default 2.1) nor CII (default D16B). */
    public EnvelopeBuilder typeVersion(String version) {
        typeVersion = version;
        return this;
    }

    /** SBDH {@code Standard}; only for non-XML payloads (XML: the root namespace is used). */
    public EnvelopeBuilder standard(String value) {
        standard = value;
        return this;
    }

    /** SBDH {@code Type}; only for non-XML payloads (XML: the root local name is used). */
    public EnvelopeBuilder type(String value) {
        type = value;
        return this;
    }

    /** Additional envelope attribute. Keys are case sensitive; the Peppol reserved keys are refused. */
    public EnvelopeBuilder attribute(String key, String value) {
        attributes.put(key, value);
        return this;
    }

    /**
     * Checks everything and builds the envelope.
     *
     * @throws SthenoException {@link SthenoException.ErrorCode#INVALID_INPUT} listing every problem found
     */
    public BuiltEnvelope build() {
        List<String> problems = new ArrayList<>();
        IParticipantIdentifier sender = participant("sender", senderId, problems);
        IParticipantIdentifier receiver = participant("receiver", receiverId, problems);
        checkId("document type", documentTypeId, PeppolIds.checkDocumentTypeId(documentTypeId, IdUsage.NEW), problems);
        checkId("process", processId, PeppolIds.checkProcessId(processId, IdUsage.NEW), problems);

        if (countryC1 == null) {
            problems.add("sender country (COUNTRY_C1) is missing; it is mandatory in every envelope");
        } else if (!CountryCodes.isValidEnvelopeCountry(countryC1)) {
            problems.add("sender country '" + countryC1 + "' is not an ISO 3166-1 alpha-2 code (upper case, e.g. 'BE')");
        }

        int payloads = (xmlPayload != null ? 1 : 0) + (binaryPayload != null ? 1 : 0) + (textPayload != null ? 1 : 0);
        if (payloads != 1) {
            problems.add("exactly one payload (XML, binary or text) must be given, found " + payloads);
        }

        String std = standard;
        String typ = type;
        String ver = typeVersion;
        if (xmlPayload != null) {
            std = xmlPayload.getNamespaceURI();
            typ = xmlPayload.getLocalName();
            if (std == null || typ == null) {
                problems.add("the XML payload root element has no namespace (Peppol business documents always have one)");
            }
            if (ver == null && std != null) {
                if (std.startsWith(UBL_NS_PREFIX)) {
                    ver = CPeppolSBDH.TYPE_VERSION_21;
                } else if (CII_NS.equals(std)) {
                    ver = CPeppolSBDH.TYPE_VERSION_CII_D16B;
                } else {
                    problems.add("typeVersion is needed for an XML payload in namespace '" + std + "' (neither UBL nor CII)");
                }
            }
        } else if (payloads == 1) {
            if (std == null || typ == null || ver == null) {
                problems.add("standard, type and typeVersion must be given for a non-XML payload");
            }
            if (mimeType == null || mimeType.isBlank()) {
                problems.add("a MIME type must be given for a non-XML payload");
            }
        }

        IParticipantIdentifier mlsToId = null;
        if (mlsTo != null) {
            mlsToId = PeppolIds.parseParticipantId(mlsTo);
            if (mlsToId == null) {
                problems.add("mlsTo '" + mlsTo + "' is not a Peppol participant identifier");
            }
        }

        for (String key : attributes.keySet()) {
            if (key == null || key.isBlank() || RESERVED_KEYS.contains(key)) {
                problems.add("'" + key + "' cannot be used as an additional attribute key (empty or reserved by the Peppol envelope)");
            }
        }

        IMimeType mime = null;
        if (xmlPayload == null && payloads == 1 && mimeType != null && !mimeType.isBlank()) {
            try {
                mime = MimeTypeParser.parseMimeType(mimeType);
                if (mime == null) {
                    problems.add("'" + mimeType + "' is not a MIME type");
                }
            } catch (MimeTypeParserException ex) {
                problems.add("'" + mimeType + "' is not a MIME type: " + ex.getMessage());
            }
        }

        if (!problems.isEmpty()) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT, "The envelope cannot be built: " + String.join("; ", problems));
        }

        String instanceId = instanceIdentifier != null ? instanceIdentifier : PeppolSBDHData.createRandomSBDHInstanceIdentifier();
        PeppolSBDHData data = new PeppolSBDHData(PeppolIdentifierFactory.INSTANCE);
        data.setSender(sender);
        data.setReceiver(receiver);
        data.setDocumentType(PeppolIds.parseDocumentTypeId(documentTypeId));
        data.setProcess(PeppolIds.parseProcessId(processId));
        data.setCountryC1(countryC1);
        data.setDocumentIdentification(std, ver, typ, instanceId,
                creationDateTime != null ? XMLOffsetDateTime.of(creationDateTime) : XMLOffsetDateTime.now());
        if (mlsToId != null) {
            data.setMLSToScheme(mlsToId.getScheme());
            data.setMLSToValue(mlsToId.getValue());
        }
        if (mlsType != null) {
            data.setMLSType(mlsType);
        }
        attributes.forEach((k, v) -> data.additionalAttributes().put(k, v));

        if (xmlPayload != null) {
            data.setBusinessMessage(xmlPayload);
        } else if (binaryPayload != null) {
            data.setBusinessMessageBinaryOnly(binaryPayload, mime, binaryTextEncoding);
        } else {
            data.setBusinessMessageTextOnly(textPayload, mime);
        }

        List<String> missing = new ArrayList<>();
        if (!data.areAllFieldsSet(missing::add)) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT,
                    "The envelope cannot be built, missing: " + String.join(", ", missing));
        }

        byte[] xml = new SBDMarshaller().getAsBytes(new PeppolSBDHDataWriter().createStandardBusinessDocument(data));
        if (xml == null) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT, "The envelope could not be serialised to XML");
        }
        return new BuiltEnvelope(instanceId, xml, mlsType == EPeppolMLSType.ALWAYS_SEND);
    }

    private static IParticipantIdentifier participant(String role, String value, List<String> problems) {
        if (value == null) {
            problems.add(role + " is missing");
            return null;
        }
        IdCheck check = PeppolIds.checkParticipantId(value, IdUsage.NEW);
        if (!check.isValid()) {
            problems.add(role + ": " + String.join("; ", check.getErrors()));
            return null;
        }
        return PeppolIds.parseParticipantId(value);
    }

    private static void checkId(String role, String value, IdCheck check, List<String> problems) {
        if (value == null) {
            problems.add(role + " is missing");
        } else if (!check.isValid()) {
            problems.add(role + ": " + String.join("; ", check.getErrors()));
        }
    }
}
