# Changelog

All published versions of Stheno, newest first. Every module (`stheno-core`, `stheno-spring-boot-starter`, `stheno-test`) is released
with the same version number. Stheno is early software (version 0.x): APIs can still change between versions, and this file says when
they do.

The latest version is the one on [Maven Central](https://central.sonatype.com/artifact/io.github.b077as/stheno-spring-boot-starter).

## 0.1.0 - 2026-11-22

First release.

### Added
- **Identifiers.** `PeppolIds` checks participant, document type and process identifiers with the peppol-commons rules (code list
  states, check digits where peppol-commons has them), normalises them (participant ids are case-insensitive, document type and
  process ids are case-sensitive) and reports every problem at once as an `IdCheck`. `IdUsage.RECEIVED` turns code list problems
  into warnings, `IdUsage.NEW` into errors. Bean Validation annotations `@PeppolParticipantId`, `@PeppolDocumentTypeId` and
  `@PeppolProcessId`.
- **Envelopes.** `EnvelopeReader` reads Peppol Business Message Envelopes (SBDH 2.0.2) with an XML, binary or text payload.
  `EnvelopeBuilder` builds them and reports all problems at once; the sender country (`COUNTRY_C1`) is required and never defaulted.
- **Inbound processing.** `InboundProcessor` receives a document in two steps (`receive`, `handle`), detects duplicates by envelope
  instance id and receiver, and routes to `InboundHandler`s by document type. A handler that throws leaves nothing recorded, so the
  Access Point's retry reaches it again. `InMemoryReceivedMessageStore` and `JdbcReceivedMessageStore` (plain JDBC, safe across
  nodes). Received Message Level Status documents go to an `MlsHandler` through `MlsReader`. A document no handler takes is not
  taken over (or rejected, if configured). A delivery of a document that is still undecided is not confirmed, and the JDBC store
  lets a new delivery take over one left unfinished longer than the processing timeout.
- **Acknowledgements to your Access Point**, each with its own switch: the receiver country for Peppol Reporting and the MLS
  verdict (`AP` / `AB` / `RE` with issues), written for phoss-ap 0.13+ (`/api/inbound/report`, `/api/mls/send`). A rejection the
  Access Point does not take is reported, and a rejection made of warnings only is refused.
- **Lookups.** `PeppolLookup` with `SmpPeppolLookup` (SML and SMP over DNS and HTTPS, with the OpenPeppol trust store) and
  `CachingPeppolLookup`. Network errors are never cached.
- **Validation.** `DocumentValidator` validates with the official rule sets through phive, picks the rule set with ddd, and
  turns a `ValidationReport` into MLS issues. Optional warm-up at startup.
- **Invoice Response (T111 3.1).** `InvoiceResponseBuilder` for all statuses, with envelope. Every status passes phive's official
  `invoice-message-response` rules in the tests.
- **Spring Boot 4 starter.** Auto-configuration, a `@PeppolInboundHandler` and `@PeppolMlsHandler` annotation model, the inbound
  endpoint (constant-time header authentication, size limit), background handling on virtual threads with retries, Spring events,
  Micrometer metrics and an Actuator health indicator. Optional features switch on when their libraries are on the classpath.
- **Test support.** `stheno-test` has `FakeAccessPoint`, which behaves like phoss-ap (forwarding with its headers, sync and async
  answers, the acknowledgement API with configurable answers) and a valid BIS 3 sample invoice. No certificates, no network.

### Not included
- Sending documents, AS4, being an Access Point, signing and format conversion. See the README.
