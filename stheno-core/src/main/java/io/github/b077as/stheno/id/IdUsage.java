package io.github.b077as.stheno.id;

/**
 * Why an identifier is checked. The Peppol Policy for use of Identifiers 4.4.0 (POLICY 4a, 23a, 25a) forbids deprecated code list
 * entries only for <b>new</b> documents and registrations, so a received document is judged more leniently.
 */
public enum IdUsage {
    /** The identifier comes from a document that was already received: code list problems are warnings. */
    RECEIVED,
    /** The identifier will be used in a new document or registration: deprecated and removed entries are errors. */
    NEW
}
