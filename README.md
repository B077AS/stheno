<h1 align="center">
  <img src=".github/icon.png" alt="Stheno icon" width="96"><br>
  Stheno
</h1>

<h3 align="center">
  The business side of Peppol, for Spring Boot.<br>
  Receives, checks and confirms. Never transmits.
</h3>

<p align="center">
  For the ERP, billing or accounting application behind a Peppol Access Point:<br>
  identifier checks, lookups, an inbound endpoint with handlers, acknowledgements and a fake Access Point for your tests.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.b077as/stheno-spring-boot-starter"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.b077as/stheno-spring-boot-starter?label=Maven%20Central&color=C71A36"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 4" src="https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white">
  <img alt="Peppol Envelope 2.0.2 | MLS 1.1" src="https://img.shields.io/badge/Peppol-Envelope%202.0.2%20%7C%20MLS%201.1-17806F">
  <a href="https://github.com/phax/phoss-ap"><img alt="Access Point: phoss-ap 0.13+" src="https://img.shields.io/badge/Access%20Point-phoss--ap%200.13%2B-0D4A40"></a>
  <img alt="License: Apache 2.0" src="https://img.shields.io/badge/License-Apache%202.0-blue">
  <img alt="Status: early" src="https://img.shields.io/badge/status-early-E8A200">
</p>

---

Peppol integration for business applications (ERP, billing, accounting) that sit behind a Peppol Access Point. Stheno **receives,
checks and confirms**; it never transmits documents (it is not an Access Point and does not speak AS4).

- **Inbound**: an endpoint your Access Point forwards documents to, duplicate detection, routing to your `@PeppolInboundHandler`
  methods, background processing with retries.
- **Acknowledgements to your Access Point** (each switchable): the receiver country for Peppol Reporting, and the Message Level
  Status verdict (`AP` / `AB` / `RE` with issues) from which the Access Point builds the MLS.
- **MLS answers** to documents you sent, parsed, to your `@PeppolMlsHandler`.
- **Checks**: Peppol identifiers (also as Bean Validation annotations), document validation with the official rule sets, cached
  "can this participant receive this document?" lookups.
- **Building**: Peppol envelopes (SBDH 2.0.2) and Invoice Responses (T111), to hand to your Access Point yourself.
- **Testing**: a fake Access Point that behaves like phoss-ap. No certificates, no network.

Built on Philip Helger's peppol-commons, phive and ddd. First supported Access Point: [phoss-ap](https://github.com/phax/phoss-ap)
0.13+ (HTTP forwarding). Java 21, Spring Boot 4. Licence: Apache 2.0. Status: early (0.x), see the [changelog](CHANGELOG.md).

## Modules

| Module | What |
|---|---|
| `stheno-core` | Everything without Spring (usable on its own) |
| `stheno-spring-boot-starter` | Auto-configuration, endpoint, annotations, events, metrics, health |
| `stheno-test` | `FakeAccessPoint` and a valid sample BIS 3 invoice for your tests |

```xml
<dependency>
  <groupId>io.github.b077as</groupId>
  <artifactId>stheno-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>io.github.b077as</groupId>
  <artifactId>stheno-test</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

Optional features switch on when you add their libraries (and set `enabled`):

| Feature | Add | Property |
|---|---|---|
| Lookups | `com.helger.peppol:peppol-smp-client` | `stheno.lookup.enabled=true` |
| Validation | `com.helger.phive:phive-xml`, `com.helger.phive.rules:phive-rules-peppol`, `com.helger:ddd` | `stheno.validation.enabled=true` |
| Bean Validation annotations | `jakarta.validation-api` + an implementation (Spring Boot's `spring-boot-starter-validation`) | |
| JDBC duplicate store (several nodes) | a `DataSource` bean | `stheno.inbound.store=jdbc` |

## Handling documents

```java
@Component
class InvoiceInbox {

    @PeppolInboundHandler(documentType =
        "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1")
    InboundOutcome invoice(InboundDocument doc) {
        Element invoice = doc.getPayload().getXml();
        if (!knownBuyer(invoice)) {
            return InboundOutcome.rejected("Unknown buyer",
                    List.of(MlsIssue.businessRule("/Invoice/cbc:BuyerReference", "Not one of our orders")));
        }
        store(invoice);                         // throw if you cannot store it now: it is retried
        return InboundOutcome.accepted("DK");   // receiver (C4) country for Peppol Reporting
    }

    @PeppolMlsHandler
    void answer(ReceivedMls mls) {
        // mls.getReferencedInstanceIdentifier() = the envelope id of the document you sent
        markDelivered(mls.getReferencedInstanceIdentifier(), mls.isAccepted(), mls.getIssues());
    }
}
```

- A handler without `documentType` takes everything else. A document no handler takes is rejected (`RE`, reason `FD`).
- Return `rejected` for unacceptable content; **throw** for "not now" (database down): nothing is decided, the document is retried.
- The same document delivered again (Access Point retry or replay) does not reach your handler a second time.
- `doc.getDelivery().getVerificationResult()` tells what phoss-ap's own validation said, if it ran.

## Configuration and the matching phoss-ap settings

```properties
stheno.inbound.path=/peppol/inbound
stheno.inbound.forwarding-mode=async          # = phoss-ap forwarding.mode (async: http_post_async, sync: http_post_sync)
stheno.inbound.processing=background          # background (recommended) | inline
stheno.inbound.auth-header-name=Authorization
stheno.inbound.auth-header-value=Bearer change-me
stheno.inbound.store=memory                   # memory | jdbc
stheno.inbound.duplicate-retention=30d

