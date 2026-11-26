# Changelog

All published versions of Stheno, newest first. Every module (`stheno-core`, `stheno-spring-boot-starter`, `stheno-test`) is released
with the same version number. Stheno is early software (version 0.x): APIs can still change between versions, and this file says when
they do.

The latest version is the one on [Maven Central](https://central.sonatype.com/artifact/io.github.b077as/stheno-spring-boot-starter).

## 0.2.0 - 2026-11-26

What happens to the documents you sent: delivery status from the Message Level Status and invoice status from Invoice Responses. Also
fixes to how received documents are confirmed or rejected, and to the JDBC store.

### Added
- **Delivery tracking.** `DeliveryTracker` follows sent documents through their Message Level Status: the first MLS counts, silence
  is normal unless a positive answer was requested, a requested answer that does not arrive is reported as overdue, and an MLS that
  is faster than the registration is kept. `InMemoryDeliveryStore` and `JdbcDeliveryStore`; in the starter with
  `stheno.delivery.enabled=true`, with the events `DeliveryDecided`, `DeliveryOverdue`, `MlsForUnknownDocument` and `MlsIgnored`,
  and counters under `stheno.delivery.*`. `BuiltEnvelope` says whether a positive MLS was requested.
- **Reading Invoice Responses.** `InvoiceResponseReader` reads a Peppol Invoice Response (T111) into an `InvoiceResponse`;
  `@PeppolInvoiceResponseHandler` methods receive them in the starter (core: `InvoiceResponseHandler`).
- **Invoice status.** The delivery tracker matches responses to sent invoices (`InvoiceReference`: participants, number, type code,
  date), keeps them all and derives the status in the order the BIS allows (`ResponseHistory`); responses out of sequence do not
  change it. Events `InvoiceResponseTracked`, `InvoiceResponseOutOfSequence` and `InvoiceResponseForUnknownInvoice`, counters under
  `stheno.delivery.responses`.
- `InvoiceResponseBuilder.replyTo` prefills a response from the UBL invoice or credit note it answers.
- `SthenoException.ErrorCode.INVALID_DOCUMENT`.
- `RejectionNotDelivered` event and `stheno.ap.rejections.undelivered` counter: the Access Point did not take a rejection (an MLS
  was already decided, MLS sending is disabled, the verdict is switched off, or every attempt failed), so the sender may believe the
  document was delivered.
- `stheno.inbound.processing-timeout` (default 10 minutes): documents left without a decision for longer are reported
  (`DocumentsUnfinished` event, `ReceivedMessageStore.findUnfinished`).
- `stheno.inbound.unhandled-documents` (`retry` / `reject`, core: `UnhandledDocumentPolicy`).

### Changed
Check these when upgrading from 0.1.0.
- A document no handler takes is no longer rejected (`RE`, reason `FD`): it is not taken over and answered `503`, so that the Access
  Point delivers it again. `InboundProcessor.receive` throws `NO_HANDLER`. For the old behaviour set
  `stheno.inbound.unhandled-documents=reject`.
- A duplicate of a document that has no decision yet is answered `503` instead of `200`.
- The JDBC store lets a new delivery take over a document left without a decision longer than the processing timeout. Set the timeout
  longer than your slowest handler.
- `InboundOutcome.rejected` refuses a rejection made of warnings (`BW`) only, as MLS requires.
- `Acknowledgement` has a new field `rejectionNotDelivered` and `BuiltEnvelope` a new field `positiveMlsRequested` (their
  constructors changed). `SthenoException.ErrorCode` has the new value `NO_HANDLER`.
- The columns of the JDBC received message store are unchanged, so an existing table keeps working.
- The JDBC store writes times as UTC; 0.1.0 wrote the local time of the JVM. Unless that was UTC, existing rows seem older or younger
  by the difference, once: they may expire or count as unfinished that much earlier or later.
- New tables use `DATETIME(6)` and `utf8mb4` on MySQL and MariaDB, `DATETIME2(6)` and `NVARCHAR` on SQL Server, and get an index on
  `received_at`. Existing tables are not altered. On MySQL and MariaDB change an existing `received_at` to `DATETIME(6)`.
- The starter deletes expired received messages itself (`ReceivedMessageStore.purgeExpired`).

### Fixed
- With a pool that has auto-commit switched off, the JDBC store saved nothing, so duplicates reached the handler again.
- With Spring's `TransactionAwareDataSourceProxy`, the JDBC store worked inside the application's transaction.
- The JDBC table of received messages grew without limit: expired rows were never deleted.
- Nodes in different time zones, or the end of summer time, could make the JDBC store see a document as older than it was.
- Creating the JDBC table failed at the second start when its name had a schema, was skipped when another schema had a table of that
  name, and could fail when several nodes started together.
- The automatically created table did not work on SQL Server, and on MySQL and MariaDB lost fractions of seconds.
- Documents could be lost with the JDBC store: after the application stopped during a handling, a new delivery was answered as a
  duplicate and never handled.
- A document stayed registered as being handled, or was forgotten without a trace, when a handler threw an `Error`, when the
  executor refused the task, or when the application stopped while a retry was waiting.
- A rejection that came too late for the Access Point was only logged as information.
- Retrying a failed acknowledgement repeated the call that had already succeeded.
- The README example rejected a document for a business reason. An MLS rejection is only for technical faults; answer business
  objections with an Invoice Response.

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
  nodes). Received Message Level Status documents go to an `MlsHandler` through `MlsReader`.
- **Acknowledgements to your Access Point**, each with its own switch: the receiver country for Peppol Reporting and the MLS
  verdict (`AP` / `AB` / `RE` with issues), written for phoss-ap 0.13+ (`/api/inbound/report`, `/api/mls/send`).
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
