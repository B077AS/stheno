package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.ap.AccessPointAcknowledger;
import io.github.b077as.stheno.ap.Acknowledgement;
import io.github.b077as.stheno.ap.PhossApForwarding;
import io.github.b077as.stheno.inbound.DeliveryMetadata;
import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.InboundProcessor;
import io.github.b077as.stheno.inbound.Reception;
import io.github.b077as.stheno.spring.SthenoProperties.ForwardingMode;
import io.github.b077as.stheno.spring.SthenoProperties.Processing;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns one forwarding call of the Access Point into an HTTP answer, runs the handlers and tells the Access Point the outcome.
 * <p>
 * Answer rules (phoss-ap):
 * <ul>
 * <li>invalid envelope: sync forwarding {@code 200 {"retry":"none"}}, async {@code 400} (the Access Point gives up after its retries);</li>
 * <li>duplicate: {@code 200} again (the earlier receiver country in sync mode), the handler is not called;</li>
 * <li>background processing: {@code 200} at once, the handler runs afterwards;</li>
 * <li>inline processing: handler exception = {@code 503} (the Access Point retries); a rejection in sync mode without MLS verdict =
 * {@code 200 {"retry":"none"}} (the Access Point sends {@code RE} itself).</li>
 * </ul>
 * Acknowledgements (receiver country, MLS verdict) are sent after a short delay, so that the Access Point has processed the forwarding
 * answer first, and retried when they fail.
 */
@Slf4j
public class SthenoInboundService implements DisposableBean {

    private final InboundProcessor processor;
    private final AccessPointAcknowledger acknowledger;
    private final SthenoProperties properties;
    private final ApplicationEventPublisher events;
    private final Executor handlerExecutor;
    private final ExecutorService ownExecutor;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger consecutiveAckFailures = new AtomicInteger();
    private final AtomicReference<Instant> lastAckFailure = new AtomicReference<>();

