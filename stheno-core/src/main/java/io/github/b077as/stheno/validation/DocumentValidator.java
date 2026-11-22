package io.github.b077as.stheno.validation;

import com.helger.ddd.DocumentDetails;
import com.helger.ddd.DocumentDetailsDeterminator;
import com.helger.ddd.model.DDDSyntaxList;
import com.helger.ddd.model.DDDValueProviderList;
import com.helger.diagnostics.error.IError;
import com.helger.diver.api.coord.DVRCoordinate;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.phive.api.execute.IValidationExecutorCacheSupport;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executor.IValidationExecutor;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.phive.peppol.PeppolValidation;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundPayload;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Validates business documents with phive and the official rule sets (XSD + Schematron). The rule set of a document is found by
 * ddd (Document Details Determinator) from its customization and profile, the way phoss-ap does it.
 * <p>
 * Only ERROR and FATAL_ERROR findings make a document invalid; warnings never do (ph-commons' {@code isFailure()} is also true for
 * warnings and is not used). Needs {@code phive-xml}, {@code phive-rules-*} and {@code ddd} on the classpath.
 * <p>
 * Thread-safe. Compiling a Schematron rule set takes seconds the first time; use {@link #warmUp} at startup.
 */
@Slf4j
public final class DocumentValidator {

    private final ValidationExecutorSetRegistry<IValidationSourceXML> registry = new ValidationExecutorSetRegistry<>();
    private final DocumentDetailsDeterminator ddd;

    /** With all Peppol rule sets of {@code phive-rules-peppol}. */
    public DocumentValidator() {
        this(PeppolValidation::initStandard);
    }

    /** With the rule sets the initializer registers (e.g. {@code PeppolValidation::initStandard} plus national rule packages). */
    public DocumentValidator(Consumer<ValidationExecutorSetRegistry<IValidationSourceXML>> initializer) {
        initializer.accept(registry);
        ddd = new DocumentDetailsDeterminator(DDDSyntaxList.getDefaultSyntaxList(), DDDValueProviderList.getDefaultValueProviderList());
        ddd.setIdentifierFactory(PeppolIdentifierFactory.INSTANCE);
    }

    /**
     * The rule set ddd determines for a document, or {@code null} if it does not know the document.
     */
    public String findRuleSet(Element root) {
        DocumentDetails details = ddd.findDocumentDetails(root);
        return details != null && details.hasVESID() ? details.getVESID() : null;
    }

    /** Validates the XML payload of a received document with the rule set ddd finds for it. */
    public ValidationReport validate(InboundDocument document) {
        InboundPayload payload = document.getPayload();
        if (payload == null || payload.getKind() != InboundPayload.Kind.XML) {
            throw new SthenoException(SthenoException.ErrorCode.VALIDATION_UNAVAILABLE, "Only XML payloads can be validated");
        }
        return validate(payload.getXml());
    }

    /** Validates a document with the rule set ddd finds for it. */
    public ValidationReport validate(Element root) {
        String ruleSet = findRuleSet(root);
        if (ruleSet == null) {
            throw new SthenoException(SthenoException.ErrorCode.VALIDATION_UNAVAILABLE,
                    "No validation rule set is known for a {" + root.getNamespaceURI() + "}" + root.getLocalName() + " document");
        }
        return validate(root, ruleSet);
    }

    /** Validates a document with an explicit rule set (phive VESID, e.g. {@code eu.peppol.bis3:invoice:2026.5}). */
    public ValidationReport validate(Element root, String ruleSet) {
        IValidationExecutorSet<IValidationSourceXML> ves = executorSet(ruleSet);
        ValidationResultList results = ValidationExecutionManager.executeValidation(IValidityDeterminator.createDefault(), ves,
                ValidationSourceXML.create(null, root), Locale.ENGLISH);
        List<ValidationFinding> findings = new ArrayList<>();
        for (ValidationResult result : results) {
            boolean syntax = result.getValidationArtefact().getValidationType().getBaseType().isXSD();
            for (IError e : result.getErrorList()) {
                ValidationFinding f = toFinding(e, syntax);
                if (f != null) {
                    findings.add(f);
                }
            }
        }
        return new ValidationReport(ves.getID().getAsSingleID(), List.copyOf(findings));
    }

    /**
     * Loads and compiles rule sets ahead of time so that the first real validation is fast.
     *
     * @param ruleSets VESIDs to prepare; empty = all registered rule sets (slow, uses memory)
     * @return the number of rule sets prepared
     */
    public int warmUp(Collection<String> ruleSets) {
        List<IValidationExecutorSet<IValidationSourceXML>> sets = new ArrayList<>();
        if (ruleSets.isEmpty()) {
            sets.addAll(registry.getAll());
        } else {
            for (String id : ruleSets) {
                sets.add(executorSet(id));
            }
        }
        for (IValidationExecutorSet<IValidationSourceXML> ves : sets) {
            for (IValidationExecutor<IValidationSourceXML> executor : ves.getAllExecutors()) {
                if (executor instanceof IValidationExecutorCacheSupport cacheable) {
                    cacheable.ensureItemIsInCache();
                }
            }
        }
        log.info("Prepared {} validation rule set(s)", sets.size());
        return sets.size();
    }

    private IValidationExecutorSet<IValidationSourceXML> executorSet(String ruleSet) {
        DVRCoordinate id = DVRCoordinate.parseOrNull(ruleSet);
        IValidationExecutorSet<IValidationSourceXML> ves = id == null ? null : registry.getOfID(id);
        if (ves == null) {
            throw new SthenoException(SthenoException.ErrorCode.VALIDATION_UNAVAILABLE,
                    "Validation rule set '" + ruleSet + "' is not registered (missing phive-rules package?)");
        }
        return ves;
    }

    private static ValidationFinding toFinding(IError e, boolean syntax) {
        String text = e.getErrorText(Locale.ENGLISH);
        // Saxon compile-time hints about the rule XSLT itself are not findings about the document (seen in Envoy)
        if (e.getErrorID() == null && text != null && text.startsWith("Transformation warning")) {
            return null;
        }
        if (e.hasLinkedException()) {
            text += " (" + e.getLinkedException().getMessage() + ")";
        }
        boolean error = e.getErrorLevel().isError();
        String location = e.getErrorLocation() == null || !e.getErrorLocation().isAnyInformationPresent() ? null
                : e.getErrorLocation().getAsString();
        if (location == null && e.getErrorFieldName() != null) {
            location = e.getErrorFieldName();
        }
        String ruleId = e.getErrorID() != null ? e.getErrorID() : syntax ? "XSD" : null;
        return new ValidationFinding(error, syntax, ruleId, text, location);
    }
}
