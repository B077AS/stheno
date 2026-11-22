package io.github.b077as.stheno.ap;

import lombok.Value;

/**
 * What {@link AccessPointAcknowledger} did for one received document.
 */
@Value
public class Acknowledgement {

    ApCallResult receiverCountry;
    ApCallResult mlsVerdict;

    public static final Acknowledgement NOTHING = new Acknowledgement(ApCallResult.SKIPPED, ApCallResult.SKIPPED);

    public boolean isFailed() {
        return receiverCountry == ApCallResult.FAILED || mlsVerdict == ApCallResult.FAILED;
    }
}
