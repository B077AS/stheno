package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.ReceivedMls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
                        () -> store.register(id, true, START),
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
