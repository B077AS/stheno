package io.github.b077as.stheno.lookup;

/**
 * Read-only Peppol network lookup.
 */
public interface PeppolLookup {

    /**
     * @param participantId  receiver, with or without scheme (e.g. {@code 0088:5798000000001})
     * @param documentTypeId document type, with or without scheme
     * @param processId      process, with or without scheme
     * @throws io.github.b077as.stheno.SthenoException {@code INVALID_INPUT} for malformed identifiers, {@code LOOKUP_FAILED} if the
     *                                                 network answer could not be obtained (the answer is then unknown)
     */
    ReceiverCapability lookup(String participantId, String documentTypeId, String processId);
}