    /**
     * @param acknowledger    {@code null} when no Access Point API is configured
     * @param handlerExecutor {@code null} for virtual threads
     */
    public SthenoInboundService(InboundProcessor processor, AccessPointAcknowledger acknowledger, SthenoProperties properties,
                                ApplicationEventPublisher events, Executor handlerExecutor) {
        this.processor = processor;
        this.acknowledger = acknowledger;
        this.properties = properties;
        this.events = events;
        this.ownExecutor = handlerExecutor == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        this.handlerExecutor = handlerExecutor != null ? handlerExecutor : ownExecutor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stheno-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Handles one forwarding call. */
    public InboundResponse accept(byte[] envelope, Map<String, String> headers) {
        ForwardingMode mode = properties.getInbound().getForwardingMode();
        DeliveryMetadata delivery = PhossApForwarding.metadataFromHeaders(headers);
        Reception reception;
        try {
            reception = processor.receive(envelope, delivery);
        } catch (SthenoException ex) {
            if (ex.getCode() == SthenoException.ErrorCode.INVALID_ENVELOPE) {
                log.warn("Refused a forwarded document: {}", ex.getMessage());
                return mode == ForwardingMode.SYNC
                        ? new InboundResponse(200, PhossApForwarding.syncRejectedNoRetry(ex.getMessage()))
                        : new InboundResponse(400, errorJson(ex.getMessage()));
            }
            log.error("Could not register a forwarded document: {}", ex.getMessage());
            return new InboundResponse(503, errorJson("temporarily unavailable"));
        }

        if (reception.isDuplicate()) {
            InboundOutcome previous = reception.getPrevious().getOutcome();
            return answerFor(previous);
        }

        if (properties.getInbound().getProcessing() == Processing.INLINE) {
            HandlingResult result = processor.handle(reception.getDocument());
            if (result.getStatus() == HandlingResult.Status.FAILED) {
                events.publishEvent(new SthenoEvents.HandlerFailed(result, 1, false));
                return new InboundResponse(503, errorJson("the receiving application could not process the document now"));
            }
            InboundOutcome outcome = result.getOutcome();
            if (!rejectsWithRetryNone(outcome)) {
                boolean countrySent = mode == ForwardingMode.SYNC && outcome.getReceiverCountry() != null
                        && outcome.getStatus() != InboundOutcome.Status.REJECTED;
                scheduleAcknowledgement(result, countrySent, 1, properties.getAccessPoint().getAcknowledgeDelay());
            }
            return answerFor(outcome);
        }

        pending.incrementAndGet();
        byte[] raw = envelope;
        handlerExecutor.execute(() -> runInBackground(reception, raw, delivery, 1));
        return new InboundResponse(200, mode == ForwardingMode.SYNC ? PhossApForwarding.syncReceived(null) : receivedJson());
    }

    /** Number of documents received but not yet handled (background processing). */
    public int getPendingCount() {
        return pending.get();
    }

    public int getConsecutiveAcknowledgementFailures() {
        return consecutiveAckFailures.get();
    }

    public Instant getLastAcknowledgementFailure() {
        return lastAckFailure.get();
    }

    public boolean isAcknowledging() {
        return acknowledger != null;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        if (ownExecutor != null) {
            ownExecutor.shutdown();
            try {
                ownExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runInBackground(Reception reception, byte[] raw, DeliveryMetadata delivery, int attempt) {
        try {
            HandlingResult result = processor.handle(reception.getDocument());
            if (result.getStatus() == HandlingResult.Status.FAILED) {
                int max = Math.max(1, properties.getInbound().getHandlerRetryAttempts());
                boolean givingUp = attempt >= max;
                events.publishEvent(new SthenoEvents.HandlerFailed(result, attempt, givingUp));
                if (givingUp) {
                    log.error("Giving up on {} after {} attempt(s); the Access Point gets no verdict and will fall back to its own MLS",
                            reception.getDocument().getInstanceIdentifier(), attempt);
                    pending.decrementAndGet();
                } else {
                    Duration delay = properties.getInbound().getHandlerRetryDelay().multipliedBy(1L << (attempt - 1));
                    scheduler.schedule(() -> handlerExecutor.execute(() -> retryInBackground(raw, delivery, attempt + 1)),
                            delay.toMillis(), TimeUnit.MILLISECONDS);
                }
                return;
            }
            pending.decrementAndGet();
            scheduleAcknowledgement(result, false, 1, properties.getAccessPoint().getAcknowledgeDelay());
        } catch (RuntimeException ex) {
            pending.decrementAndGet();
            log.error("Unexpected error while handling {}", reception.getDocument().getInstanceIdentifier(), ex);
        }
    }

    private void retryInBackground(byte[] raw, DeliveryMetadata delivery, int attempt) {
        // The failed attempt released the message: register it again (it may have been delivered again in the meantime)
        Reception again;
        try {
            again = processor.receive(raw, delivery);
        } catch (RuntimeException ex) {
            pending.decrementAndGet();
            log.error("Retry of a failed document could not register it: {}", ex.getMessage());
            return;
        }
        if (again.isDuplicate()) {
            pending.decrementAndGet();
            return;
        }
        runInBackground(again, raw, delivery, attempt);
    }

    private void scheduleAcknowledgement(HandlingResult result, boolean countryAlreadySent, int attempt, Duration delay) {
        if (acknowledger == null || !result.isAcknowledgeable()) {
            return;
        }
        scheduler.schedule(() -> handlerExecutor.execute(() -> acknowledge(result, countryAlreadySent, attempt)),
                Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void acknowledge(HandlingResult result, boolean countryAlreadySent, int attempt) {
        Acknowledgement ack = acknowledger.acknowledge(result, countryAlreadySent);
        int max = Math.max(1, properties.getAccessPoint().getRetryAttempts());
        boolean givingUp = ack.isFailed() && attempt >= max;
        events.publishEvent(new SthenoEvents.AccessPointAcknowledged(result.getDocument(), ack, attempt, givingUp));
        if (!ack.isFailed()) {
            consecutiveAckFailures.set(0);
            return;
        }
        consecutiveAckFailures.incrementAndGet();
        lastAckFailure.set(Instant.now());
        if (!givingUp) {
            Duration delay = properties.getAccessPoint().getRetryDelay().multipliedBy(1L << (attempt - 1));
            scheduleAcknowledgement(result, countryAlreadySent, attempt + 1, delay);
        } else {
            log.error("Giving up acknowledging {} to the Access Point after {} attempt(s)", result.getDocument().getInstanceIdentifier(), attempt);
        }
    }

    private InboundResponse answerFor(InboundOutcome outcome) {
        if (properties.getInbound().getForwardingMode() == ForwardingMode.ASYNC) {
            return new InboundResponse(200, receivedJson());
        }
        if (outcome == null) {
            return new InboundResponse(200, PhossApForwarding.syncReceived(null));
        }
        if (rejectsWithRetryNone(outcome)) {
            String text = outcome.getText() != null ? outcome.getText() : outcome.getIssues().get(0).getDescription();
            return new InboundResponse(200, PhossApForwarding.syncRejectedNoRetry(text));
        }
        String country = outcome.getStatus() == InboundOutcome.Status.REJECTED ? null : outcome.getReceiverCountry();
        return new InboundResponse(200, PhossApForwarding.syncReceived(country));
    }

    /**
     * In sync forwarding without the MLS verdict switch, a rejection can only be expressed in the forwarding answer
     * ({@code retry: none}); phoss-ap then sends the {@code RE} itself.
     */
    private boolean rejectsWithRetryNone(InboundOutcome outcome) {
        return properties.getInbound().getForwardingMode() == ForwardingMode.SYNC
                && outcome != null && outcome.getStatus() == InboundOutcome.Status.REJECTED
                && (acknowledger == null || !acknowledger.isSendMlsVerdict());
    }

    private static String receivedJson() {
        return "{\"status\":\"received\"}";
    }

    private static String errorJson(String message) {
        return "{\"status\":\"rejected\",\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n\\t]", " ") + "\"}";
    }
}
