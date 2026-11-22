package io.github.b077as.stheno.id;

import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.checks.validator.IdentifierValidator;
import com.helger.peppolid.codelist.ECodeListItemState;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.peppolid.peppol.doctype.IPeppolPredefinedDocumentTypeIdentifier;
import com.helger.peppolid.peppol.doctype.PredefinedDocumentTypeIdentifierManager;
import com.helger.peppolid.peppol.pidscheme.IPeppolParticipantIdentifierScheme;
import com.helger.peppolid.peppol.pidscheme.PeppolParticipantIdentifierSchemeManager;
import com.helger.peppolid.peppol.process.IPeppolPredefinedProcessIdentifier;
import com.helger.peppolid.peppol.process.PredefinedProcessIdentifierManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks for Peppol participant, document type and process identifiers, following the Peppol Policy for use of Identifiers 4.4.0.
 * <p>
 * All checks delegate to peppol-commons (identifier syntax, the official code lists with their states, and the scheme specific value
 * validators such as check digits). Stheno never adds own rules on top, so a check is never stricter than the official components.
 * <p>
 * Input may be given with or without the identifier scheme: {@code 0088:5798000000001} and
 * {@code iso6523-actorid-upis::0088:5798000000001} are the same participant.
 */
public final class PeppolIds {

    private static final PeppolIdentifierFactory IF = PeppolIdentifierFactory.INSTANCE;
    private static final String SCHEME_SEPARATOR = "::";

    private PeppolIds() {
    }

