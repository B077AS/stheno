package io.github.b077as.stheno.spring;

import lombok.Value;

/**
 * The HTTP answer for the Access Point's forwarding call.
 */
@Value
public class InboundResponse {

    int status;
    /** JSON body. */
    String body;
}
