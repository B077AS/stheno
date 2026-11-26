package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.ap.AccessPointAcknowledger;
import io.github.b077as.stheno.ap.Acknowledgement;
import io.github.b077as.stheno.ap.ApCallResult;
import io.github.b077as.stheno.ap.PhossApForwarding;
import io.github.b077as.stheno.inbound.DeliveryMetadata;
import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.InboundProcessor;
import io.github.b077as.stheno.inbound.MessageKey;
import io.github.b077as.stheno.inbound.ReceivedRecord;
import io.github.b077as.stheno.inbound.Reception;
import io.github.b077as.stheno.spring.SthenoProperties.ForwardingMode;
import io.github.b077as.stheno.spring.SthenoProperties.Processing;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 * <li>no handler for the document type (unless configured to reject): {@code 503} (the Access Point retries);</li>
 * <li>duplicate of a handled document: {@code 200} again (the earlier receiver country in sync mode), the handler is not called;</li>
 * <li>duplicate of a document that is not decided yet: {@code 503}, so that it is delivered again should the first handling be lost;</li>
 * <li>background processing: {@code 200} at once, the handler runs afterwards;</li>
 * <li>inline processing: handler exception = {@code 503} (the Access Point retries); a rejection in sync mode without MLS verdict =
 * {@code 200 {"retry":"none"}} (the Access Point sends {@code RE} itself).</li>
 * </ul>
 * Acknowledgements (receiver country, MLS verdict) are sent after a short delay, so that the Access Point has processed the forwarding
 * answer first, and retried when they fail. A rejection that does not get through is logged as an error and published as
 * {@link SthenoEvents.RejectionNotDelivered}.
 * <p>
 * Background processing keeps the documents in memory only: documents left undecided longer than the processing timeout are reported
 * ({@link SthenoEvents.DocumentsUnfinished}), and have to be delivered again by the Access Point.
 */
@Slf4j
public class SthenoInboundService implements DisposableBean {

    private static final Duration FIRST_UNFINISHED_CHECK = Duration.ofSeconds(30);
    private static final Duration PURGE_INTERVAL = Duration.ofHours(1);