stheno.access-point.base-url=https://ap.example.com
stheno.access-point.token=change-me           # = phoss-ap phase4.api.requiredtoken
stheno.access-point.report-receiver-country=true
stheno.access-point.mls-verdict=true
```

phoss-ap side:

```properties
forwarding.mode=http_post_async
forwarding.http.endpoint=https://erp.example.com/peppol/inbound
forwarding.http.headers.1.name=Authorization
forwarding.http.headers.1.value=Bearer change-me
mls.sending.trigger=api      # the positive MLS waits for your verdict (keep auto and set stheno.access-point.mls-verdict=false otherwise)
```

Why the MLS verdict matters: Peppol only allows the positive MLS code `AP` after "a verifiable confirmation" from the receiver's
system (MLS 1.1.0). With `mls-verdict=true` Stheno gives exactly that confirmation, or the rejection with your reasons. If your
handler does not answer in time, phoss-ap's watchdog sends a fallback `AB` itself.

How Stheno answers the forwarding call:

| Situation | async forwarding | sync forwarding |
|---|---|---|
| received, handled in background | `200` | `200 {"status":"received"}` (country reported later) |
| inline, accepted | `200` | `200 {"countryCodeC4":"DK"}` |
| inline, rejected, MLS verdict on | `200` + `RE` via API | `200` + `RE` via API |
| inline, rejected, MLS verdict off | `200` | `200 {"retry":"none"}` (phoss-ap sends `RE` itself) |
| inline, handler threw | `503` (phoss-ap retries) | `503` |
| duplicate | `200` | `200` (same country as the first time) |
| broken envelope | `400` | `200 {"retry":"none"}` |
| wrong auth header / too large | `401` / `413` | `401` / `413` |

The endpoint is a normal Spring MVC endpoint: your Spring Security rules apply. Exclude it from CSRF protection (the Access Point is
a machine client) and protect it with the header above or your own rules. Use HTTPS.

## Events, metrics, health

- Spring events (`SthenoEvents`): `DocumentReceived`, `DuplicateReceived`, `DocumentHandled`, `HandlerFailed`, `MlsReceived`,
  `AccessPointAcknowledged`.
- Micrometer: `stheno.inbound.received`, `stheno.inbound.duplicates`, `stheno.inbound.handled{outcome}`,
  `stheno.inbound.handler.failures`, `stheno.ap.acknowledgements{result}`.
- Actuator health `stheno`: handlers, pending documents, switches, `consecutiveAcknowledgementFailures`.
- Handlers run on virtual threads; define an `Executor` bean named `sthenoInboundExecutor` to use your own.

## Checks and building blocks

```java
// Identifiers (peppol-commons rules, code list states, check digits)
IdCheck check = PeppolIds.checkParticipantId("0208:0123456749", IdUsage.NEW);
@PeppolParticipantId String peppolId;                     // Bean Validation on your own DTO fields

// Lookup (stheno.lookup.enabled=true): "can this customer receive invoices on Peppol?"
ReceiverCapability c = lookup.lookup("0088:5798000000001", invoiceDocType, billingProcess);
c.canReceive();

// Validation (stheno.validation.enabled=true, stheno.validation.warm-up=eu.peppol.bis3:invoice:latest)
ValidationReport report = validator.validate(doc);
if (!report.isValid()) return InboundOutcome.rejected("Invalid invoice", report.toMlsIssues());

// Invoice Response (T111) in an envelope, to hand to your Access Point
BuiltEnvelope reply = InvoiceResponseBuilder.create()
        .id("imr-1").sender("0088:5798000000001", "Buyer A/S").receiver("0208:0123456749", "Seller NV")
        .invoice("INV-0001", "380", null)
        .status(InvoiceResponseBuilder.Status.RE)
        .reason(InvoiceResponseBuilder.Reason.REF, "Order reference not found")
        .buildEnvelope("DK");
```

`EnvelopeBuilder` wraps any document (XML, binary or text) in a Peppol envelope; the sender country is required, never defaulted.

## Testing

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxTest {

    static final FakeAccessPoint AP = FakeAccessPoint.start();

    @DynamicPropertySource
    static void stheno(DynamicPropertyRegistry registry) {
        AP.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @LocalServerPort int port;

    @Test
    void acceptsInvoices() {
        AP.forwardTo(URI.create("http://localhost:" + port + "/peppol/inbound"));
        Delivery d = AP.deliver(FakeAccessPoint.sampleInvoiceEnvelope());
        assertThat(AP.awaitMlsVerdict(d.getInstanceIdentifier(), Duration.ofSeconds(5)).isAccepted()).isTrue();
        assertThat(AP.awaitCountryReport(d.getInstanceIdentifier(), Duration.ofSeconds(5)).getReceiverCountry()).isEqualTo("DK");
    }
}
```

The fake can also deliver the same envelope twice, broken envelopes, MLS answers (`deliverMls`), phoss-ap verification headers, and
answer the acknowledgement calls with any status (`respondToMlsVerdictWith(500)`).

## Not in scope

Sending documents, AS4, being an Access Point, signing, format conversion, commercial Access Point APIs (they can be plugged in by
implementing the inbound side yourself: `InboundProcessor` works with any transport).

## Building

JDK 21 and Maven 3.9+: `mvn install`. Live network test (Peppol test SML, read-only): `mvn test -DargLine="-Dstheno.live=true"`.
