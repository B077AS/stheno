package io.github.b077as.stheno.ap;

import lombok.Value;

/**
 * What {@link AccessPointAcknowledger} did for one received document.
 */
@Value
public class Acknowledgement {

    ApCallResult receiverCountry;
    ApCallResult mlsVerdict;
    /**
     * {@code true} if the application rejected the document but the Access Point did not take that verdict (an MLS was already
     * decided, e.g. its fallback {@code AB}; MLS sending is disabled; or the verdict is switched off). The sender may then believe the
     * document was delivered. A {@link ApCallResult#FAILED} call is not counted here, as it can still be retried.
     */
    boolean rejectionNotDelivered;

    public static final Acknowledgement NOTHING = new Acknowledgement(ApCallResult.SKIPPED, ApCallResult.SKIPPED, false);

    public boolean isFailed() {
        return receiverCountry == ApCallResult.FAILED || mlsVerdict == ApCallResult.FAILED;
    }
}