    /**
     * Parses a participant identifier, with or without the {@code iso6523-actorid-upis} scheme.
     *
     * @return the identifier, or {@code null} if it is not a syntactically valid Peppol participant identifier
     */
    public static IParticipantIdentifier parseParticipantId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String s = input.strip();
        return s.contains(SCHEME_SEPARATOR) ? IF.parseParticipantIdentifier(s) : IF.createParticipantIdentifierWithDefaultScheme(s);
    }

    /**
     * Checks a participant identifier: syntax (POLICY 1), the scheme code against the Peppol participant identifier scheme code list and
     * its state (POLICY 4, 4a), and the scheme specific value check of peppol-commons (e.g. check digits).
     */
    public static IdCheck checkParticipantId(String input, IdUsage usage) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        IParticipantIdentifier pid = parseParticipantId(input);
        if (pid == null) {
            errors.add("'" + input + "' is not a Peppol participant identifier: expected '<4 digit scheme code>:<value>' (e.g. "
                    + "'0088:5798000000001') with only letters, digits, '-', '.', '_' or '~' in the value, at most 130 characters");
            return IdCheck.of(null, errors, warnings);
        }

        String schemeCode = pid.getValue().substring(0, 4);
        IPeppolParticipantIdentifierScheme scheme = PeppolParticipantIdentifierSchemeManager.getSchemeOfISO6523Code(schemeCode);
        if (scheme == null) {
            codeListProblem(usage, errors, warnings,
                    "scheme code " + schemeCode + " is not in the Peppol participant identifier scheme code list");
        } else {
            stateProblem(usage, errors, warnings, scheme.getState(), "scheme code " + schemeCode + " (" + scheme.getSchemeID() + ")");
        }

        if (!IdentifierValidator.isValidParticipantIdentifier(pid)) {
            errors.add("the value of '" + pid.getValue() + "' fails the check of scheme " + schemeCode + " (e.g. a wrong check digit)");
        }
        return IdCheck.of(pid.getURIEncoded(), errors, warnings);
    }

    /** Parses a document type identifier, with or without scheme (default {@code busdox-docid-qns}). */
    public static IDocumentTypeIdentifier parseDocumentTypeId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String s = input.strip();
        return hasExplicitScheme(s) ? IF.parseDocumentTypeIdentifier(s) : IF.createDocumentTypeIdentifierWithDefaultScheme(s);
    }

    /** Checks a document type identifier: syntax and, if listed, its state in the Peppol document type code list. */
    public static IdCheck checkDocumentTypeId(String input, IdUsage usage) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        IDocumentTypeIdentifier id = parseDocumentTypeId(input);
        if (id == null) {
            errors.add("'" + input + "' is not a Peppol document type identifier");
            return IdCheck.of(null, errors, warnings);
        }
        IPeppolPredefinedDocumentTypeIdentifier listed =
                PredefinedDocumentTypeIdentifierManager.getDocumentTypeIdentifierOfID(id.getURIEncoded());
        if (listed == null) {
            warnings.add("document type '" + id.getValue() + "' is not in the Peppol document type code list");
        } else {
            stateProblem(usage, errors, warnings, listed.getState(), "document type '" + id.getValue() + "'");
        }
        return IdCheck.of(id.getURIEncoded(), errors, warnings);
    }

    /** Parses a process identifier, with or without scheme (default {@code cenbii-procid-ubl}). */
    public static IProcessIdentifier parseProcessId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String s = input.strip();
        return hasExplicitScheme(s) ? IF.parseProcessIdentifier(s) : IF.createProcessIdentifierWithDefaultScheme(s);
    }

    /** Checks a process identifier: syntax and, if listed, its state in the Peppol process code list. */
    public static IdCheck checkProcessId(String input, IdUsage usage) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        IProcessIdentifier id = parseProcessId(input);
        if (id == null) {
            errors.add("'" + input + "' is not a Peppol process identifier");
            return IdCheck.of(null, errors, warnings);
        }
        IPeppolPredefinedProcessIdentifier listed = PredefinedProcessIdentifierManager.getProcessIdentifierOfID(id.getURIEncoded());
        if (listed == null) {
            warnings.add("process '" + id.getValue() + "' is not in the Peppol process code list");
        } else {
            stateProblem(usage, errors, warnings, listed.getState(), "process '" + id.getValue() + "'");
        }
        return IdCheck.of(id.getURIEncoded(), errors, warnings);
    }

    /**
     * The normalised form of a participant identifier, usable as a map or database key: {@code scheme::value} in lower case (participant
     * identifier values are case insensitive, POLICY 2). Falls back to the trimmed lower case input if it cannot be parsed, so that
     * unparsable values still compare consistently.
     */
    public static String normalizeParticipantId(String input) {
        if (input == null) {
            return null;
        }
        IParticipantIdentifier pid = parseParticipantId(input);
        return pid != null ? pid.getURIEncoded().toLowerCase(Locale.ROOT) : input.strip().toLowerCase(Locale.ROOT);
    }

    /** {@code true} if both strings denote the same participant (case insensitive value, POLICY 2). */
    public static boolean isSameParticipant(String a, String b) {
        return a != null && b != null && normalizeParticipantId(a).equals(normalizeParticipantId(b));
    }

    private static boolean hasExplicitScheme(String s) {
        // Document type values themselves contain "::" (e.g. "...Invoice-2::Invoice##..."), so only a prefix without ':' counts as scheme
        int idx = s.indexOf(SCHEME_SEPARATOR);
        return idx > 0 && !s.substring(0, idx).contains(":");
    }

    private static void codeListProblem(IdUsage usage, List<String> errors, List<String> warnings, String text) {
        if (usage == IdUsage.NEW) {
            errors.add(text);
        } else {
            warnings.add(text);
        }
    }

    private static void stateProblem(IdUsage usage, List<String> errors, List<String> warnings, ECodeListItemState state, String what) {
        if (state == ECodeListItemState.REMOVED) {
            codeListProblem(usage, errors, warnings, what + " is removed from the Peppol code list and must not be used");
        } else if (state == ECodeListItemState.DEPRECATED) {
            codeListProblem(usage, errors, warnings, what + " is deprecated and must not be used for new documents");
        }
    }
}
