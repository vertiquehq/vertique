// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads an application's own contract as its document, in one blocking step.
 *
 * <p>The step runs on a worker thread, inside the document store's single flight, so it runs once per
 * component and application. It chooses the format by the location's extension, the text after the
 * last {@code .}, compared ignoring letter case: {@code json} is parsed as JSON, {@code yaml} and
 * {@code yml} as YAML; any other extension, or none, is refused before anything is read. It then
 * reads the location, unchanged, with the Vert.x file system's blocking read (an absolute path as is;
 * a relative path from the working directory when that file exists, otherwise as a classpath
 * resource), parses it, checks it with {@link ServedContractChecks} against the mount's routed
 * operations, and renders both forms with {@link ServedContractRenderer}. It never fetches anything:
 * no reference leaves the document.
 *
 * <p>A location that cannot be read, or whose content does not parse, fails with a {@link
 * RestConfigurationException} naming the application and the failure, with no cause attached: neither
 * the message nor any cause carries the parser's or the file system's text, or any byte of the file.
 *
 * <p>Only once the step succeeds are its notices and warnings logged on the component's {@link
 * DocumentWarnings}, each at most once per document: the source notice naming the resolved location;
 * the warning that a working-directory file shadows a classpath resource of the same name; the
 * warning that the contract's first server is not the mount path (the document is served unchanged);
 * and, for a public document, the warning listing the mount's operations that restrict callers. A
 * failed step logs none of them. Last, one {@code DEBUG} line names the document, its mount, and the
 * time the step took, never any content.
 */
final class ServedContractLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ServedContractLoader.class);

    /** The extensions the loader accepts, as a refusal lists them. */
    private static final String SUPPORTED_EXTENSIONS = ".json, .yaml, or .yml";

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ServedContractLoader() {}

    /**
     * Loads, checks, and renders a served contract.
     *
     * @param document the enabled document of the application
     * @param path the application's effective contract location, unchanged
     * @param setting the setting the location comes from, as a failure names it
     * @param publication the detached publication of the application's mount
     * @param facts the per-operation descriptor facts, keyed by operation id
     * @param routed the mount's routed operations, hidden ones included
     * @param fileSystem the file system that reads the location
     * @param warnings the documentation module's warnings of the component
     * @return the frozen document
     * @throws RestConfigurationException when the location has an unsupported extension, cannot be
     *     read or parsed, or the contract fails a check
     */
    static PublishedDocument load(
            EnabledDocuments.EnabledDocument document,
            String path,
            String setting,
            MountPublication publication,
            Map<String, OperationFacts> facts,
            List<RoutedOperation> routed,
            FileSystem fileSystem,
            DocumentWarnings warnings) {
        long start = System.nanoTime();
        String name = document.name();
        String subject = "apidocs.documents." + name + ": the contract of application '" + name + "' (" + setting + ")";
        boolean yaml = yamlFormat(path, subject);

        Buffer content;
        try {
            content = fileSystem.readFileBlocking(path);
        } catch (RuntimeException unreadable) {
            // The file system's message carries the location and, for a classpath resource, the
            // cache copy's path; it is never chained.
            throw new RestConfigurationException(subject + " cannot be read");
        }
        ServedContractSource.Resolution resolution = ServedContractSource.resolve(path);

        JsonNode contract = parse(content, yaml, subject);
        String mountPath = normalizedMountPath(publication.mountPath());
        ServedContractChecks.check(name, contract, mountPath, routed);

        PendingWarnings pending = new PendingWarnings(name);
        pending.notice(DocumentWarnings.SOURCE, DocumentWarnings.servedSource(name, resolution.location()));
        if (resolution.shadowsClasspath()) {
            pending.add(DocumentWarnings.CONTRACT_SHADOWED, DocumentWarnings.contractShadowed(name, path));
        }
        if (!firstServerIsMount(contract, mountPath)) {
            pending.add(DocumentWarnings.CONTRACT_SERVERS, DocumentWarnings.contractServers(name, mountPath));
        }
        List<OperationPublication> visible = HiddenOperations.visible(publication.operations(), facts);
        PublicRestrictionWarning.addServed(document, publication.mountPath(), visible, pending);

        PublishedDocument rendered = ServedContractRenderer.render(contract, SnapshotRenderer.render(publication));
        pending.emit(warnings);
        LOG.debug(
                "Loaded the served contract of apidocs.documents.{} at mount '{}' in {} ms",
                name,
                publication.mountPath(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return rendered;
    }

    /**
     * Chooses the format by the location's extension.
     *
     * @return {@code true} for YAML, {@code false} for JSON
     * @throws RestConfigurationException naming the location and the supported extensions when the
     *     extension is neither
     */
    private static boolean yamlFormat(String path, String subject) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "json" -> false;
            case "yaml", "yml" -> true;
            default ->
                throw new RestConfigurationException(subject + " at '" + path
                        + "' has an unsupported extension; a contract location ends with " + SUPPORTED_EXTENSIONS);
        };
    }

    /** Parses the content in the chosen format, failing with no cause and no parser text. */
    private static JsonNode parse(Buffer content, boolean yaml, String subject) {
        try {
            return (yaml ? YAML : JSON).readTree(content.getBytes());
        } catch (IOException | RuntimeException unparsable) {
            // The parser's message quotes the content; it is never chained.
            throw new RestConfigurationException(subject + (yaml ? " is not valid YAML" : " is not valid JSON"));
        }
    }

    /** Returns the mount path without its trailing {@code /*}, and {@code /} for the root mount. */
    private static String normalizedMountPath(String mountPath) {
        String normalized = mountPath.endsWith("/*") ? mountPath.substring(0, mountPath.length() - 2) : mountPath;
        return normalized.isEmpty() ? "/" : normalized;
    }

    /** Whether the contract's first server's {@code url} is exactly the mount path. */
    private static boolean firstServerIsMount(JsonNode contract, String mountPath) {
        JsonNode url = contract.path("servers").path(0).path("url");
        return url.isTextual() && url.textValue().equals(mountPath);
    }
}
