package io.github.b077as.stheno.lookup;

import com.helger.peppol.security.PeppolTrustStores;
import com.helger.peppol.sml.ESML;
import com.helger.peppol.sml.ISMLInfo;

import java.security.KeyStore;
import java.util.Collections;

/**
 * Which Peppol network to query, with its SML and the official OpenPeppol SMP trust store used to verify the signature of SMP answers
 * (the 2018 and the 2025 CA generation merged, because SMPs of both generations are in use). Lookups are read-only.
 */
public enum PeppolNetwork {

    /** The live network. */
    PRODUCTION(ESML.PEPPOL_PRODUCTION),
    /** The Peppol test network. */
    TEST(ESML.PEPPOL_TEST);

    private final ISMLInfo sml;
    private volatile KeyStore smpTrustStore;

    PeppolNetwork(ISMLInfo sml) {
        this.sml = sml;
    }

    public ISMLInfo getSml() {
        return sml;
    }

    public KeyStore getSmpTrustStore() {
        KeyStore ks = smpTrustStore;
        if (ks == null) {
            synchronized (this) {
                ks = smpTrustStore;
                if (ks == null) {
                    ks = this == PRODUCTION
                            ? merge(PeppolTrustStores.Config2025.TRUSTSTORE_SMP_PRODUCTION, PeppolTrustStores.Config2018.TRUSTSTORE_SMP_PRODUCTION)
                            : merge(PeppolTrustStores.Config2025.TRUSTSTORE_SMP_TEST, PeppolTrustStores.Config2018.TRUSTSTORE_SMP_PILOT);
                    smpTrustStore = ks;
                }
            }
        }
        return ks;
    }

    private static KeyStore merge(KeyStore... stores) {
        try {
            KeyStore merged = KeyStore.getInstance("PKCS12");
            merged.load(null, null);
            int n = 0;
            for (KeyStore s : stores) {
                for (String alias : Collections.list(s.aliases())) {
                    if (s.isCertificateEntry(alias)) {
                        merged.setCertificateEntry("trust-" + (n++) + "-" + alias, s.getCertificate(alias));
                    }
                }
            }
            return merged;
        } catch (java.io.IOException | java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot build the Peppol SMP trust store", ex);
        }
    }
}
