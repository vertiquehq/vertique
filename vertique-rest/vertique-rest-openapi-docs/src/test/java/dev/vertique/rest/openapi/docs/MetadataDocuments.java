// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.assembly.AssemblyContext;
import dev.vertique.rest.openapi.docs.assembly.DocumentAssembler;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.config.EnabledDocumentsResolver;
import dev.vertique.rest.openapi.docs.config.InfoConfig;
import dev.vertique.rest.openapi.docs.diagnostics.DiagnosticsAccess;
import dev.vertique.rest.openapi.docs.diagnostics.DocumentWarnings;
import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.MetadataPublications;
import dev.vertique.rest.openapi.docs.metadata.OperationFacts;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.LoggerFactory;

/**
 * Assembles documents for the metadata proofs, resolves the {@code info} of fixture applications,
 * captures the documentation module's warnings, and normalizes documents for comparison.
 *
 * <p>It lives in the documentation package because the enabled document, the operation facts, the
 * assembly context, the warning guard, the assembler, and the sink's descriptor-fact and detach steps
 * are package-private.
 *
 * <p><b>Synthetic publications.</b> {@link #assemble(MountPublication, ApiDocs.Access, AssemblyContext)}
 * takes a publication built by {@link MetadataPublications}, whose operations still carry their
 * descriptors, and does what the documentation sink does before assembly: it takes the per-operation
 * facts through the sink's own {@code operationFacts}, detaches the publication through the sink's
 * own {@code detach}, then assembles. The document is enabled for the publication's application,
 * declared by its declaring type, at its mount path, with the fixed {@link #INFO}, no configured
 * server URL, and the access the caller names.
 *
 * <p><b>Warnings.</b> An assembly context carries the {@link DocumentWarnings} guard the caller
 * passes; pass one guard to several assemblies to stand for one component, or a fresh one per
 * assembly. {@link WarningCapture} records what the guard logs.
 *
 * <p><b>Configured {@code info}.</b> {@link #resolve} parses the {@code apidocs} section and runs the
 * enabled-document selection over a view holding one hand-written registration, as the
 * composition does, and {@link #assembleResolved} assembles a resolved document over a publication
 * without operations.
 */
public final class MetadataDocuments {

    /** The {@code info} of every document assembled from a synthetic publication. */
    static final InfoConfig INFO = new InfoConfig("Metadata", "1.0", null);

    /** The name of the logger the documentation module's warnings are logged on. */
    static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetadataDocuments() {}

    // ---------------------------------------------------------------------------------------------
    // Assembly contexts
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a fresh warning guard, standing for one component.
     *
     * @return the guard
     */
    public static DocumentWarnings warnings() {
        return DiagnosticsAccess.documentWarnings();
    }

    /**
     * Returns an assembly context that binds no schema source and has a fresh warning guard.
     *
     * @return the context
     */
    static AssemblyContext context() {
        return context(warnings());
    }

    /**
     * Returns an assembly context that binds no schema source and uses the given warning guard.
     *
     * @param warnings the guard
     * @return the context
     */
    static AssemblyContext context(DocumentWarnings warnings) {
        return new AssemblyContext(
                Optional.empty(), registry(), Objects.requireNonNull(warnings, "warnings"), Set.of(), Set.of());
    }

    /**
     * Returns an assembly context that binds the given schema source and uses the given warning guard.
     *
     * @param source the bound source
     * @param warnings the guard
     * @return the context
     */
    static AssemblyContext context(OperationSchemaSource source, DocumentWarnings warnings) {
        return new AssemblyContext(
                Optional.of(Objects.requireNonNull(source, "source")),
                registry(),
                Objects.requireNonNull(warnings, "warnings"),
                Set.of(),
                Set.of());
    }

