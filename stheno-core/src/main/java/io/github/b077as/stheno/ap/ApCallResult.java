package io.github.b077as.stheno.ap;

/**
 * Result of one acknowledgement call to the application's own Access Point.
 */
public enum ApCallResult {
    /** The Access Point took the information. */
    DONE,
    /** The Access Point already had it (e.g. a second delivery of the same document, or an MLS already decided). */
    ALREADY_DONE,
    /** The Access Point does not want it (e.g. MLS sending disabled there, or the document is itself an MLS). */
    NOT_APPLICABLE,
    /** Not called: switched off, or nothing to report (e.g. no receiver country known). */
    SKIPPED,
    /** The call failed; see the log. */
    FAILED
}
