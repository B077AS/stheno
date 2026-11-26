package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;
import lombok.Value;
import lombok.With;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DeliveryStore} in memory, for tests and trying things out: everything is lost on restart, so an application that has to know
 * what happened to its documents wants {@link JdbcDeliveryStore}.
 */
public final class InMemoryDeliveryStore implements DeliveryStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<ResponseKey, Response> responses = new ConcurrentHashMap<>();

    @Override
    public TrackedDocument register(String instanceIdentifier, boolean positiveMlsRequested, InvoiceReference invoice, Instant now) {
        return entries.compute(instanceIdentifier, (id, old) -> {
            if (old == null) {
                return new Entry(new TrackedDocument(id, now, positiveMlsRequested, null, null, invoice), now, false);
            }
            TrackedDocument d = old.getDocument();
            return d.getSentAt() != null ? old
                    : old.withDocument(new TrackedDocument(id, now, positiveMlsRequested, d.getMls(), d.getMlsReceivedAt(), invoice));
        }).getDocument();
    }

    @Override
    public TrackedDocument recordMls(ReceivedMls mls, Instant now) {
        return entries.compute(mls.getReferencedInstanceIdentifier(), (id, old) -> {
            if (old == null) {
                return new Entry(new TrackedDocument(id, null, false, mls, now, null), now, false);
            }
            TrackedDocument d = old.getDocument();
            return d.getMls() != null ? old : old.withDocument(
                    new TrackedDocument(id, d.getSentAt(), d.isPositiveMlsRequested(), mls, now, d.getInvoice()));
        }).getDocument();
    }

    @Override
    public Optional<TrackedDocument> find(String instanceIdentifier) {
        return Optional.ofNullable(entries.get(instanceIdentifier)).map(Entry::getDocument);
    }

    @Override
    public TrackedResponse recordResponse(InvoiceReference invoice, TrackedResponse response) {
        ResponseKey key = new ResponseKey(invoice.getInvoiceSenderId(), response.getInstanceIdentifier());
        return responses.computeIfAbsent(key, k -> new Response(invoice, response)).getResponse();
    }

    @Override
    public List<TrackedResponse> findResponses(InvoiceReference invoice) {
        return responses.values().stream()
                .filter(r -> sameInvoice(r.getInvoice(), invoice))
                .map(Response::getResponse)
                .sorted(Comparator.comparing(TrackedResponse::getReceivedAt).thenComparing(TrackedResponse::getInstanceIdentifier))
                .toList();
    }

    @Override
    public List<TrackedDocument> findByInvoice(InvoiceReference invoice) {
        return entries.values().stream()
                .map(Entry::getDocument)
                .filter(d -> d.getSentAt() != null && d.getInvoice() != null && sameInvoice(d.getInvoice(), invoice))
                .sorted(Comparator.comparing(TrackedDocument::getSentAt).reversed()
                        .thenComparing(TrackedDocument::getInstanceIdentifier))
                .toList();
    }

    @Override
    public List<TrackedDocument> findOverdue(Instant sentBefore, boolean onlyUnreported, int max) {
        return entries.values().stream()
                .filter(e -> !onlyUnreported || !e.isOverdueReported())
                .map(Entry::getDocument)
                .filter(d -> d.getMls() == null && d.isPositiveMlsRequested() && d.getSentAt() != null
                        && d.getSentAt().isBefore(sentBefore))
                .sorted(Comparator.comparing(TrackedDocument::getSentAt))
                .limit(max)
                .toList();
    }

    @Override
    public void markOverdueReported(String instanceIdentifier, Instant now) {
        entries.computeIfPresent(instanceIdentifier, (id, old) -> old.withOverdueReported(true));
    }

    @Override
    public int purge(Instant createdBefore) {
        int before = entries.size();
        entries.values().removeIf(e -> e.getCreatedAt().isBefore(createdBefore));
        responses.values().removeIf(r -> r.getResponse().getReceivedAt().isBefore(createdBefore));
        return before - entries.size();
    }

    private static boolean sameInvoice(InvoiceReference a, InvoiceReference b) {
        return a.getInvoiceSenderId().equals(b.getInvoiceSenderId())
                && a.getInvoiceReceiverId().equals(b.getInvoiceReceiverId())
                && a.getInvoiceNumber().equals(b.getInvoiceNumber())
                && a.getTypeCode().equals(b.getTypeCode())
                && DeliveryStore.compatibleDates(a.getIssueDate(), b.getIssueDate());
    }

    @Value
    @With
    private static class Entry {
        TrackedDocument document;
        Instant createdAt;
        boolean overdueReported;
    }

    @Value
    private static class ResponseKey {
        String invoiceSenderId;
        String instanceIdentifier;
    }

    @Value
    private static class Response {
        InvoiceReference invoice;
        TrackedResponse response;
    }
}
