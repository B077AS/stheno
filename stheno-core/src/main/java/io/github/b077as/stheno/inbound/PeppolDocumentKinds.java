package io.github.b077as.stheno.inbound;

import com.helger.peppolid.peppol.process.EPredefinedProcessIdentifier;

/**
 * Well-known Peppol identifiers used to recognise special documents.
 */
public final class PeppolDocumentKinds {

    /** Process identifier value of the Peppol Message Level Status (taken from the peppol-commons code list). */
    public static final String MLS_PROCESS_ID = EPredefinedProcessIdentifier.urn_peppol_edec_mls.getValue();

    private PeppolDocumentKinds() {
    }
}