    private static DefaultJsonMapperProfileRegistry registry() {
        return new DefaultJsonMapperProfileRegistry(Set.of());
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic publications
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the enabled document of a publication.
     *
     * @param publication the publication
     * @param access the document's access
     * @return the document, with {@link #INFO} and no configured server URL
     */
    public static EnabledDocuments.EnabledDocument document(MountPublication publication, ApiDocs.Access access) {
        return new EnabledDocuments.EnabledDocument(
                Objects.requireNonNull(publication.applicationName(), "applicationName"),
                Objects.requireNonNull(publication.declaringType(), "declaringType"),
                Objects.requireNonNull(access, "access"),
                publication.mountPath(),
                ContractOrigin.GLOBAL,
                INFO,
                null,
                null);
    }

    /**
     * Assembles the document of a publication whose operations carry their descriptors.
     *
     * @param attached the publication, for example from {@link MetadataPublications#build()}
     * @param access the document's access
     * @param context the assembly context
     * @return the rendering, or the publication failure
     */
    static Outcome assemble(MountPublication attached, ApiDocs.Access access, AssemblyContext context) {
        return assemble(document(attached, access), attached, context);
    }

    /**
     * Assembles the document of a publication whose operations carry their descriptors, as the given
     * enabled document.
     *
     * @param document the enabled document
     * @param attached the publication
     * @param context the assembly context
     * @return the rendering, or the publication failure
     */
    static Outcome assemble(
            EnabledDocuments.EnabledDocument document, MountPublication attached, AssemblyContext context) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(context, "context");
        Map<String, OperationFacts> facts = PublicationAccess.operationFacts(attached);
        MountPublication detached = PublicationAccess.detach(attached);
        try {
            PublishedDocument published = DocumentAssembler.assemble(document, detached, facts, context);
            return Outcome.ofRendering(new Rendering(published.json(), published.yaml()));
        } catch (RestConfigurationException failure) {
            return Outcome.ofFailure(failure);
        }
    }

    /**
     * Assembles the public document of a publication that must publish.
     *
     * @param attached the publication
     * @param context the assembly context
     * @return the rendering
     * @throws AssertionError when publication fails
     */
    static Rendering renderPublic(MountPublication attached, AssemblyContext context) {
        return assemble(attached, ApiDocs.Access.PUBLIC, context).rendering();
    }

    /**
     * Assembles the protected document of a publication that must publish.
     *
     * @param attached the publication
     * @param context the assembly context
     * @return the rendering
     * @throws AssertionError when publication fails
     */
    static Rendering renderProtected(MountPublication attached, AssemblyContext context) {
        return assemble(attached, ApiDocs.Access.PROTECTED, context).rendering();
    }

    /**
     * Assembles the public document of a publication that must fail publication.
     *
     * @param attached the publication
     * @param context the assembly context
     * @return the failure
     * @throws AssertionError when the document publishes
     */
    static RestConfigurationException failurePublic(MountPublication attached, AssemblyContext context) {
        return assemble(attached, ApiDocs.Access.PUBLIC, context).failure();
    }

    // ---------------------------------------------------------------------------------------------
    // Configured info
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses the {@code apidocs} section, then runs the enabled-document selection, for a view
     * holding exactly one registration, over the loopback configuration plus, when given, {@code
     * apidocs.documents.<name>.info}.
     *
     * @param registration the registration, for example {@code InfoRegistrations.childApi(...)}
     * @param configuredInfo the configured {@code info} object, or {@code null} for none
     * @return the enabled documents, or whatever parsing or selection threw
     */
    static Resolution resolve(GeneratedRestApplicationRegistration registration, @Nullable JsonObject configuredInfo) {
        Objects.requireNonNull(registration, "registration");
        JsonObject config = DocsConfigs.loopback();
        if (configuredInfo != null) {
            DocsConfigs.document(config, registration.name()).put("info", configuredInfo.copy());
        }
        ConfigViewComponents.ViewComponent component = DaggerConfigViewComponents_ViewComponent.factory()
                .create(config, new ConfigViewComponents.RegistrationList(List.of(registration)));
        RestApplications applications = component.restApplications();
        ConfigParser parser = component.configParser();
        try {
            return Resolution.ofDocuments(EnabledDocumentsResolver.resolve(config, parser, () -> applications));
        } catch (RuntimeException failure) {
            return Resolution.ofFailure(failure);
        }
    }

    /**
     * Assembles a resolved document over a publication of its application without operations, at its
     * mount path and declared by its declaring type, as the composition would.
     *
     * @param document the resolved document
     * @param warnings the warning guard, standing for the component
     * @return the rendering, or the publication failure
     */
    static Outcome assembleResolved(EnabledDocuments.EnabledDocument document, DocumentWarnings warnings) {
        Objects.requireNonNull(document, "document");
        MountPublication empty = MetadataPublications.from(Publications.mount(document.mountPath())
                        .application(document.name(), document.declaringType())
                        .build())
                .build();
        return assemble(document, empty, context(warnings));
    }

    /**
     * Returns the rendered root {@code info} member.
     *
     * @param rendering the rendering
     * @return the member, its members in written order
     */
    static JsonNode info(Rendering rendering) {
        JsonNode info = rendering.jsonTree().get("info");
        assertNotNull(info, () -> "the document has no info: " + rendering.jsonText());
        return info;
    }

    /**
     * Returns the rendered root {@code info} member as compact JSON text, its members in written order.
     *
     * @param rendering the rendering
     * @return the text, without insignificant whitespace
     */
    static String infoJson(Rendering rendering) {
        try {
            return JSON.writeValueAsString(info(rendering));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Comparison
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a copy of a document without its {@code servers} and {@code info}, the members that
     * differ between two applications describing the same operations.
     *
     * @param document the document
     * @return the normalized copy; the argument is unchanged
     */
    static JsonNode normalized(JsonNode document) {
        JsonNode copy = document.deepCopy();
        if (copy instanceof ObjectNode root) {
            root.remove("servers");
            root.remove("info");
        }
        return copy;
    }

    /**
     * Parses a JSON document and normalizes it as {@link #normalized(JsonNode)} does.
     *
     * @param json the document's JSON bytes, for example a response body
     * @return the normalized document
     */
    static JsonNode normalized(byte[] json) {
        try {
            return normalized(JSON.readTree(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses a JSON document text and normalizes it as {@link #normalized(JsonNode)} does.
     *
     * @param json the document's JSON text
     * @return the normalized document
     */
    static JsonNode normalized(String json) {
        return normalized(json.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------------------------------

    /**
     * The outcome of one assembly: exactly one of a rendering and a publication failure.
     *
     * @param published the rendering, or {@code null} when publication failed
     * @param failed the failure, or {@code null} when the document published
     */
    public record Outcome(
            @Nullable Rendering published, @Nullable RestConfigurationException failed) {

        static Outcome ofRendering(Rendering rendering) {
            return new Outcome(rendering, null);
        }

        static Outcome ofFailure(RestConfigurationException failure) {
            return new Outcome(null, failure);
        }

        /**
         * Reports whether the document published.
         *
         * @return {@code true} when a rendering is present
         */
        boolean isPublished() {
            return published != null;
        }

        /**
         * Returns the rendering of a document that must publish.
         *
         * @return the rendering
         * @throws AssertionError when publication failed, carrying the failure as its cause
         */
        public Rendering rendering() {
            if (published == null) {
                throw new AssertionError("expected the document to publish, but publication failed", failed);
            }
            return published;
        }

        /**
         * Returns the failure of a document that must fail publication.
         *
         * @return the failure
         * @throws AssertionError when the document published
         */
        public RestConfigurationException failure() {
            if (failed == null) {
                throw new AssertionError("expected publication to fail, but the document published: "
                        + Objects.requireNonNull(published).jsonText());
            }
            return failed;
        }
    }

    /**
     * The outcome of resolving the enabled documents: exactly one of the documents and the thrown
     * exception, whatever its type.
     *
     * @param resolved the enabled documents, or {@code null} when resolution threw
     * @param thrown the exception, or {@code null} when resolution succeeded
     */
    record Resolution(
            @Nullable EnabledDocuments resolved, @Nullable RuntimeException thrown) {

        static Resolution ofDocuments(EnabledDocuments documents) {
            return new Resolution(documents, null);
        }

        static Resolution ofFailure(RuntimeException failure) {
            return new Resolution(null, failure);
        }

        /**
         * Returns the one enabled document of a resolution that must succeed.
         *
         * @return the document
         * @throws AssertionError when resolution threw or did not enable exactly one document
         */
        public EnabledDocuments.EnabledDocument document() {
            if (resolved == null) {
                throw new AssertionError("expected the document to resolve, but resolution threw", thrown);
            }
            if (resolved.all().size() != 1) {
                throw new AssertionError("expected exactly one enabled document, got " + resolved.all());
            }
            return resolved.all().get(0);
        }

        /**
         * Returns the exception of a resolution that must fail.
         *
         * @return the exception
         * @throws AssertionError when resolution succeeded
         */
        public RuntimeException failure() {
            if (thrown == null) {
                throw new AssertionError("expected resolution to fail, but it enabled " + resolved);
            }
            return thrown;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Warnings
    // ---------------------------------------------------------------------------------------------

    /**
     * Captures the events logged on the {@value MetadataDocuments#WARNINGS_LOGGER} logger with a Logback {@link
     * ListAppender}. Attach it in {@code @BeforeEach} and detach it in {@code @AfterEach}; while
     * attached the logger's level is {@code WARN}, and detaching restores the previous level.
     */
    public static final class WarningCapture {

        private final Logger logger = (Logger) LoggerFactory.getLogger(WARNINGS_LOGGER);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private @Nullable Level previousLevel;
        private boolean attached;

        /** Starts capturing. */
        public void attach() {
            if (attached) {
                throw new IllegalStateException("the capture is already attached");
            }
            previousLevel = logger.getLevel();
            logger.setLevel(Level.WARN);
            appender.list.clear();
            appender.start();
            logger.addAppender(appender);
            attached = true;
        }

        /** Stops capturing and restores the logger's previous level; safe to call when not attached. */
        public void detach() {
            if (!attached) {
                return;
            }
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            attached = false;
        }

        /** Forgets every event captured so far. */
        void clear() {
            appender.list.clear();
        }

        /**
         * Returns the captured events at {@code WARN}, in logging order.
         *
         * @return the events
         */
        List<ILoggingEvent> warnEvents() {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
        }

        /**
         * Returns the formatted messages of the captured events at {@code WARN}, in logging order.
         *
         * @return the messages
         */
        public List<String> warnings() {
            return warnEvents().stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }
}
