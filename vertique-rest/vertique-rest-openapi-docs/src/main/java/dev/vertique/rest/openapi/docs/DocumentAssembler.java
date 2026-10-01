// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Assembles one application's document from its detached mount publication.
 *
 * <p>The root members are written in this order: {@code openapi}, {@code info}, {@code
 * jsonSchemaDialect} (JSON Schema draft 2020-12), {@code servers}, {@code paths}, {@code
 * components} (only when it holds a schema, with its keys in natural order), {@code tags} (only when
 * a published operation declares a tag; see {@link RootTags}), and {@code
 * x-vertique-validation}, which names the {@code java.util.regex} pattern dialect and, in a
 * protected document only, the validation authority (see {@link ValidationDisclosure}). The single server
 * is the configured server URL of the document, or else the mount path without its trailing {@code
 * /*} ({@code /} for the root mount); it is never inferred from a request. Paths are rendered by
 * {@link RenderedPaths} from each operation's mount-relative JAX-RS template, in natural order, and
 * each operation is keyed by its lowercase method and carries its runtime operation id.
 *
 * <p>The {@code info} is the configured one, else the complete {@code info} of the declaring
 * interface's annotation ({@link AnnotatedInfo}).
 *
 * <p>Hidden operations ({@link HiddenOperations}) are removed first, before paths are rendered and
 * before any input is checked: nothing of a hidden operation is rendered, verified, redacted,
 * checked, or recorded in the root flags, and a path item whose operations are all hidden is not
 * emitted. The snapshot still covers the whole publication.
 *
 * <p>Inputs are assembled by {@link InputAssembler} in two phases over the whole document, both
 * visiting the operations in the order the document lists them (path keys in natural order, then
 * methods in Path Item order): every operation is first checked, so the first failure of the document
 * in that order fails it before anything is published. Each operation's inputs are checked for a
 * hidden path parameter, a duplicate input, an unverified request body, a refused construct, a request
 * body describing a hidden member, or an unresolved redaction; then its annotations are checked
 * against how the runtime binds it ({@link MetadataAgreement}: the operation id, then the request
 * body, the parameters, and the form fields, each input for a contradiction, then for an unresolved
 * reference or a malformed example). Then the tag declarations of every operation are merged in the
 * same order, so a conflicting tag fails before anything is published; then every operation is
 * published, each checked schema once, with its documentation metadata ({@link OperationMetadata}).
 * Inputs the inventory flags hidden are left
 * out before any check reads them, and a {@link DisclosureTally} created for the assembly records
 * whether any was and whether a reserved name was removed from a published request body. The
 * input-direction schema generators that inspect request bodies are likewise created per assembly
 * ({@link InputGenerators}).
 *
 * <p>The warnings of the assembly are collected in document order and logged on the component's
 * {@link DocumentWarnings} only once the document is fully assembled and written, so a document that
 * fails publication warns about nothing: first the extensions of an annotated {@code info} not
 * published for lacking the {@code x-} prefix, once per document; then, per operation, the schema
 * members of its inputs the document does not publish, in one warning, and each requirement on an
 * input whose enforcement the runtime leaves unknown.
 *
 * <p>Failures are thrown as {@link dev.vertique.rest.core.RestConfigurationException} so that
 * publication fails startup; each message starts with the {@linkplain #subject subject} naming the
 * application, its declaring interface, and its mount.
 */
final class DocumentAssembler {

    /** The JSON Schema dialect every document declares at its root. */
    static final String JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** The regular-expression dialect of every pattern a document publishes. */
    static final String PATTERN_DIALECT = "java.util.regex";

    /** The warning kind of the unpublished extensions of an annotated {@code info}. */
    private static final String INFO_EXTENSIONS = "info-extensions";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DocumentAssembler() {}

    /**
     * Assembles the document of one application.
     *
     * @param document the enabled document of the application
     * @param publication the detached publication of the application's mount
     * @param facts the per-operation descriptor facts, keyed by operation id
     * @param context the component's assembly inputs besides the publication
     * @return the published document
     * @throws dev.vertique.rest.core.RestConfigurationException when the publication cannot be
     *     described by one document
     */
    static PublishedDocument assemble(
            EnabledDocuments.EnabledDocument document,
            MountPublication publication,
            Map<String, OperationFacts> facts,
            AssemblyContext context) {
        Objects.requireNonNull(context, "context");
        String subject = subject(document, publication);
        SortedMap<String, List<OperationPublication>> pathItems =
                RenderedPaths.pathItems(subject, HiddenOperations.visible(publication.operations(), facts));
        SchemaEmbedder embedder = new SchemaEmbedder(subject);
        DisclosureTally tally = new DisclosureTally();
        InputGenerators generators = new InputGenerators(context.profiles());
        ValidationDisclosure disclosure = new ValidationDisclosure(document.access(), publication.strategyId());
        PendingWarnings warnings = new PendingWarnings(document.name());
        AnnotatedInfo annotatedInfo = document.annotatedInfo();
        if (annotatedInfo != null) {
            warnUnpublishedExtensions(document, annotatedInfo, warnings);
        }
        MetadataAgreement agreement =
                new MetadataAgreement(subject, document.name(), publication.mountPath(), warnings);

        List<PlannedOperation> planned = new ArrayList<>();
        for (Map.Entry<String, List<OperationPublication>> item : pathItems.entrySet()) {
            for (OperationPublication operation : item.getValue()) {
                OperationFacts operationFacts = facts.get(operation.operationId());
                planned.add(new PlannedOperation(
                        item.getKey(),
                        RenderedPaths.methodKey(operation),
                        InputAssembler.check(
                                subject,
                                embedder,
                                operation,
                                operationFacts,
                                context,
                                tally,
                                generators,
                                disclosure.marksInputs(),
                                agreement),
                        OperationMetadata.of(operationFacts)));
            }
        }

        RootTags rootTags = new RootTags(subject);
        for (PlannedOperation operation : planned) {
            rootTags.add(operation.plan().operationId(), operation.metadata());
        }

        ObjectNode paths = NODES.objectNode();
        for (PlannedOperation operation : planned) {
            ObjectNode pathItem = paths.has(operation.path())
                    ? (ObjectNode) paths.get(operation.path())
                    : paths.putObject(operation.path());
            pathItem.set(operation.method(), operation.plan().publish(embedder, operation.metadata()));
        }

        ObjectNode root = NODES.objectNode();
        root.put("openapi", DocumentWriter.OPENAPI_VERSION);
        root.set(
                "info",
                annotatedInfo != null ? DocumentWriter.info(annotatedInfo) : DocumentWriter.info(document.info()));
        root.put("jsonSchemaDialect", JSON_SCHEMA_DIALECT);
        root.putArray("servers").addObject().put("url", serverUrl(document, publication));
        root.set("paths", paths);
        SortedMap<String, JsonNode> schemas = embedder.components();
        if (!schemas.isEmpty()) {
            ObjectNode componentSchemas = root.putObject("components").putObject("schemas");
            schemas.forEach(componentSchemas::set);
        }
        ArrayNode tags = rootTags.render();
        if (tags != null) {
            root.set("tags", tags);
        }
        root.set(ValidationDisclosure.MEMBER, disclosure.root(context, tally));
        PublishedDocument written = DocumentWriter.write(root, SnapshotRenderer.render(publication));
        warnings.emit(context.warnings());
        return written;
    }

    /**
     * Holds back the warning, logged once per document, about the extensions of the annotated {@code
     * info} that are not published because their names do not start with {@code x-}. The warning names
     * the keys only, never a property value.
     */
    private static void warnUnpublishedExtensions(
            EnabledDocuments.EnabledDocument document, AnnotatedInfo info, PendingWarnings warnings) {
        if (info.unpublishedKeys().isEmpty()) {
            return;
        }
        warnings.add(
                INFO_EXTENSIONS,
                "apidocs.documents." + document.name() + ": application '" + document.name() + "' (declared by "
                        + document.declaringType().getName()
                        + ") has @OpenAPIDefinition.info extensions whose names do not start with 'x-', which"
                        + " are not published: " + String.join(", ", info.unpublishedKeys()));
    }

    /**
     * Returns the subject every assembly failure message starts with.
     *
     * @param document the enabled document of the application
     * @param publication the publication of the application's mount
     * @return {@code Application '<name>' (declared by <binary name>) at mount '<mount path>'}, the
     *     mount path as registered
     */
    static String subject(EnabledDocuments.EnabledDocument document, MountPublication publication) {
        return "Application '" + document.name() + "' (declared by "
                + document.declaringType().getName() + ") at mount '" + publication.mountPath() + "'";
    }

    /**
     * Returns the URL of the document's single server: the configured server URL when present,
     * else the mount path without its trailing wildcard, and {@code /} when nothing remains.
     */
    private static String serverUrl(EnabledDocuments.EnabledDocument document, MountPublication publication) {
        if (document.serverUrl() != null) {
            return document.serverUrl();
        }
        String mountPath = publication.mountPath();
        if (mountPath.endsWith("/*")) {
            mountPath = mountPath.substring(0, mountPath.length() - 2);
        }
        return mountPath.isEmpty() ? "/" : mountPath;
    }

    /**
     * One operation of the document, checked and waiting to be published.
     *
     * @param path the rendered path key
     * @param method the lowercase method key
     * @param plan the checked plan of its Operation Object
     * @param metadata the operation's documentation metadata
     */
    private record PlannedOperation(String path, String method, InputAssembler.Plan plan, OperationMetadata metadata) {}
}