    private final InboundProcessor processor;
    private final AccessPointAcknowledger acknowledger;
    private final SthenoProperties properties;
    private final ApplicationEventPublisher events;
    private final Executor handlerExecutor;
    private final ExecutorService ownExecutor;
    private final ScheduledExecutorService scheduler;
    private final Set<MessageKey> pending = ConcurrentHashMap.newKeySet();
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
        Duration timeout = properties.getInbound().getProcessingTimeout();
        Duration first = timeout.compareTo(FIRST_UNFINISHED_CHECK) < 0 ? timeout : FIRST_UNFINISHED_CHECK;
        scheduler.scheduleWithFixedDelay(this::checkForUnfinished, first.toMillis(), timeout.toMillis(), TimeUnit.MILLISECONDS);
        // Often enough that no entry stays much longer than the retention
        long retention = Math.max(1, properties.getInbound().getDuplicateRetention().toMillis());
        scheduler.scheduleWithFixedDelay(this::purgeExpired, Math.min(retention, FIRST_UNFINISHED_CHECK.toMillis()),
                Math.min(retention, PURGE_INTERVAL.toMillis()), TimeUnit.MILLISECONDS);
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
            log.error("Did not take over a forwarded document: {}", ex.getMessage());
            return new InboundResponse(503, errorJson("temporarily unavailable"));
        }

        if (reception.isDuplicate()) {
            if (reception.isInProgress()) {
                return new InboundResponse(503, errorJson("the document is not processed yet"));
            }
            return answerFor(reception.getPrevious().getOutcome());
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
                scheduleAcknowledgement(result, countrySent, 1, properties.getAccessPoint().getAcknowledgeDelay(), null);
            }
            return answerFor(outcome);
        }

        MessageKey key = MessageKey.of(reception.getDocument());
        pending.add(key);
        byte[] raw = envelope;
        try {
            handlerExecutor.execute(() -> runInBackground(reception, raw, delivery, 1));
        } catch (RejectedExecutionException ex) {
            pending.remove(key);
            processor.abandon(reception.getDocument());
            log.error("No thread could take {}: {}", key, ex.getMessage());
            return new InboundResponse(503, errorJson("temporarily unavailable"));
        }
        return new InboundResponse(200, mode == ForwardingMode.SYNC ? PhossApForwarding.syncReceived(null) : receivedJson());
    }

    /** Number of documents received but not yet handled (background processing). */
    public int getPendingCount() {
        return pending.size();
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
        if (!pending.isEmpty()) {
            log.error("Stopping with {} document(s) taken over from the Access Point but not handled. Deliver them again there: {}",
                    pending.size(), pending);
        }
    }

    private void runInBackground(Reception reception, byte[] raw, DeliveryMetadata delivery, int attempt) {
        MessageKey key = MessageKey.of(reception.getDocument());
        try {
            HandlingResult result = processor.handle(reception.getDocument());
            if (result.getStatus() == HandlingResult.Status.FAILED) {
                int max = Math.max(1, properties.getInbound().getHandlerRetryAttempts());
                boolean givingUp = attempt >= max;
                events.publishEvent(new SthenoEvents.HandlerFailed(result, attempt, givingUp));
                if (givingUp) {
                    log.error("Giving up on {} after {} attempt(s); the Access Point gets no verdict and will fall back to its own MLS."
                            + " Deliver it again there once the problem is fixed", key, attempt);
                    pending.remove(key);
                } else {
                    scheduleRetry(raw, delivery, key, attempt);
                }
                return;
            }
            pending.remove(key);
            scheduleAcknowledgement(result, false, 1, properties.getAccessPoint().getAcknowledgeDelay(), null);
        } catch (RuntimeException ex) {
            pending.remove(key);
            log.error("Unexpected error while handling {}", key, ex);
        } catch (Error error) {
            pending.remove(key);
            log.error("Error while handling {}", key, error);
            throw error;
        }
    }

    private void scheduleRetry(byte[] raw, DeliveryMetadata delivery, MessageKey key, int failedAttempt) {
        // The failed attempt released the message. Register it again right away: if the application stops while waiting for the
        // retry, the store still knows it as unfinished
        Reception again = processor.receive(raw, delivery);
        if (again.isDuplicate()) {
            // Delivered again in the meantime: that delivery takes care of it
            pending.remove(key);
            return;
        }
        Duration delay = properties.getInbound().getHandlerRetryDelay().multipliedBy(1L << (failedAttempt - 1));
        Runnable retry = () -> {
            try {
                handlerExecutor.execute(() -> runInBackground(again, raw, delivery, failedAttempt + 1));
            } catch (RejectedExecutionException ex) {
                pending.remove(key);
                processor.abandon(again.getDocument());
                log.error("No thread could retry {}; deliver it again at the Access Point: {}", key, ex.getMessage());
            }
        };
        try {
            scheduler.schedule(retry, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            log.debug("Stopping: {} stays pending and is listed by destroy()", key);
        }
    }

    private void scheduleAcknowledgement(HandlingResult result, boolean countryAlreadySent, int attempt, Duration delay,
                                         Acknowledgement previous) {
        if (!result.isAcknowledgeable()) {
            return;
        }
        if (acknowledger == null) {
            if (isRejection(result)) {
                rejectionNotDelivered(result, ApCallResult.SKIPPED);
            }
            return;
        }
        try {
            scheduler.schedule(() -> handlerExecutor.execute(() -> acknowledge(result, countryAlreadySent, attempt, previous)),
                    Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            log.warn("Stopping: the Access Point is not told about {}", result.getDocument().getInstanceIdentifier());
            if (isRejection(result)) {
                rejectionNotDelivered(result, ApCallResult.SKIPPED);
            }
        }
    }

    private void acknowledge(HandlingResult result, boolean countryAlreadySent, int attempt, Acknowledgement previous) {
        Acknowledgement ack = acknowledger.acknowledge(result, countryAlreadySent, previous);
        int max = Math.max(1, properties.getAccessPoint().getRetryAttempts());
        boolean givingUp = ack.isFailed() && attempt >= max;
        events.publishEvent(new SthenoEvents.AccessPointAcknowledged(result.getDocument(), ack, attempt, givingUp));
        boolean refused = ack.isRejectionNotDelivered() && (previous == null || !previous.isRejectionNotDelivered());
        boolean neverArrived = givingUp && ack.getMlsVerdict() == ApCallResult.FAILED && isRejection(result);
        if (refused || neverArrived) {
            rejectionNotDelivered(result, ack.getMlsVerdict());
        }
        if (!ack.isFailed()) {
            consecutiveAckFailures.set(0);
            return;
        }
        consecutiveAckFailures.incrementAndGet();
        lastAckFailure.set(Instant.now());
        if (!givingUp) {
            Duration delay = properties.getAccessPoint().getRetryDelay().multipliedBy(1L << (attempt - 1));
            scheduleAcknowledgement(result, countryAlreadySent, attempt + 1, delay, ack);
        } else {
            log.error("Giving up acknowledging {} to the Access Point after {} attempt(s)", result.getDocument().getInstanceIdentifier(), attempt);
        }
    }

    private void rejectionNotDelivered(HandlingResult result, ApCallResult mlsVerdict) {
        log.error("The rejection of {} did not reach the Access Point as MLS verdict ({}): the sender may believe the document was"
                        + " delivered. Tell them another way",
                result.getDocument().getInstanceIdentifier(), mlsVerdict);
        events.publishEvent(new SthenoEvents.RejectionNotDelivered(result.getDocument(), result.getOutcome(), mlsVerdict));
    }

    private void checkForUnfinished() {
        // Never throws: an exception would end the schedule
        try {
            handlerExecutor.execute(this::reportUnfinished);
        } catch (RuntimeException ex) {
            log.warn("Could not look for unfinished documents: {}", ex.getMessage());
        }
    }

    private void purgeExpired() {
        // Never throws: an exception would end the schedule
        try {
            handlerExecutor.execute(() -> {
                try {
                    processor.purgeExpired();
                } catch (RuntimeException ex) {
                    log.warn("Could not purge old received message records: {}", ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Could not purge old received message records: {}", ex.getMessage());
        }
    }

    private void reportUnfinished() {
        try {
            List<ReceivedRecord> unfinished = processor.findUnfinished(properties.getInbound().getProcessingTimeout()).stream()
                    .filter(r -> !pending.contains(r.getKey()))
                    .toList();
            if (!unfinished.isEmpty()) {
                log.warn("{} document(s) have no decision after {} (handling lost, or a very slow handler). Deliver lost ones again"
                                + " at the Access Point: {}", unfinished.size(), properties.getInbound().getProcessingTimeout(),
                        unfinished.stream().map(ReceivedRecord::getKey).toList());
                events.publishEvent(new SthenoEvents.DocumentsUnfinished(unfinished));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not look for unfinished documents: {}", ex.getMessage());
        }
    }

    private InboundResponse answerFor(InboundOutcome outcome) {
        if (properties.getInbound().getForwardingMode() == ForwardingMode.ASYNC) {
            return new InboundResponse(200, receivedJson());
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

    private static boolean isRejection(HandlingResult result) {
        return result.getOutcome().getStatus() == InboundOutcome.Status.REJECTED;
    }

    private static String receivedJson() {
        return "{\"status\":\"received\"}";
    }

    private static String errorJson(String message) {
        return "{\"status\":\"rejected\",\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n\\t]", " ") + "\"}";
    }
}
