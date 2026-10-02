// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The publication sink of the documentation module. It asks for operation detail only for the
 * applications that have an enabled document, and hands each such mount's publication to the
 * {@link DocumentStore}.
 *
 * <p>Before that, it checks every publication it receives, whether or not the mount serves a
 * documented application and whether or not the mount is empty. The checks run in this order, and
 * the first that finds a violation fails the mount with one {@link RestConfigurationException}
 * listing all of that check's violations, one per line:
 *
 * <ol>
 *   <li>no operation id equals a reserved document operation id, {@code apidocs:<name>:json} or
 *       {@code apidocs:<name>:yaml} for an enabled document {@code <name>};
 *   <li>no {@code GET} or {@code HEAD} operation can answer a document URL,
 *       {@code <apidocs.path>/<name>/openapi.json} or {@code .yaml}, as decided by
 *       {@link DocumentRouteMatcher}; only a mount whose path without its trailing {@code *} is a
 *       prefix of the URL is tested, and violations are listed by URL, then method, then template;
 *   <li>a documented application whose selected request-validation strategy resolves operations
 *       from the mount's contract does not take that contract from the shared global location; a
 *       strategy id that names no registered strategy counts as one that does not.
 * </ol>
 *
 * <p>Only a mount that passes every check is published, so a refused mount never stores a document.
 * The checks keep no state between publications.
 *
 * <p>An application whose contract is its own, as the {@link RestApplications} view decides ({@link
 * ServedContractSource}), has that contract loaded as its document ({@link ServedContractLoader})
 * inside the same single flight, in place of the assembly; every other documented application's
 * document is assembled and, once assembled, logged as generated. Either way a later composition
 * compares its own snapshot and never loads or assembles.
 *
 * <p>The publication a sink receives must not be retained past the call. The sink therefore takes a
 * detached copy during {@link #mountBuilt}: every schema {@link JsonObject} is copied and the
 * operation descriptor is dropped. The copy shares only immutable records with the publication (the
 * input bindings, response shapes, policies, requirement sets and the body provenance manifest).
 * The stored document retains only the byte arrays, entity tags and the string snapshot.
 */
final class DocsPublicationSink implements OperationPublicationSink {

    private final EnabledDocuments documents;
    private final DocumentStore store;
    private final String prefix;
    private final Set<RequestValidationStrategy> strategies;
    private final RestApplications applications;
    private final AssemblyContext context;

    /**
     * Creates a sink with the default documentation prefix, no registered request-validation
     * strategy, and no declared application.
     *
     * @param documents the enabled documents
     * @param store the document store of the component
     * @param context the component's assembly inputs the assembler reads besides the publication
     */
    DocsPublicationSink(EnabledDocuments documents, DocumentStore store, AssemblyContext context) {
        this(documents, store, EnabledDocuments.DEFAULT_PATH, Set.of(), new RestApplications(List.of()), context);
    }

    /**
     * Creates the sink.
     *
     * @param documents the enabled documents
     * @param store the document store of the component
     * @param prefix the configured documentation prefix, without a trailing slash
     * @param strategies the registered request-validation strategies
     * @param applications the declared applications of the component
     * @param context the component's assembly inputs the assembler reads besides the publication
     */
    DocsPublicationSink(
            EnabledDocuments documents,
            DocumentStore store,
            String prefix,
            Set<RequestValidationStrategy> strategies,
            RestApplications applications,
            AssemblyContext context) {
        this.documents = documents;
        this.store = store;
        this.prefix = prefix;
        this.strategies = strategies;
        this.applications = applications;
        this.context = context;
    }

    /**
     * Reports whether the application has an enabled document.
     *
     * @param applicationName the application name, or {@code null} for a mount that serves no
     *     declared application
     * @return {@code true} exactly when the name is non-null and names an enabled document
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return applicationName != null && documents.byName(applicationName).isPresent();
    }

    /**
     * Checks the mount, then publishes it when it serves a documented application; every other mount
     * that passes the checks completes at once.
     *
     * @param publication the publication of the mount
     * @return a future that completes on the calling context when this composition's part of the
     *     publication is done, and fails with a {@link RestConfigurationException} when the mount uses
     *     a reserved operation id, has a route that can answer a document URL, or documents an
     *     application on the shared global contract, when the calling thread has no Vert.x context,
     *     when the application's own contract cannot be read or parsed or fails a check, or when the
     *     mount differs from the document already published
     */
    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        List<String> violations = reservedIdViolations(publication);
        if (violations.isEmpty()) {
            violations = collisionViolations(publication);
        }
        if (violations.isEmpty()) {
            violations = sharedContractViolations(publication);
        }
        if (!violations.isEmpty()) {
            return Future.failedFuture(new RestConfigurationException(String.join("\n", violations)));
        }
        String applicationName = publication.applicationName();
        if (applicationName == null) {
            return Future.succeededFuture();
        }
        EnabledDocuments.EnabledDocument document =
                documents.byName(applicationName).orElse(null);
        if (document == null) {
            return Future.succeededFuture();
        }
        Context caller = Vertx.currentContext();
        if (caller == null) {
            return Future.failedFuture(new RestConfigurationException("The mount of application '" + applicationName
                    + "' was built outside a Vert.x context, so its document cannot be published"));
        }
        Map<String, OperationFacts> facts = operationFacts(publication);
        MountPublication detached = detach(publication);
        Optional<RestApplications.Entry> served = ServedContractSource.served(applications, applicationName);
        if (served.isPresent()) {
            String servedPath = served.get().effectiveOpenapiPath();
            if (servedPath == null) {
                return Future.failedFuture(new RestConfigurationException("apidocs.documents." + applicationName
                        + ": application '" + applicationName + "' has no contract location"));
            }
            String setting = ServedContractSource.setting(served.get());
            for (OperationPublication operation : detached.operations()) {
                if (operation.detail() == null) {
                    return Future.failedFuture(new RestConfigurationException("The served contract of application '"
                            + applicationName + "' cannot be checked: operation '"
                            + ContractReferences.display(operation.operationId())
                            + "' was published without its detail"));
                }
            }
            List<RoutedOperation> routed = routedOperations(detached, facts);
            return store.publish(
                    applicationName,
                    caller,
                    DocumentStore.Source.SERVED_CONTRACT,
                    () -> ServedContractLoader.load(
                            document,
                            servedPath,
                            setting,
                            detached,
                            facts,
                            routed,
                            caller.owner().fileSystem(),
                            context.warnings()),
                    () -> SnapshotRenderer.render(detached));
        }
        return store.publish(
                applicationName,
                caller,
                DocumentStore.Source.GENERATED,
                () -> {
                    PublishedDocument assembled = DocumentAssembler.assemble(document, detached, facts, context);
                    context.warnings()
                            .infoOnce(
                                    DocumentWarnings.SOURCE,
                                    applicationName,
                                    DocumentWarnings.generatedSource(applicationName));
                    return assembled;
                },
                () -> SnapshotRenderer.render(detached));
    }

    /**
     * Lists the mount's routed operations as the checks of a served contract see them: each with its
     * id, method, rendered mount-relative path, whether it is hidden, and its hidden inputs.
     */
    private static List<RoutedOperation> routedOperations(
            MountPublication publication, Map<String, OperationFacts> facts) {
        List<RoutedOperation> routed = new ArrayList<>(publication.operations().size());
        for (OperationPublication operation : publication.operations()) {
            Set<InputKey> hiddenInputs = new LinkedHashSet<>();
            OperationDetail detail = operation.detail();
            if (detail != null) {
                for (InputBinding input : detail.inputs()) {
                    if (input.hidden() && input.location() != null && input.name() != null) {
                        hiddenInputs.add(new InputKey(input.location(), input.name()));
                    }
                }
            }
            routed.add(new RoutedOperation(
                    operation.operationId(),
                    operation.httpMethod(),
                    RenderedPaths.render(operation.jaxRsPathTemplate()),
                    HiddenOperations.hidden(facts.get(operation.operationId())),
                    Collections.unmodifiableSet(hiddenInputs)));
        }
        return Collections.unmodifiableList(routed);
    }

    /** Lists, sorted, each operation whose id is reserved for an enabled document. */
    private List<String> reservedIdViolations(MountPublication publication) {
        List<String> violations = new ArrayList<>();
        for (OperationPublication operation : publication.operations()) {
            String id = operation.operationId();
            for (EnabledDocuments.EnabledDocument document : documents.all()) {
                String name = document.name();
                if (("apidocs:" + name + ":json").equals(id) || ("apidocs:" + name + ":yaml").equals(id)) {
                    violations.add("Operation '" + id + "' (" + operation.httpMethod() + " "
                            + operation.jaxRsPathTemplate() + ") on mount '" + publication.mountPath()
                            + "' uses an operation id reserved for the document of application '" + name
                            + "' (declared by " + document.declaringType().getName() + ", apidocs.documents."
                            + name + ")");
                }
            }
        }
        violations.sort(null);
        return violations;
    }

    /**
     * Lists each route of the mount that can answer a document URL, ordered by URL, then method, then
     * template.
     */
    private List<String> collisionViolations(MountPublication publication) {
        String mountPath = publication.mountPath();
        String mountPoint = mountPath.endsWith("*") ? mountPath.substring(0, mountPath.length() - 1) : mountPath;
        List<Collision> collisions = new ArrayList<>();
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            for (String form : List.of("json", "yaml")) {
                String url = prefix + "/" + document.name() + "/openapi." + form;
                if (mountPoint.isEmpty() || !url.startsWith(mountPoint)) {
                    continue;
                }
                for (OperationPublication operation : publication.operations()) {
                    if (DocumentRouteMatcher.canAnswer(mountPath, operation, url)) {
                        collisions.add(new Collision(url, document, operation));
                    }
                }
            }
        }
        collisions.sort(Comparator.comparing(Collision::url)
                .thenComparing(collision -> collision.operation().httpMethod())
                .thenComparing(collision -> collision.operation().jaxRsPathTemplate()));
        List<String> violations = new ArrayList<>(collisions.size());
        for (Collision collision : collisions) {
            OperationPublication operation = collision.operation();
            EnabledDocuments.EnabledDocument document = collision.document();
            violations.add("Route " + operation.httpMethod() + " " + operation.jaxRsPathTemplate() + " (operation '"
                    + operation.operationId() + "') on mount '" + mountPath + "' can answer document URL '"
                    + collision.url() + "' of application '" + document.name() + "' (declared by "
                    + document.declaringType().getName() + "); choose an apidocs.path no such route can match,"
                    + " or narrow or remove the route; a route that matches every path, such as"
                    + " GET /{path: .*}, must be narrowed or removed");
        }
        return violations;
    }

    /**
     * Lists the refusal of a documented application whose selected strategy resolves operations from
     * the mount's contract while that contract is the shared global one.
     */
    private List<String> sharedContractViolations(MountPublication publication) {
        String applicationName = publication.applicationName();
        if (applicationName == null) {
            return List.of();
        }
        EnabledDocuments.EnabledDocument document =
                documents.byName(applicationName).orElse(null);
        if (document == null) {
            return List.of();
        }
        String strategyId = publication.strategyId();
        boolean resolvesFromContract = strategies.stream()
                .filter(strategy -> strategy.id().equals(strategyId))
                .findFirst()
                .map(RequestValidationStrategy::resolvesOperationsFromMountContract)
                .orElse(false);
        if (!resolvesFromContract) {
            return List.of();
        }
        boolean sharedContract = applications
                .byName(applicationName)
                .map(entry -> entry.contractOrigin() == RestApplications.ContractOrigin.GLOBAL)
                .orElse(false);
        if (!sharedContract) {
            return List.of();
        }
        return List.of("apidocs.documents." + applicationName + ": application '" + applicationName
                + "' (declared by " + document.declaringType().getName() + ") at '" + publication.mountPath()
                + "' uses request-validation strategy '" + strategyId
                + "', which resolves operations from the shared global contract; that contract file already is"
                + " the mount's OpenAPI document, so no document is generated for it: serve that file behind an"
                + " access check at least as strict as the most restricted mount it describes");
    }

    /**
     * One route that can answer a document URL.
     *
     * @param url the document URL
     * @param document the document the URL belongs to
     * @param operation the operation whose route can answer it
     */
    private record Collision(String url, EnabledDocuments.EnabledDocument document, OperationPublication operation) {}

    /** Takes the descriptor facts of every operation that has detail, before the publication is detached. */
    static Map<String, OperationFacts> operationFacts(MountPublication publication) {
        Map<String, OperationFacts> facts = new LinkedHashMap<>();
        for (OperationPublication operation : publication.operations()) {
            OperationDetail detail = operation.detail();
            if (detail == null || detail.descriptor() == null) {
                continue;
            }
            JaxRsOperationDescriptor descriptor = detail.descriptor();
            List<String> consumes = descriptor.consumes() == null ? List.of() : descriptor.consumes();
            List<String> namedFileParts = new ArrayList<>();
            for (FilePartDescriptor part : descriptor.fileParts()) {
                if (part.partName() != null) {
                    namedFileParts.add(part.partName());
                }
            }
            Map<InputKey, Class<?>> elementTypes = new LinkedHashMap<>();
            for (ParamDescriptor parameter : descriptor.parameters()) {
                if (parameter.componentType() != null && parameter.location() != null && parameter.name() != null) {
                    elementTypes.put(new InputKey(parameter.location(), parameter.name()), parameter.componentType());
                }
            }
            facts.put(
                    operation.operationId(),
                    new OperationFacts(
                            consumes,
                            namedFileParts,
                            descriptor.methodAnnotations(),
                            descriptor.classAnnotations(),
                            elementTypes));
        }
        return Collections.unmodifiableMap(facts);
    }

    /** Copies a publication without retaining descriptors, copying every captured schema so the copy is independent. */
    static MountPublication detach(MountPublication publication) {
        List<OperationPublication> operations = publication.operations().stream()
                .map(DocsPublicationSink::detach)
                .toList();
        return new MountPublication(
                publication.mountPath(),
                publication.mountId(),
                publication.applicationName(),
                publication.declaringType(),
                publication.strategyId(),
                operations);
    }

    private static OperationPublication detach(OperationPublication operation) {
        OperationDetail detail = operation.detail();
        return new OperationPublication(
                operation.operationId(),
                operation.httpMethod(),
                operation.jaxRsPathTemplate(),
                operation.vertxRouteValue(),
                operation.vertxRouteIsRegex(),
                operation.effectivePolicy(),
                operation.securityRequirementSets(),
                operation.requiresAction(),
                detail == null ? null : detach(detail));
    }

    private static OperationDetail detach(OperationDetail detail) {
        return new OperationDetail(
                null,
                detail.profileId(),
                detach(detail.schemas()),
                detail.gateInstalled(),
                detail.inputs(),
                detail.response());
    }

    private static CapturedSchemas detach(CapturedSchemas schemas) {
        JsonObject body = schemas.body();
        Map<InputKey, JsonObject> parameters = new HashMap<>();
        schemas.parameters().forEach((key, schema) -> parameters.put(key, schema.copy()));
        return new CapturedSchemas(body == null ? null : body.copy(), schemas.bodyProvenance(), parameters);
    }
}
