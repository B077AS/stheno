package io.github.b077as.stheno;

import lombok.Getter;

/**
 * The one exception type of Stheno. {@link #getCode()} tells what went wrong in a stable, machine readable way.
 */
@Getter
public class SthenoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public SthenoException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SthenoException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable error codes. */
    public enum ErrorCode {
        /** The received bytes are not a readable Peppol Business Message Envelope (SBDH). */
        INVALID_ENVELOPE,
        /** Data needed to build a document is missing or invalid. Nothing is defaulted silently. */
        INVALID_INPUT,
        /** A call to the application's own Access Point failed. */
        ACCESS_POINT_CALL_FAILED,
        /** A Peppol network lookup (SML/DNS, SMP) could not be completed; the answer is unknown (not "cannot receive"). */
        LOOKUP_FAILED,
        /** No validation rule set is known for the document (or validation is not on the classpath). */
        VALIDATION_UNAVAILABLE,
        /** The received message store (e.g. the database) failed. */
        STORE_FAILED,
        /** No handler takes the received document, so it was not taken over. */
        NO_HANDLER,
        /** A received business document cannot be read as the kind of document its type says (e.g. an Invoice Response). */
        INVALID_DOCUMENT
    }
}
