package io.github.b077as.stheno.test;

import io.github.b077as.stheno.delivery.DeliveryStatus;
import io.github.b077as.stheno.delivery.DeliveryTracker;
import io.github.b077as.stheno.delivery.TrackedDocument;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import io.github.b077as.stheno.test.app.TestApplication;
import io.github.b077as.stheno.test.app.TestApplication.Handlers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery tracking: the application registers what it sent, the Access Point forwards the MLS answers.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stheno.delivery.enabled=true")
class DeliveryTrackingEndToEndTest {

    private static final FakeAccessPoint AP = FakeAccessPoint.start();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    DeliveryTracker tracker;

    @DynamicPropertySource
    static void stheno(DynamicPropertyRegistry registry) {
        AP.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @AfterAll
    static void stop() {
        AP.close();
    }

    @BeforeEach
    void setUp() {
        AP.reset();
        Handlers.reset();
        AP.forwardTo(URI.create("http://localhost:" + port + "/peppol/inbound"));
    }

    @Test
    void rejectionOfASentDocumentIsFollowedAndALaterAnswerIgnored() {
        String id = UUID.randomUUID().toString();
        tracker.track(id, false);
        assertThat(status(id)).isEqualTo(DeliveryStatus.NO_REJECTION);

        assertThat(AP.deliverMls(id, "RE", MlsIssue.businessRule("/Invoice/cbc:ID", "BR-02 violated")).getStatus()).isEqualTo(200);
        waitUntil(() -> Handlers.EVENTS.stream().anyMatch(e -> e instanceof SthenoEvents.DeliveryDecided d
                && d.getDocument().getInstanceIdentifier().equals(id)));
        assertThat(status(id)).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(tracker.find(id).orElseThrow().getMls().getIssues()).singleElement()
                .extracting(MlsIssue::getDescription).isEqualTo("BR-02 violated");

        AP.deliverMls(id, "AP");
        waitUntil(() -> Handlers.EVENTS.stream().anyMatch(e -> e instanceof SthenoEvents.MlsIgnored i
                && i.getDocument().getInstanceIdentifier().equals(id)));
        assertThat(status(id)).isEqualTo(DeliveryStatus.REJECTED);
    }

    @Test
    void mlsForADocumentNotYetRegisteredIsKept() {
        String id = UUID.randomUUID().toString();
        AP.deliverMls(id, "AP");
        waitUntil(() -> Handlers.EVENTS.stream().anyMatch(e -> e instanceof SthenoEvents.MlsForUnknownDocument u
                && u.getMls().getReferencedInstanceIdentifier().equals(id)));

        TrackedDocument tracked = tracker.track(id, true);
        assertThat(tracker.statusOf(tracked)).isEqualTo(DeliveryStatus.CONFIRMED);
    }

    private DeliveryStatus status(String id) {
        return tracker.statusOf(tracker.find(id).orElseThrow());
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached in time").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
