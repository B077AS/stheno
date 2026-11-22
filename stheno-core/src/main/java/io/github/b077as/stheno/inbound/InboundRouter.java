package io.github.b077as.stheno.inbound;

import lombok.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Chooses the application handler for a received document. Routes are tried in the order they were added; the first match wins.
 * Document type identifiers are compared case sensitively (Identifier Policy POLICY 2), with or without the {@code busdox-docid-qns}
 * scheme.
 */
public final class InboundRouter {

    private final List<Route> routes;
    private final MlsHandler mlsHandler;
    private final InboundHandler fallback;

    private InboundRouter(List<Route> routes, MlsHandler mlsHandler, InboundHandler fallback) {
        this.routes = List.copyOf(routes);
        this.mlsHandler = mlsHandler;
        this.fallback = fallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The handler for a business document, or {@code null} if none matches and there is no fallback. */
    public InboundHandler findHandler(InboundDocument document) {
        for (Route route : routes) {
            if (route.getMatcher().test(document)) {
                return route.getHandler();
            }
        }
        return fallback;
    }

    /** The handler for received MLS messages, or {@code null}. */
    public MlsHandler getMlsHandler() {
        return mlsHandler;
    }

    /** {@code true} if the document has the given document type (full {@code scheme::value} or only the value). */
    public static boolean hasDocumentType(InboundDocument document, String documentTypeId) {
        String actual = document.getDocumentTypeId();
        if (actual == null || documentTypeId == null) {
            return false;
        }
        if (actual.equals(documentTypeId)) {
            return true;
        }
        int idx = actual.indexOf("::");
        return idx > 0 && actual.substring(idx + 2).equals(documentTypeId);
    }

    @Value
    private static class Route {
        Predicate<InboundDocument> matcher;
        InboundHandler handler;
    }

    /** Builder. */
    public static final class Builder {

        private final List<Route> routes = new ArrayList<>();
        private MlsHandler mlsHandler;
        private InboundHandler fallback;

        /** Handles documents of exactly this document type (with or without scheme). */
        public Builder documentType(String documentTypeId, InboundHandler handler) {
            Objects.requireNonNull(documentTypeId, "documentTypeId");
            return when(d -> hasDocumentType(d, documentTypeId), handler);
        }

        /** Handles documents matching a condition, e.g. on the payload root element or the process. */
        public Builder when(Predicate<InboundDocument> matcher, InboundHandler handler) {
            routes.add(new Route(Objects.requireNonNull(matcher, "matcher"), Objects.requireNonNull(handler, "handler")));
            return this;
        }

        /** Receives the MLS answers to documents the application sent. */
        public Builder mls(MlsHandler handler) {
            mlsHandler = handler;
            return this;
        }

        /** Handles every business document no other route matched. */
        public Builder fallback(InboundHandler handler) {
            fallback = handler;
            return this;
        }

        public InboundRouter build() {
            return new InboundRouter(routes, mlsHandler, fallback);
        }
    }
}
