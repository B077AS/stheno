package io.github.b077as.stheno.test;

import com.helger.peppol.mls.EPeppolMLSResponseCode;
import com.helger.peppol.mls.PeppolMLSBuilder;
import com.helger.peppol.mls.PeppolMLSLineResponseBuilder;
import com.helger.peppol.mls.PeppolMLSMarshaller;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.b077as.stheno.ap.PhossApForwarding;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeBuilder;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.PeppolDocumentKinds;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A fake phoss-ap for tests, on both sides of the application:
 * <ul>
 * <li><b>forwarding</b>: {@link #deliver} POSTs an envelope to the application's inbound endpoint exactly like phoss-ap's HTTP
 * forwarding (body = complete SBD, headers {@code X-SBDH-Instance-ID}, {@code X-phoss-AP-Transaction-ID}, optional
 * {@code X-Verification-*} and custom headers);</li>
 * <li><b>receiver backend API</b>: a local HTTP server answering {@code POST /api/inbound/report} and {@code POST /api/mls/send} and
 * recording every call, so that tests can wait for the application's acknowledgements.</li>
 * </ul>
 * No certificates, no Peppol network, nothing leaves the machine.
 *
 * <pre>
 * static final FakeAccessPoint AP = FakeAccessPoint.start();
 *
 * &#64;DynamicPropertySource
 * static void stheno(DynamicPropertyRegistry registry) {
 *     AP.springProperties().forEach(registry::add);
 * }
 *
 * &#64;Test
 * void acceptsInvoices(&#64;LocalServerPort int port) {
 *     AP.forwardTo(URI.create("http://localhost:" + port + "/peppol/inbound"));
 *     Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
 *     assertThat(AP.awaitMlsVerdict(d.getInstanceIdentifier(), Duration.ofSeconds(5)).isAccepted()).isTrue();
 * }
 * </pre>
 */
public final class FakeAccessPoint implements AutoCloseable {

    /** Sender of {@link #sampleInvoiceEnvelope()} (Belgian enterprise number, valid check digit). */
    public static final String SAMPLE_SENDER = "0208:0123456749";
    /** Receiver of {@link #sampleInvoiceEnvelope()} (GLN, valid check digit). */
    public static final String SAMPLE_RECEIVER = "0088:5798000000001";
    public static final String BIS3_INVOICE_DOCUMENT_TYPE =
            "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";
    public static final String BIS3_BILLING_PROCESS = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
    public static final String DEFAULT_TOKEN = "fake-ap-token";

    private final HttpServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<RecordedMlsVerdict> verdicts = new CopyOnWriteArrayList<>();
    private final List<RecordedCountryReport> countryReports = new CopyOnWriteArrayList<>();
    private final AtomicInteger mlsStatus = new AtomicInteger(200);
    private final AtomicInteger reportStatus = new AtomicInteger(200);
    private final Map<String, String> customHeaders = new LinkedHashMap<>();
    private volatile URI target;
    private volatile String verificationResult;
    private volatile String verificationDetailsJson;

    private FakeAccessPoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/inbound/report", this::onReport);
        server.createContext("/api/mls/send", this::onMlsSend);
        server.start();
    }

    /** Starts the fake on a free local port. */
    public static FakeAccessPoint start() {
        try {
            return new FakeAccessPoint();
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot start the fake Access Point", ex);
        }
    }

    /** Base URL of the fake's receiver backend API (for {@code stheno.access-point.base-url}). */
    public URI getBaseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The settings that point the Stheno starter at this fake. */
    public Map<String, String> springProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("stheno.access-point.base-url", getBaseUrl().toString());
        p.put("stheno.access-point.token", DEFAULT_TOKEN);
        p.put("stheno.access-point.acknowledge-delay", "10ms");
        p.put("stheno.access-point.retry-delay", "100ms");
        p.put("stheno.inbound.handler-retry-delay", "100ms");
        return p;
    }

    /** The application's inbound endpoint, e.g. {@code http://localhost:8080/peppol/inbound}. */
    public FakeAccessPoint forwardTo(URI inboundEndpoint) {
        this.target = Objects.requireNonNull(inboundEndpoint, "inboundEndpoint");
        return this;
    }

    /** A static header sent with every forwarding call (phoss-ap {@code forwarding.http.headers.N.*}), e.g. for authentication. */
    public FakeAccessPoint header(String name, String value) {
        customHeaders.put(name, value);
        return this;
    }

    /** Simulates phoss-ap's own inbound verification: {@code passed}, {@code rejected} or {@code unverified} (null = none). */
    public FakeAccessPoint verification(String result, String detailsJsonOrNull) {
        this.verificationResult = result;
        this.verificationDetailsJson = detailsJsonOrNull;
        return this;
    }

    /** HTTP status the fake answers {@code /api/mls/send} with (200 sent, 409 already decided, 503 disabled, 500 error ...). */
    public FakeAccessPoint respondToMlsVerdictWith(int status) {
        mlsStatus.set(status);
        return this;
    }

    /** HTTP status the fake answers {@code /api/inbound/report} with (200, 400 already set, 404 unknown ...). */
    public FakeAccessPoint respondToCountryReportWith(int status) {
        reportStatus.set(status);
        return this;
    }

    /** Forgets all recorded calls and resets the answers and options. */
    public void reset() {
        verdicts.clear();
        countryReports.clear();
        mlsStatus.set(200);
        reportStatus.set(200);
        customHeaders.clear();
        verificationResult = null;
        verificationDetailsJson = null;
    }

    /** Forwards an envelope to the application, like phoss-ap does. Delivering the same envelope again simulates a retry or replay. */
    public Delivery deliver(BuiltEnvelope envelope) {
        return deliver(envelope.getXml(), envelope.getInstanceIdentifier());
    }

    /** Forwards raw bytes (e.g. a broken envelope) to the application. */
    public Delivery deliver(byte[] envelope, String instanceIdentifier) {
        if (target == null) {
            throw new IllegalStateException("Call forwardTo(...) first");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/xml")
                .header(PhossApForwarding.HEADER_SBDH_INSTANCE_ID, instanceIdentifier)
                .header(PhossApForwarding.HEADER_TRANSACTION_ID, UUID.randomUUID().toString());
        if (verificationResult != null) {
            b.header(PhossApForwarding.HEADER_VERIFICATION_RESULT, verificationResult);
            if (verificationDetailsJson != null) {
                b.header(PhossApForwarding.HEADER_VERIFICATION_DETAILS,
                        Base64.getEncoder().encodeToString(verificationDetailsJson.getBytes(StandardCharsets.UTF_8)));
            }
        }
        customHeaders.forEach(b::header);
        try {
            HttpResponse<String> response = http.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(envelope)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Delivery(instanceIdentifier, response.statusCode(), response.body());
        } catch (IOException ex) {
            throw new UncheckedIOException("Forwarding to " + target + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Forwards a Message Level Status answering a document the application sent (as if the receiver's Access Point had answered it).
     *
     * @param answeredInstanceIdentifier SBDH instance id of the application's sent document
     * @param responseCode               {@code AP}, {@code AB} or {@code RE}
     * @param issues                     line responses (needed for {@code RE})
     */
    public Delivery deliverMls(String answeredInstanceIdentifier, String responseCode, MlsIssue... issues) {
        PeppolMLSBuilder mls = new PeppolMLSBuilder(EPeppolMLSResponseCode.getFromIDOrThrow(responseCode))
                .randomID()
                .issueDateTimeNow()
                .senderParticipantID(PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme("0242:000111"))
                .receiverParticipantID(PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme("0242:000222"))
                .referenceId(answeredInstanceIdentifier);
        for (MlsIssue issue : issues) {
            PeppolMLSLineResponseBuilder line = new PeppolMLSLineResponseBuilder().errorField(issue.getLocation());
            line.addResponse(r -> {
                switch (issue.getReason()) {
                    case BV -> r.statusReasonCodeBusinessRuleViolationFatal();
                    case BW -> r.statusReasonCodeBusinessRuleViolationWarning();
                    case SV -> r.statusReasonCodeSyntaxViolation();
                    case FD -> r.statusReasonCodeFailureOfDelivery();
                }
                r.description(issue.getDescription());
            });
            mls.addLineResponse(line);
        }
        Element root = new PeppolMLSMarshaller().getAsDocument(mls.build()).getDocumentElement();
        BuiltEnvelope envelope = EnvelopeBuilder.create()
                .sender("0242:000111")
                .receiver("0242:000222")
                .documentType("urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2::ApplicationResponse##urn:peppol:edec:mls:1.0::2.1")
                .process(PeppolDocumentKinds.MLS_PROCESS_ID)
                .senderCountry("BE")
                .payload(root)
                .build();
        return deliver(envelope);
    }

    /** All {@code /api/mls/send} calls so far. */
    public List<RecordedMlsVerdict> getMlsVerdicts() {
        return List.copyOf(verdicts);
    }

    /** All {@code /api/inbound/report} calls so far. */
    public List<RecordedCountryReport> getCountryReports() {
        return List.copyOf(countryReports);
    }

    /** Waits for the MLS verdict of a document. */
    public RecordedMlsVerdict awaitMlsVerdict(String instanceIdentifier, Duration timeout) {
        return await(verdicts, v -> v.getInstanceIdentifier().equals(instanceIdentifier), timeout)
                .orElseThrow(() -> new AssertionError("No MLS verdict for " + instanceIdentifier + " within " + timeout + "; got " + verdicts));
    }

    /** Waits for the receiver country report of a document. */
    public RecordedCountryReport awaitCountryReport(String instanceIdentifier, Duration timeout) {
        return await(countryReports, r -> r.getInstanceIdentifier().equals(instanceIdentifier), timeout)
                .orElseThrow(() -> new AssertionError("No receiver country for " + instanceIdentifier + " within " + timeout + "; got "
                        + countryReports));
    }

    /** {@code true} if no MLS verdict for the document arrives within the time (e.g. for documents that must not be answered). */
    public boolean noMlsVerdictWithin(String instanceIdentifier, Duration time) {
        return await(verdicts, v -> v.getInstanceIdentifier().equals(instanceIdentifier), time).isEmpty();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- sample data -------------------------------------------------------------------------------------------------

    /** Stheno's own minimal Peppol BIS Billing 3.0 invoice (valid against the official rules). */
    public static Element sampleInvoice() {
        try (InputStream in = FakeAccessPoint.class.getResourceAsStream("sample-invoice.xml")) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().parse(in).getDocumentElement();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read the sample invoice", ex);
        }
    }

    /** The sample invoice in an envelope from {@link #SAMPLE_SENDER} (BE) to {@link #SAMPLE_RECEIVER}, with a new instance id. */
    public static BuiltEnvelope sampleInvoiceEnvelope() {
        return sampleEnvelope(sampleInvoice());
    }

    /** Any BIS 3 invoice in an envelope from {@link #SAMPLE_SENDER} (BE) to {@link #SAMPLE_RECEIVER}. */
    public static BuiltEnvelope sampleEnvelope(Element invoice) {
        return EnvelopeBuilder.create()
                .sender(SAMPLE_SENDER)
                .receiver(SAMPLE_RECEIVER)
                .documentType(BIS3_INVOICE_DOCUMENT_TYPE)
                .process(BIS3_BILLING_PROCESS)
                .senderCountry("BE")
                .payload(invoice)
                .build();
    }

    // ---- receiver backend API ----------------------------------------------------------------------------------------

    private void onReport(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        countryReports.add(new RecordedCountryReport(query.get("sbdhInstanceID"), query.get("c4CountryCode"),
                exchange.getRequestHeaders().getFirst("X-Token")));
        exchange.getRequestBody().readAllBytes();
        answer(exchange, reportStatus.get(), "{\"status\":\"updated\"}");
    }

    @SuppressWarnings("unchecked")
    private void onMlsSend(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> json = MiniJson.parseObject(body);
        List<MlsIssue> issues = new ArrayList<>();
        Object list = json.get("issues");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> i = (Map<String, Object>) o;
                issues.add(new MlsIssue(MlsIssue.Reason.valueOf((String) i.get("statusReasonCode")), (String) i.get("errorField"),
                        (String) i.get("description")));
            }
        }
        verdicts.add(new RecordedMlsVerdict((String) json.get("sbdhInstanceID"), (String) json.get("responseCode"),
                (String) json.get("responseText"), List.copyOf(issues), exchange.getRequestHeaders().getFirst("X-Token")));
        answer(exchange, mlsStatus.get(), "{\"status\":\"sent\"}");
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static <T> Optional<T> await(List<T> list, Predicate<T> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (T t : list) {
                if (match.test(t)) {
                    return Optional.of(t);
                }
            }
            if (System.nanoTime() > deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }
}
