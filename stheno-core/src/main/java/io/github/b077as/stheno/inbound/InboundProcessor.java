package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.envelope.EnvelopeReader;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The inbound flow, independent of any transport: read the envelope, detect duplicates, hand the document to the application.
 * <p>
 * It is split in two steps because an Access Point waits for the answer while it is still in the middle of the Peppol exchange (phoss-ap
 * forwards inside the AS4 request): {@link #receive} is fast and can be answered immediately, {@link #handle} runs the application code
 * and may be executed later on another thread. {@link #process} does both in one go.
 * <p>
 * Thread-safe.
 */
@Slf4j
public final class InboundProcessor {

    private final EnvelopeReader reader = new EnvelopeReader();
    private final MlsReader mlsReader = new MlsReader();
    private final ReceivedMessageStore store;
    private final Supplier<InboundRouter> routerSupplier;
    private volatile InboundRouter router;
    private final List<InboundListener> listeners;
    private final UnhandledDocumentPolicy unhandled;

    public InboundProcessor(ReceivedMessageStore store, InboundRouter router, List<InboundListener> listeners) {
        this(store, router, listeners, UnhandledDocumentPolicy.RETRY);
    }

    public InboundProcessor(ReceivedMessageStore store, InboundRouter router, List<InboundListener> listeners,
                            UnhandledDocumentPolicy unhandled) {
        this(store, () -> router, listeners, unhandled);
        Objects.requireNonNull(router, "router");
    }

    /** With a router that is built on first use (e.g. after all Spring beans with handler methods exist). */
    public InboundProcessor(ReceivedMessageStore store, Supplier<InboundRouter> router, List<InboundListener> listeners) {
        this(store, router, listeners, UnhandledDocumentPolicy.RETRY);
    }

    public InboundProcessor(ReceivedMessageStore store, Supplier<InboundRouter> router, List<InboundListener> listeners,
                            UnhandledDocumentPolicy unhandled) {
        this.store = Objects.requireNonNull(store, "store");
        this.routerSupplier = Objects.requireNonNull(router, "router");
        this.listeners = List.copyOf(listeners);
        this.unhandled = Objects.requireNonNull(unhandled, "unhandled");
    }

    /** The router (built now if it was supplied lazily). */
    public InboundRouter getRouter() {
        InboundRouter r = router;
        if (r == null) {
            synchronized (this) {
                r = router;
                if (r == null) {
                    r = Objects.requireNonNull(routerSupplier.get(), "router");
                    router = r;
                }
            }
        }
        return r;
    }

    /**
     * Step 1: reads the envelope and registers the message.
     *
     * @throws io.github.b077as.stheno.SthenoException {@code INVALID_ENVELOPE} if the bytes are no valid Peppol envelope;
     *         {@code NO_HANDLER} if no handler takes the new business document and the policy is
     *         {@link UnhandledDocumentPolicy#RETRY} (nothing is registered)
     */
    public Reception receive(byte[] envelope, DeliveryMetadata delivery) {
        InboundDocument document = reader.read(envelope, delivery);
        MessageKey key = MessageKey.of(document);
        Optional<ReceivedRecord> previous = store.register(key);
        if (previous.isPresent()) {
            Reception reception = new Reception(document, true, previous.get());
            log.info("Duplicate delivery of {} ignored (first received {})", key, previous.get().getReceivedAt());
            notifyListeners(l -> l.onDuplicate(reception));
            return reception;
        }
        if (unhandled == UnhandledDocumentPolicy.RETRY && !document.isMessageLevelStatus()) {
            requireHandler(document, key);
        }
        log.info("Received {} ({})", key, document.getDocumentTypeId());
        notifyListeners(l -> l.onReceived(document));
        return new Reception(document, false, null);
    }

    /**
     * Gives up a document that was received but will not be handled now (e.g. no thread could take it): it is forgotten, so that a new
     * delivery of it is handled.
     */
    public void abandon(InboundDocument document) {
        store.release(MessageKey.of(document));
    }

    /** The store's unfinished messages, see {@link ReceivedMessageStore#findUnfinished}. */
    public List<ReceivedRecord> findUnfinished(Duration olderThan) {
        return store.findUnfinished(olderThan);
    }

    /**
     * Step 2: hands a newly received document to the application and records the decision. Must only be called for a {@link Reception}
     * that is not a duplicate.
     */
    public HandlingResult handle(InboundDocument document) {
        MessageKey key = MessageKey.of(document);
        try {
            HandlingResult result = document.isMessageLevelStatus() ? handleMls(document) : handleBusinessDocument(document);
            store.complete(key, result.getOutcome());
            notifyListeners(l -> l.onHandled(result));
            return result;
        } catch (Exception ex) {
            log.warn("Handler failed for {}: {}; the message is released for a new delivery", key, ex.toString());
            store.release(key);
            HandlingResult result = new HandlingResult(HandlingResult.Status.FAILED, document, null, null, ex);
            notifyListeners(l -> l.onHandlerFailed(result));
            return result;
        } catch (Error error) {
            // Not turned into a result, but the message must not stay registered as "being handled"
            store.release(key);
            throw error;
        }
    }

    /** Both steps in one call. A duplicate is not handed to the application again. */
    public HandlingResult process(byte[] envelope, DeliveryMetadata delivery) {
        Reception reception = receive(envelope, delivery);
        if (reception.isDuplicate()) {
            return new HandlingResult(HandlingResult.Status.DUPLICATE, reception.getDocument(),
                    reception.getPrevious().getOutcome(), null, null);
        }
        return handle(reception.getDocument());
    }

    private HandlingResult handleBusinessDocument(InboundDocument document) throws Exception {
        InboundHandler handler = getRouter().findHandler(document);
        InboundOutcome outcome;
        if (handler == null && unhandled == UnhandledDocumentPolicy.RETRY) {
            throw new IllegalStateException("No handler for document type " + document.getDocumentTypeId());
        }
        if (handler == null) {
            log.warn("No handler for document type {}: rejecting it", document.getDocumentTypeId());
            outcome = InboundOutcome.rejected(MlsIssue.deliveryFailure(
                    "The receiving application does not process documents of type " + document.getDocumentTypeId()));
        } else {
            outcome = handler.handle(document);
            if (outcome == null) {
                throw new IllegalStateException("The handler for " + document.getDocumentTypeId() + " returned no outcome");
            }
        }
        return new HandlingResult(HandlingResult.Status.HANDLED, document, outcome, null, null);
    }

    /** Releases the registration again if no handler takes the document (or looking for one fails). */
    private void requireHandler(InboundDocument document, MessageKey key) {
        boolean found = false;
        try {
            found = getRouter().findHandler(document) != null;
        } finally {
            if (!found) {
                store.release(key);
            }
        }
        if (!found) {
            throw new SthenoException(SthenoException.ErrorCode.NO_HANDLER, "No handler for document type "
                    + document.getDocumentTypeId() + ": " + key + " is not taken over, the Access Point has to deliver it again");
        }
    }

    private HandlingResult handleMls(InboundDocument document) throws Exception {
        ReceivedMls mls = mlsReader.read(document);
        MlsHandler handler = getRouter().getMlsHandler();
        if (handler == null) {
            log.info("MLS {} for {} received, no MLS handler registered", mls.getResponseCode(), mls.getReferencedInstanceIdentifier());
        } else {
            handler.handle(mls, document);
        }
        // An MLS is never answered with an MLS: the outcome only records that it was taken over
        return new HandlingResult(HandlingResult.Status.HANDLED, document, InboundOutcome.accepted(null), mls, null);
    }

    private void notifyListeners(Consumer<InboundListener> call) {
        for (InboundListener l : listeners) {
            try {
                call.accept(l);
            } catch (RuntimeException ex) {
                log.warn("Inbound listener {} failed: {}", l, ex.toString());
            }
        }
    }
}
