package io.github.b077as.stheno.inbound;

/**
 * What the {@link InboundProcessor} does with a business document no handler takes.
 */
public enum UnhandledDocumentPolicy {
    /**
     * Do not take the document over ({@link InboundProcessor#receive} throws {@code NO_HANDLER}), so the Access Point delivers it again.
     * A missing handler is usually a mistake of the deployment, and an MLS rejection cannot be taken back: only one is allowed.
     */
    RETRY,
    /** Reject the document: MLS {@code RE} with reason {@code FD}, for applications that will never process other document types. */
    REJECT
}
