package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import io.github.b077as.stheno.envelope.EnvelopeReader;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundPayload;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;
import io.github.b077as.stheno.reply.InvoiceResponse;
import io.github.b077as.stheno.reply.InvoiceResponseBuilder;
import io.github.b077as.stheno.reply.InvoiceResponseReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tracker's behaviour, the same for every store. */
abstract class DeliveryTrackerTest {

    private static final Instant START = Instant.parse("2026-11-25T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final List<String> told = new ArrayList<>();
    private boolean listenerFails;
    private DeliveryStore store;
    private DeliveryTracker tracker;

    abstract DeliveryStore createStore();

    @BeforeEach
    void setUp() {
        store = createStore();
        DeliveryListener recorder = new DeliveryListener() {
            @Override
            public void onDecided(TrackedDocument document, DeliveryStatus status) {
                tell("decided " + document.getInstanceIdentifier() + " " + status);
            }

            @Override
            public void onOverdue(TrackedDocument document) {
                tell("overdue " + document.getInstanceIdentifier());
            }

            @Override
            public void onUnknownDocument(ReceivedMls mls) {
                tell("unknown " + mls.getReferencedInstanceIdentifier());
            }

            @Override
            public void onIgnored(TrackedDocument document, ReceivedMls ignored) {
                tell("ignored " + ignored.getMlsId());
            }

            @Override
            public void onInvoiceResponse(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
                tell("response " + document.getInstanceIdentifier() + " " + response.getStatus() + " " + history.getStatus());
            }

            @Override
            public void onResponseOutOfSequence(TrackedDocument document, TrackedResponse response, ResponseHistory history) {
                tell("unexpected " + document.getInstanceIdentifier() + " " + response.getStatus() + " " + history.getStatus());
            }

            @Override
            public void onResponseForUnknownInvoice(InvoiceReference invoice, TrackedResponse response) {
                tell("unknown invoice " + invoice.getInvoiceNumber());
            }
        };
        tracker = new DeliveryTracker(store, List.of(recorder), Duration.ofHours(24), clock);
    }

    @Test
    void silenceIsNormalWhenOnlyFailuresAreReported() {
        TrackedDocument d = tracker.track("doc-1", false);
        assertThat(tracker.statusOf(d)).isEqualTo(DeliveryStatus.NO_REJECTION);
        clock.advance(Duration.ofDays(30));
        assertThat(tracker.statusOf(tracker.find("doc-1").orElseThrow())).isEqualTo(DeliveryStatus.NO_REJECTION);
        assertThat(tracker.findOverdue()).isEmpty();
        assertThat(tracker.reportOverdue()).isZero();
    }

    @Test
    void requestedPositiveMlsBecomesOverdueAfter24HoursAndIsReportedOnce() {
        tracker.track("doc-1", true);
        clock.advance(Duration.ofHours(23));
        assertThat(tracker.statusOf(tracker.find("doc-1").orElseThrow())).isEqualTo(DeliveryStatus.AWAITING);
        assertThat(tracker.reportOverdue()).isZero();

        clock.advance(Duration.ofHours(2));
        assertThat(tracker.statusOf(tracker.find("doc-1").orElseThrow())).isEqualTo(DeliveryStatus.OVERDUE);
        assertThat(tracker.reportOverdue()).isEqualTo(1);
        assertThat(tracker.reportOverdue()).isZero();
        assertThat(tracker.findOverdue()).singleElement().extracting(TrackedDocument::getInstanceIdentifier).isEqualTo("doc-1");
        assertThat(told).containsExactly("overdue doc-1");

        // A late answer still counts
        assertThat(tracker.onMls(mls("doc-1", "mls-1", "AP"))).isEqualTo(MlsCorrelation.DECIDED);
        assertThat(tracker.statusOf(tracker.find("doc-1").orElseThrow())).isEqualTo(DeliveryStatus.CONFIRMED);
        assertThat(tracker.findOverdue()).isEmpty();
    }

    @Test
    void failedOverdueReportIsTriedAgain() {
        tracker.track("doc-1", true);
        clock.advance(Duration.ofHours(25));
        listenerFails = true;
        assertThat(tracker.reportOverdue()).isZero();
        listenerFails = false;
        assertThat(tracker.reportOverdue()).isEqualTo(1);
    }

    @Test
    void rejectionKeepsItsReasons() {
        tracker.track("doc-1", false);
        ReceivedMls rejection = new ReceivedMls("RE", "doc-1", "Invalid invoice", List.of(
                MlsIssue.businessRule("/Invoice/cbc:ID", "BR-02 violated"), MlsIssue.warning(null, "a warning")),
                "mls-1", "iso6523-actorid-upis::0242:000111");
        assertThat(tracker.onMls(rejection)).isEqualTo(MlsCorrelation.DECIDED);

        TrackedDocument d = tracker.find("doc-1").orElseThrow();
        assertThat(tracker.statusOf(d)).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(d.getMls()).isEqualTo(rejection);
        assertThat(d.getSentAt()).isEqualTo(START);
        assertThat(d.getMlsReceivedAt()).isEqualTo(START);
        assertThat(told).containsExactly("decided doc-1 REJECTED");
    }

    @Test
    void acknowledgedWithoutConfirmation() {
        tracker.track("doc-1", true);
        tracker.onMls(mls("doc-1", "mls-1", "AB"));
        assertThat(tracker.statusOf(tracker.find("doc-1").orElseThrow())).isEqualTo(DeliveryStatus.UNCONFIRMED);
    }

    @Test
    void onlyTheFirstMlsCounts() {
        tracker.track("doc-1", true);
        tracker.onMls(mls("doc-1", "mls-1", "AB"));
        assertThat(tracker.onMls(mls("doc-1", "mls-2", "RE"))).isEqualTo(MlsCorrelation.IGNORED);
        assertThat(tracker.find("doc-1").orElseThrow().getMls().getResponseCode()).isEqualTo("AB");
        assertThat(told).containsExactly("decided doc-1 UNCONFIRMED", "ignored mls-2");
    }

    @Test
    void sameMlsProcessedAgainTellsTheListenersAgain() {
        tracker.track("doc-1", false);
        listenerFails = true;
        assertThatThrownBy(() -> tracker.onMls(mls("doc-1", "mls-1", "RE"))).hasMessage("listener bug");
        listenerFails = false;
        assertThat(tracker.onMls(mls("doc-1", "mls-1", "RE"))).isEqualTo(MlsCorrelation.DECIDED);
        assertThat(told).containsExactly("decided doc-1 REJECTED");
    }

    @Test
    void mlsFasterThanTheRegistrationIsNotLost() {
        assertThat(tracker.onMls(mls("doc-1", "mls-1", "RE"))).isEqualTo(MlsCorrelation.UNKNOWN_DOCUMENT);
        assertThat(tracker.find("doc-1").orElseThrow().getSentAt()).isNull();

        TrackedDocument d = tracker.track("doc-1", false);
        assertThat(tracker.statusOf(d)).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(d.getSentAt()).isEqualTo(START);
        assertThat(told).containsExactly("unknown doc-1", "decided doc-1 REJECTED");
    }

    @Test
    void registeringAgainChangesNothing() {
        tracker.track("doc-1", true);
        clock.advance(Duration.ofHours(1));
        TrackedDocument again = tracker.track("doc-1", false);
        assertThat(again.getSentAt()).isEqualTo(START);
        assertThat(again.isPositiveMlsRequested()).isTrue();
    }

    @Test
    void concurrentRegistrationAndMlsBothSurvive() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 25; i++) {
                String id = "doc-" + i;
                List<Callable<Object>> tasks = List.of(
                        () -> store.register(id, true, null, START),
                        () -> store.recordMls(mls(id, "mls-a", "AP"), START),
                        () -> store.recordMls(mls(id, "mls-b", "RE"), START));
                for (Future<Object> f : pool.invokeAll(tasks)) {
                    f.get();
                }
                TrackedDocument d = store.find(id).orElseThrow();
                assertThat(d.getSentAt()).isEqualTo(START);
                assertThat(d.isPositiveMlsRequested()).isTrue();
                assertThat(d.getMls().getIssues()).hasSize(d.getMls().isRejected() ? 1 : 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oldRecordsArePurged() {
        tracker.track("old", false);
        tracker.onMls(mls("old", "mls-1", "RE"));
        clock.advance(Duration.ofDays(10));
        tracker.track("new", false);
        assertThat(store.purge(clock.instant().minus(Duration.ofDays(5)))).isEqualTo(1);
        assertThat(tracker.find("old")).isEmpty();
        assertThat(tracker.find("new")).isPresent();
    }

    @Test
    void anIdentifierIsNeeded() {
        assertThatThrownBy(() -> tracker.track(" ", false)).isInstanceOf(SthenoException.class);
        assertThatThrownBy(() -> tracker.onMls(mls(null, "mls-1", "AP"))).isInstanceOf(SthenoException.class);
    }

    @Test
    void responsesAdvanceTheInvoiceStatus() {
        BuiltEnvelope sent = TestDocs.namedInvoiceEnvelope("sent-1");
        TrackedDocument document = tracker.track(sent);
        assertThat(document.getInvoice()).isNotNull();
        assertThat(tracker.find("sent-1").orElseThrow().getInvoice()).isEqualTo(document.getInvoice());
        assertThat(tracker.responsesTo(document).getStatus()).isNull();

        assertThat(respond(sent, "r-1", InvoiceResponseBuilder.Status.AB)).isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(respond(sent, "r-2", InvoiceResponseBuilder.Status.UQ)).isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(respond(sent, "r-3", InvoiceResponseBuilder.Status.UQ)).isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(respond(sent, "r-4", InvoiceResponseBuilder.Status.AP)).isEqualTo(ResponseCorrelation.APPLIED);
        // Late or contradicting answers do not change the status
        assertThat(respond(sent, "r-5", InvoiceResponseBuilder.Status.IP)).isEqualTo(ResponseCorrelation.OUT_OF_SEQUENCE);
        assertThat(respond(sent, "r-6", InvoiceResponseBuilder.Status.RE)).isEqualTo(ResponseCorrelation.OUT_OF_SEQUENCE);
        assertThat(respond(sent, "r-7", InvoiceResponseBuilder.Status.PD, InvoiceResponseBuilder.Reason.PPD))
                .isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(respond(sent, "r-8", InvoiceResponseBuilder.Status.PD)).isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(respond(sent, "r-9", InvoiceResponseBuilder.Status.PD)).isEqualTo(ResponseCorrelation.OUT_OF_SEQUENCE);

        ResponseHistory history = tracker.responsesTo(document);
        assertThat(history.getStatus()).isEqualTo(InvoiceResponseBuilder.Status.PD);
        assertThat(history.getCurrent().getInstanceIdentifier()).isEqualTo("r-8");
        assertThat(history.getResponses()).hasSize(9);
        assertThat(history.getOutOfSequence()).extracting(TrackedResponse::getInstanceIdentifier).containsExactly("r-5", "r-6", "r-9");
        assertThat(told).contains("response sent-1 AB AB", "unexpected sent-1 IP AP", "response sent-1 PD PD");
        // The delivery status is a different question
        assertThat(tracker.statusOf(document)).isEqualTo(DeliveryStatus.NO_REJECTION);
    }

    @Test
    void rejectionByTheBuyerIsFinal() {
        BuiltEnvelope sent = TestDocs.namedInvoiceEnvelope("sent-1");
        tracker.track(sent);
        respond(sent, "r-1", InvoiceResponseBuilder.Status.RE, InvoiceResponseBuilder.Reason.PRI);
        assertThat(respond(sent, "r-2", InvoiceResponseBuilder.Status.PD)).isEqualTo(ResponseCorrelation.OUT_OF_SEQUENCE);
        assertThat(tracker.responsesTo(tracker.find("sent-1").orElseThrow()).getStatus()).isEqualTo(InvoiceResponseBuilder.Status.RE);
    }

    @Test
    void responseKeepsItsDetails() {
        BuiltEnvelope sent = TestDocs.namedInvoiceEnvelope("sent-1");
        tracker.track(sent);
        InboundDocument envelope = responseTo(sent, "r-1", reply(sent, InvoiceResponseBuilder.Status.CA)
                .note("Only partly")
                .effectiveDate(LocalDate.of(2026, 11, 30))
                .reason(InvoiceResponseBuilder.Reason.QTY, "Two items missing",
                        new InvoiceResponseBuilder.Condition("BT-129", "8"),
                        new InvoiceResponseBuilder.Condition("BT-112", null))
                .action(InvoiceResponseBuilder.Action.CNP, null));
        InvoiceResponse response = new InvoiceResponseReader().read(envelope);
        tracker.onInvoiceResponse(response, envelope);

        TrackedResponse kept = tracker.responsesTo(tracker.find("sent-1").orElseThrow()).getCurrent();
        assertThat(kept.getStatusEntries()).isEqualTo(response.getStatusEntries());
        assertThat(kept.getNote()).isEqualTo("Only partly");
        assertThat(kept.getResponseId()).isEqualTo(response.getId());
        assertThat(kept.getIssueDate()).isEqualTo(response.getIssueDate());
        assertThat(kept.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(kept.getReceivedAt()).isEqualTo(START);
    }

    @Test
    void sameResponseProcessedAgainIsKeptOnceAndToldAgain() {
        BuiltEnvelope sent = TestDocs.namedInvoiceEnvelope("sent-1");
        tracker.track(sent);
        listenerFails = true;
        assertThatThrownBy(() -> respond(sent, "r-1", InvoiceResponseBuilder.Status.AP)).hasMessage("listener bug");
        listenerFails = false;
        assertThat(respond(sent, "r-1", InvoiceResponseBuilder.Status.AP)).isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(tracker.responsesTo(tracker.find("sent-1").orElseThrow()).getResponses()).hasSize(1);
    }

    @Test
    void responseFasterThanTheRegistrationIsNotLost() {
        BuiltEnvelope sent = TestDocs.namedInvoiceEnvelope("sent-1");
        assertThat(respond(sent, "r-1", InvoiceResponseBuilder.Status.AP)).isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);

        TrackedDocument document = tracker.track(sent);
        assertThat(tracker.responsesTo(document).getStatus()).isEqualTo(InvoiceResponseBuilder.Status.AP);
        assertThat(told).containsExactly("unknown invoice " + document.getInvoice().getInvoiceNumber(), "response sent-1 AP AP");
    }

    @Test
    void mlsFirstThenRegistrationKeepsTheInvoice() {
        BuiltEnvelope sent = TestDocs.namedInvoiceEnvelope("sent-1");
        tracker.onMls(mls("sent-1", "mls-1", "AP"));
        tracker.track(sent);
        assertThat(respond(sent, "r-1", InvoiceResponseBuilder.Status.AP)).isEqualTo(ResponseCorrelation.APPLIED);
    }

    @Test
    void aResponseOnlyMatchesTheRightPartiesNumberTypeAndDate() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        InvoiceReference invoice = InvoiceReference.of(TestDocs.SENDER, TestDocs.RECEIVER, "INV-1", "380", date);
        tracker.track("sent-1", false, invoice);

        assertThat(correlate("r-1", TestDocs.RECEIVER, TestDocs.SENDER, "INV-2", "380", date)).isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);
        assertThat(correlate("r-2", TestDocs.RECEIVER, TestDocs.SENDER, "inv-1", "380", date)).isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);
        assertThat(correlate("r-3", TestDocs.RECEIVER, TestDocs.SENDER, "INV-1", "381", date)).isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);
        assertThat(correlate("r-4", TestDocs.RECEIVER, TestDocs.SENDER, "INV-1", "380", date.minusYears(1)))
                .isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);
        // Somebody else answering this invoice number
        assertThat(correlate("r-5", "0088:abc", TestDocs.SENDER, "INV-1", "380", date)).isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);
        assertThat(correlate("r-6", TestDocs.RECEIVER, "0088:abc", "INV-1", "380", date)).isEqualTo(ResponseCorrelation.UNKNOWN_INVOICE);
        assertThat(tracker.responsesTo(tracker.find("sent-1").orElseThrow()).getResponses()).isEmpty();
        // The date is optional in a response
        assertThat(correlate("r-7", TestDocs.RECEIVER, TestDocs.SENDER, "INV-1", "380", null)).isEqualTo(ResponseCorrelation.APPLIED);
        assertThat(tracker.responsesTo(tracker.find("sent-1").orElseThrow()).getResponses()).hasSize(1);
    }

    @Test
    void anInvoiceSentTwiceReportsTheLastSending() {
        InvoiceReference invoice = InvoiceReference.of(TestDocs.SENDER, TestDocs.RECEIVER, "INV-1", "380", null);
        tracker.track("first", false, invoice);
        clock.advance(Duration.ofHours(1));
        tracker.track("second", false, invoice);
        correlate("r-1", TestDocs.RECEIVER, TestDocs.SENDER, "INV-1", "380", null);
        assertThat(told).containsExactly("response second AP AP");
        assertThat(tracker.responsesTo(tracker.find("first").orElseThrow()).getStatus()).isEqualTo(InvoiceResponseBuilder.Status.AP);
    }

    @Test
    void oldResponsesArePurged() {
        InvoiceReference invoice = InvoiceReference.of(TestDocs.SENDER, TestDocs.RECEIVER, "INV-1", "380", null);
        tracker.track("old", false, invoice);
        correlate("r-1", TestDocs.RECEIVER, TestDocs.SENDER, "INV-1", "380", null);
        clock.advance(Duration.ofDays(10));
        assertThat(store.purge(clock.instant().minus(Duration.ofDays(5)))).isEqualTo(1);
        assertThat(store.findResponses(invoice)).isEmpty();
    }

    private ResponseCorrelation respond(BuiltEnvelope sent, String responseEnvelopeId, InvoiceResponseBuilder.Status status,
                                        InvoiceResponseBuilder.Reason... reasons) {
        clock.advance(Duration.ofSeconds(1));
        InvoiceResponseBuilder builder = reply(sent, status);
        for (InvoiceResponseBuilder.Reason reason : reasons) {
            builder.reason(reason, "because");
        }
        InboundDocument envelope = responseTo(sent, responseEnvelopeId, builder);
        return tracker.onInvoiceResponse(new InvoiceResponseReader().read(envelope), envelope);
    }

    private static InvoiceResponseBuilder reply(BuiltEnvelope sent, InvoiceResponseBuilder.Status status) {
        return InvoiceResponseBuilder.replyTo(new EnvelopeReader().read(sent.getXml(), null)).id("imr").status(status);
    }

    private static InboundDocument responseTo(BuiltEnvelope sent, String envelopeId, InvoiceResponseBuilder builder) {
        InboundDocument invoice = new EnvelopeReader().read(sent.getXml(), null);
        return InboundDocument.builder()
                .instanceIdentifier(envelopeId)
                .senderId(invoice.getReceiverId())
                .receiverId(invoice.getSenderId())
                .payload(InboundPayload.xml(builder.build()))
                .build();
    }

    private ResponseCorrelation correlate(String envelopeId, String from, String to, String number, String type, LocalDate date) {
        InboundDocument envelope = InboundDocument.builder().instanceIdentifier(envelopeId).senderId(from).receiverId(to).build();
        InvoiceResponse response = InvoiceResponse.builder()
                .status(InvoiceResponseBuilder.Status.AP)
                .statusEntries(List.of())
                .invoiceNumber(number)
                .invoiceTypeCode(type)
                .invoiceIssueDate(date)
                .build();
        return tracker.onInvoiceResponse(response, envelope);
    }

    private void tell(String what) {
        if (listenerFails) {
            throw new IllegalStateException("listener bug");
        }
        told.add(what);
    }

    private static ReceivedMls mls(String documentId, String mlsId, String code) {
        List<MlsIssue> issues = "RE".equals(code) ? List.of(MlsIssue.syntax(null, "broken")) : List.of();
        return new ReceivedMls(code, documentId, null, issues, mlsId, "iso6523-actorid-upis::0242:000111");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
