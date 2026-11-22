package io.github.b077as.stheno.validation;

import lombok.Value;

/**
 * One finding of a document validation.
 */
@Value
public class ValidationFinding {

    /** {@code true} for ERROR / FATAL_ERROR findings; warnings and infos are {@code false}. */
    boolean error;
    /** {@code true} if the finding comes from the XML Schema layer (a syntax problem), {@code false} for business rules. */
    boolean syntax;
    /** Rule identifier, e.g. {@code BR-CO-15}, or the field name for schema findings. */
    String ruleId;
    String message;
    /** Location in the document (XPath or line/column), may be {@code null}. */
    String location;

    @Override
    public String toString() {
        return (error ? "ERROR " : "WARN  ") + ruleId + (location == null ? "" : " @ " + location) + ": " + message;
    }
}
