package io.github.b077as.stheno.validation;

import io.github.b077as.stheno.inbound.MlsIssue;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of validating one document against one rule set.
 */
@Value
public class ValidationReport {

    /** The rule set used (phive VESID, e.g. {@code eu.peppol.bis3:invoice:2026.5}). */
    String ruleSet;
    List<ValidationFinding> findings;

    /** {@code true} if there is no ERROR / FATAL_ERROR finding. Warnings never make a document invalid. */
    public boolean isValid() {
        return findings.stream().noneMatch(ValidationFinding::isError);
    }

    public List<ValidationFinding> getErrors() {
        return findings.stream().filter(ValidationFinding::isError).toList();
    }

    /**
     * The findings as MLS line responses: schema errors as {@code SV}, business rule errors as {@code BV}, warnings as {@code BW}.
     * Use them with {@link io.github.b077as.stheno.inbound.InboundOutcome#rejected} when the document is refused.
     */
    public List<MlsIssue> toMlsIssues() {
        List<MlsIssue> issues = new ArrayList<>();
        for (ValidationFinding f : findings) {
            MlsIssue.Reason reason = !f.isError() ? MlsIssue.Reason.BW : f.isSyntax() ? MlsIssue.Reason.SV : MlsIssue.Reason.BV;
            String text = f.getRuleId() == null ? f.getMessage() : "[" + f.getRuleId() + "] " + f.getMessage();
            issues.add(new MlsIssue(reason, f.getLocation(), text == null ? "" : text));
        }
        return issues;
    }
}
