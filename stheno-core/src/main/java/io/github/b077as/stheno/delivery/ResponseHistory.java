package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.reply.InvoiceResponseBuilder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * All Invoice Responses received about one invoice, and the status that counts.
 * <p>
 * An invoice can get several responses. They advance in the order {@code AB, IP, UQ, CA, RE, AP, PD}; not all of them need to occur,
 * and {@code RE} and a full {@code PD} are final (BIS Invoice Response rules OP-BR111-R004 and R012). Taken in the order of arrival,
 * a response becomes the current one if the current one is not final and the new status is not an earlier one. Every other response is
 * out of sequence: it is kept, but it does not change the status, and the BIS leaves it to the trading parties to clear it up.
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ResponseHistory {

    /** Every response, in the order of arrival. */
    List<TrackedResponse> responses;
    /** Those that did not advance the status, in the order of arrival. */
    List<TrackedResponse> outOfSequence;
    /** The response that gives the status, or {@code null} if there is none. */
    TrackedResponse current;

    public static ResponseHistory of(List<TrackedResponse> inOrderOfArrival) {
        List<TrackedResponse> outOfSequence = new ArrayList<>();
        TrackedResponse current = null;
        for (TrackedResponse next : inOrderOfArrival) {
            if (current == null || !current.isFinal() && next.getStatus().compareTo(current.getStatus()) >= 0) {
                current = next;
            } else {
                outOfSequence.add(next);
            }
        }
        return new ResponseHistory(List.copyOf(inOrderOfArrival), List.copyOf(outOfSequence), current);
    }

    /** The status of the invoice, or {@code null} if no response arrived (which says nothing about the invoice). */
    public InvoiceResponseBuilder.Status getStatus() {
        return current == null ? null : current.getStatus();
    }
}
