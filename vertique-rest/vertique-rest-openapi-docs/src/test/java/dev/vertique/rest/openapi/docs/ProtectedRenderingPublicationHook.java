// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.assembly.AssemblyContext;
import dev.vertique.rest.openapi.docs.assembly.DocumentAssembler;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.diagnostics.DiagnosticsAccess;
import dev.vertique.rest.openapi.docs.document.DocumentInfo;
import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import dev.vertique.rest.openapi.docs.metadata.OperationFacts;
import dev.vertique.rest.openapi.docs.publication.DocsPublicationHook;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test publication hook that renders, without serving, the document of every application mount
 * whose declaring interface carries {@link ApiDocs}, in the access that annotation declares, and keeps
 * only the rendered bytes and a projection of each operation's binding inventory.
 *
 * <p>For such a mount, {@link #mountBuilt} takes the operation facts with {@link
 * DocsPublicationHook#operationFacts}, takes its own detached copy of the publication (every schema
 * object copied, the descriptor dropped), and calls {@link DocumentAssembler#assemble} with an enabled
 * document of the application (fixed {@link #INFO}, no server URL, access from the annotation's
 * {@code access()}) and an assembly context of the component's bound schema source and profile
 * registry. Assembly runs on a worker thread of the calling context when there is one, and on the
 * calling thread otherwise; the returned future completes on the calling context only after the
 * outcome is stored, so a deployment that succeeded has its renderings.
 *
 * <p>The hook only records: a failed assembly stores the failure's message (the message of a {@link
 * RestConfigurationException}; the class name and message of anything else) and the mount still
 * builds. The documentation module's own hook, when the component lists that module, is what fails a
 * startup. A component that lists this hook and not the documentation module never deploys the
 * documentation mount, so a protected document is rendered here and served nowhere.
 *
 * <p>It wants detail for every mount that serves a declared application. For each such operation
 * with detail it keeps an immutable {@link InventoryEntry} list in inventory order, whether or not
 * the declaring interface carries {@code @ApiDocs}. Several verticle instances building one mount
 * record equal outcomes; the last one is kept. Thread-safe.
 */
final class ProtectedRenderingPublicationHook implements MountPublicationHook {

    /** The {@code info} of every rendered document. */
    static final DocumentInfo INFO = new DocumentInfo("Protected rendering", "1.0", null);

    private final AssemblyContext context;
    private final RestApplications applications;
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Map<Key, List<InventoryEntry>> inventories = new ConcurrentHashMap<>();

    /**
     * Creates the hook.
     *
     * @param schemaSource the component's bound schema source, if any
     * @param profiles     the component's profile registry
     * @param applications the component's declared applications, for each document's contract origin
     */
    ProtectedRenderingPublicationHook(
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles,
            RestApplications applications) {
        this.context =
                new AssemblyContext(schemaSource, profiles, DiagnosticsAccess.documentWarnings(), Set.of(), Set.of());
        this.applications = Objects.requireNonNull(applications, "applications");
    }

