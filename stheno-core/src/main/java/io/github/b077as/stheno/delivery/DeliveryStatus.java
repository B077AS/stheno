package io.github.b077as.stheno.delivery;

/**
 * What is known about the delivery of a sent document from its Message Level Status.
 * <p>
 * By default Peppol only reports failures (MLS usage {@code FAILURE_ONLY}), so hearing nothing is the normal sign that all went well:
 * {@link #NO_REJECTION}. Only when a positive answer was requested ({@code ALWAYS_SEND}) is silence something to wait for
 * ({@link #AWAITING}) and eventually to follow up ({@link #OVERDUE}).
 */
public enum DeliveryStatus {
    /** No MLS, and only a failure would be reported: nothing is wrong as far as anyone knows. This is not a confirmation. */
    NO_REJECTION,
    /** A positive MLS was requested and none has arrived yet. */
    AWAITING,
    /**
     * A positive MLS was requested and none arrived within the overdue time: ask your Access Point. Do <b>not</b> send the document
     * again because of this alone: the receiver's Access Point has taken it over.
     */
    OVERDUE,
    /** MLS {@code AP}: delivered to the receiver, with its confirmation. */
    CONFIRMED,
    /** MLS {@code AB}: delivered towards the receiver, without confirmation. */
    UNCONFIRMED,
    /** MLS {@code RE}: not delivered, see the MLS issues. */
    REJECTED
}
