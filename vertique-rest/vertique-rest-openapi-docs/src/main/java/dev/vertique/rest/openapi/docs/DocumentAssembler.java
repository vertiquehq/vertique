// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
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
 * components} (only when it holds a schema, with its keys in natural order), and {@code
 * x-vertique-validation}, which names the {@code java.util.regex} pattern dialect. The single server
 * is the configured server URL of the document, or else the mount path without its trailing {@code
 * /*} ({@code /} for the root mount); it is never inferred from a request. Paths are rendered by
 * {@link RenderedPaths} from each operation's mount-relative JAX-RS template, in natural order, and
 * each operation is keyed by its lowercase method and carries its runtime operation id.
 *
 * <p>Inputs are assembled by {@link InputAssembler} in two phases over the whole document, both
 * visiting the operations in the order the document lists them (path keys in natural order, then
 * methods in Path Item order): every operation's inputs are first checked, so the first hidden path
 * parameter, duplicate input, unverified request body, unresolved redaction, or refused construct of
 * the document fails before anything is published; then every operation is published, each checked
 * schema once. Inputs the inventory flags hidden are left out before any check reads them, and a
 * {@link DisclosureTally} created for the assembly records whether any was and whether a reserved
 * name was removed from a published request body.
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

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DocumentAssembler() {}

    /**
     * Assembles the document of one application.
     *
     * @param document the enabled document of the application
     * @param publication the detached publication of the application's mount
     * @param facts the per-operation descriptor facts, keyed by operation id
     * @param context the per-application inputs besides the publication
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
                RenderedPaths.pathItems(subject, publication.operations());
        SchemaEmbedder embedder = new SchemaEmbedder(subject);
        DisclosureTally tally = new DisclosureTally();

        List<PlannedOperation> planned = new ArrayList<>();
        for (Map.Entry<String, List<OperationPublication>> item : pathItems.entrySet()) {
            for (OperationPublication operation : item.getValue()) {
                planned.add(new PlannedOperation(
                        item.getKey(),
                        RenderedPaths.methodKey(operation),
                        InputAssembler.check(
                                subject, embedder, operation, facts.get(operation.operationId()), context, tally)));
            }
        }

        ObjectNode paths = NODES.objectNode();
        for (PlannedOperation operation : planned) {
            ObjectNode pathItem = paths.has(operation.path())
                    ? (ObjectNode) paths.get(operation.path())
                    : paths.putObject(operation.path());
            pathItem.set(operation.method(), operation.plan().publish(embedder));
        }

        ObjectNode root = NODES.objectNode();
        root.put("openapi", DocumentWriter.OPENAPI_VERSION);
        root.set("info", DocumentWriter.info(document.info()));
        root.put("jsonSchemaDialect", JSON_SCHEMA_DIALECT);
        root.putArray("servers").addObject().put("url", serverUrl(document, publication));
        root.set("paths", paths);
        SortedMap<String, JsonNode> schemas = embedder.components();
        if (!schemas.isEmpty()) {
            ObjectNode componentSchemas = root.putObject("components").putObject("schemas");
            schemas.forEach(componentSchemas::set);
        }
        root.putObject("x-vertique-validation").put("patternDialect", PATTERN_DIALECT);
        return DocumentWriter.write(root, SnapshotRenderer.render(publication));
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
     */
    private record PlannedOperation(String path, String method, InputAssembler.Plan plan) {}
}