    /**
     * Wants detail for every mount that serves a declared application.
     *
     * @param applicationName the mount's application name, or {@code null}
     * @return {@code true} exactly when {@code applicationName} is non-null
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return applicationName != null;
    }

    /**
     * Records the inventory projection and, for a mount declared by an {@code @ApiDocs} interface,
     * renders and records its document.
     *
     * @param publication the mount's publication
     * @return a future that always succeeds, after the outcome is stored, on the calling context
     */
    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        String applicationName = publication.applicationName();
        if (applicationName == null) {
            return Future.succeededFuture();
        }
        for (OperationPublication operation : publication.operations()) {
            OperationDetail detail = operation.detail();
            if (detail != null) {
                inventories.put(
                        new Key(applicationName, operation.operationId()), InventoryEntry.ofAll(detail.inputs()));
            }
        }
        Class<?> declaringType = publication.declaringType();
        ApiDocs apiDocs = declaringType == null ? null : declaringType.getAnnotation(ApiDocs.class);
        if (apiDocs == null) {
            return Future.succeededFuture();
        }
        ContractOrigin origin = applications
                .byName(applicationName)
                .map(RestApplications.Entry::contractOrigin)
                .orElse(ContractOrigin.GLOBAL);
        EnabledDocuments.EnabledDocument document = new EnabledDocuments.EnabledDocument(
                applicationName, declaringType, apiDocs.access(), publication.mountPath(), origin, INFO, null, null);
        Map<String, OperationFacts> facts = PublicationAccess.operationFacts(publication);
        MountPublication detached = PublicationAccess.detach(publication);
        Callable<Outcome> task = () -> render(document, detached, facts);
        Context caller = Vertx.currentContext();
        if (caller == null) {
            outcomes.put(applicationName, render(document, detached, facts));
            return Future.succeededFuture();
        }
        return caller.executeBlocking(task).transform(rendered -> {
            outcomes.put(applicationName, rendered.succeeded() ? rendered.result() : Outcome.failed(rendered.cause()));
            return Future.succeededFuture();
        });
    }

    /**
     * Returns the JSON form of an application's rendered document.
     *
     * @param applicationName the application name
     * @return a copy of the JSON bytes
     * @throws IllegalStateException if nothing was rendered for the application, or its assembly
     *     failed (the message then carries the failure)
     */
    byte[] json(String applicationName) {
        return rendered(applicationName).json().clone();
    }

    /**
     * Returns the YAML form of an application's rendered document.
     *
     * @param applicationName the application name
     * @return a copy of the YAML bytes
     * @throws IllegalStateException if nothing was rendered for the application, or its assembly
     *     failed (the message then carries the failure)
     */
    byte[] yaml(String applicationName) {
        return rendered(applicationName).yaml().clone();
    }

    /**
     * Returns the failure of an application's assembly.
     *
     * @param applicationName the application name
     * @return the failure message, or empty when the document was rendered
     * @throws IllegalStateException if no assembly was recorded for the application
     */
    Optional<String> failure(String applicationName) {
        return Optional.ofNullable(outcome(applicationName).failure());
    }

    /**
     * Returns the inventory projection of one operation.
     *
     * @param applicationName the application name
     * @param operationId     the operation id
     * @return the entries, in inventory order
     * @throws IllegalStateException if no inventory was recorded for the operation
     */
    List<InventoryEntry> inventory(String applicationName, String operationId) {
        List<InventoryEntry> inventory = inventories.get(new Key(applicationName, operationId));
        if (inventory == null) {
            Set<String> recorded = new TreeSet<>();
            inventories.keySet().forEach(key -> recorded.add(key.applicationName() + "/" + key.operationId()));
            throw new IllegalStateException("no inventory was recorded for operation '" + operationId
                    + "' of application '" + applicationName + "'; recorded: " + recorded);
        }
        return inventory;
    }

    private Outcome outcome(String applicationName) {
        Outcome outcome = outcomes.get(applicationName);
        if (outcome == null) {
            throw new IllegalStateException("no document was assembled for application '" + applicationName
                    + "'; assembled: " + new TreeSet<>(outcomes.keySet()));
        }
        return outcome;
    }

    private Outcome rendered(String applicationName) {
        Outcome outcome = outcome(applicationName);
        if (outcome.failure() != null) {
            throw new IllegalStateException(
                    "the document of application '" + applicationName + "' failed to assemble: " + outcome.failure());
        }
        return outcome;
    }

    private Outcome render(
            EnabledDocuments.EnabledDocument document, MountPublication detached, Map<String, OperationFacts> facts) {
        try {
            PublishedDocument published = DocumentAssembler.assemble(document, detached, facts, context);
            return new Outcome(published.json(), published.yaml(), null);
        } catch (Throwable failure) {
            return Outcome.failed(failure);
        }
    }

    /**
     * One binding of an operation's inventory, projected to plain values.
     *
     * @param origin   the binding's origin name, for example {@code PARAMETER}, {@code BODY}, or {@code
     *     COMPOSITE_FIELD}
     * @param location the binding's location name, for example {@code QUERY}, or {@code null} for the
     *     body
     * @param name     the binding's name, or {@code null} for the body
     * @param hidden   the binding's {@code hidden} flag
     */
    record InventoryEntry(
            String origin,
            @Nullable String location,
            @Nullable String name,
            boolean hidden) {

        /**
         * Projects one binding.
         *
         * @param binding the binding
         * @return the projection
         */
        static InventoryEntry of(InputBinding binding) {
            return new InventoryEntry(
                    binding.origin().name(),
                    binding.location() == null ? null : binding.location().name(),
                    binding.name(),
                    binding.hidden());
        }

        /**
         * Projects every binding, in order.
         *
         * @param bindings the bindings
         * @return an immutable list of projections
         */
        static List<InventoryEntry> ofAll(List<InputBinding> bindings) {
            return bindings.stream().map(InventoryEntry::of).toList();
        }
    }

    /**
     * The outcome of one assembly: both forms, or the failure message.
     *
     * @param json    the JSON bytes, or {@code null} when assembly failed
     * @param yaml    the YAML bytes, or {@code null} when assembly failed
     * @param failure the failure message, or {@code null} when the document was rendered
     */
    private record Outcome(
            byte[] json, byte[] yaml, @Nullable String failure) {

        private static Outcome failed(Throwable failure) {
            String message = failure instanceof RestConfigurationException
                    ? failure.getMessage()
                    : failure.getClass().getName() + ": " + failure.getMessage();
            return new Outcome(null, null, String.valueOf(message));
        }
    }

    /** Identifies one operation of one application. */
    private record Key(String applicationName, String operationId) {}
}
