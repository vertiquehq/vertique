// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.profile.TagsProfileModule;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles the documents of synthetic publications for the disclosure unit proofs, in public or
 * protected mode, and returns both rendered forms.
 *
 * <p>It lives in the documentation package because the enabled document, the operation facts, the
 * assembly context, and the assembler are package-private. A document is enabled for the
 * publication's application, declared by the publication's declaring type, at the publication's
 * mount path, with the fixed {@link #INFO}, no configured server URL, and the access the caller
 * names: "public" and "protected" mode mean the document's access is {@code PUBLIC} or {@code
 * PROTECTED}, whatever the declaring type carries.
 *
 * <p>Every assembly context holds a profile registry with the built-in profiles and the {@code
 * tags-zx} profile of {@link TagsProfileModule}, so a publication whose operation names that profile
 * id resolves it. The bound schema source is the caller's: {@link #noSource()} binds none.
 */
final class DisclosureDocuments {

    /** The {@code info} of every document. */
    static final InfoConfig INFO = new InfoConfig("Disclosure", "1.0", null);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final ObjectMapper JSON = new ObjectMapper();

    private DisclosureDocuments() {}

    /**
     * Returns an assembly context that binds no schema source.
     *
     * @return the context
     */
    static AssemblyContext noSource() {
        return new AssemblyContext(Optional.empty(), TagsProfileModule.registry());
    }

    /**
     * Returns an assembly context that binds the given schema source.
     *
     * @param source the bound source, for example a fixture source or the canonical {@code
     *     AnnotationSchemaSource}
     * @return the context
     */
    static AssemblyContext withSource(OperationSchemaSource source) {
        return new AssemblyContext(Optional.of(Objects.requireNonNull(source, "source")), TagsProfileModule.registry());
    }

    /**
     * Returns the enabled document matching a built publication.
     *
     * @param built  the built publication
     * @param access the document's access
     * @return the enabled document
     */
    static EnabledDocuments.EnabledDocument document(Publications.Built built, ApiDocs.Access access) {
        MountPublication publication = built.publication();
        return new EnabledDocuments.EnabledDocument(
                Objects.requireNonNull(publication.applicationName(), "applicationName"),
                Objects.requireNonNull(publication.declaringType(), "declaringType"),
                Objects.requireNonNull(access, "access"),
                publication.mountPath(),
                ContractOrigin.GLOBAL,
                INFO);
    }

    /**
     * Converts the descriptor facts of a built publication into the per-operation facts the
     * assembler receives.
     *
     * @param built the built publication
     * @return the facts, keyed by operation id
     */
    static Map<String, OperationFacts> facts(Publications.Built built) {
        Map<String, OperationFacts> facts = new LinkedHashMap<>();
        for (String operationId : built.consumes().keySet()) {
            facts.put(
                    operationId,
                    new OperationFacts(
                            built.consumes().get(operationId),
                            built.namedFileParts().get(operationId)));
        }
        return facts;
    }

    /**
     * Assembles the document of a built publication.
     *
     * @param built   the built publication
     * @param access  the document's access
     * @param context the assembly context
     * @return both rendered forms
     * @throws RestConfigurationException when publication fails
     */
    static Rendering render(Publications.Built built, ApiDocs.Access access, AssemblyContext context) {
        PublishedDocument published =
                DocumentAssembler.assemble(document(built, access), built.publication(), facts(built), context);
        return new Rendering(published.json(), published.yaml());
    }

    /**
     * Assembles the document of a built publication in public mode.
     *
     * @param built   the built publication
     * @param context the assembly context
     * @return both rendered forms
     */
    static Rendering renderPublic(Publications.Built built, AssemblyContext context) {
        return render(built, ApiDocs.Access.PUBLIC, context);
    }

    /**
     * Assembles the document of a built publication in protected mode.
     *
     * @param built   the built publication
     * @param context the assembly context
     * @return both rendered forms
     */
    static Rendering renderProtected(Publications.Built built, AssemblyContext context) {
        return render(built, ApiDocs.Access.PROTECTED, context);
    }

    /**
     * Assembles the document of a built publication that must fail publication.
     *
     * @param built   the built publication
     * @param access  the document's access
     * @param context the assembly context
     * @return the failure
     */
    static RestConfigurationException failure(
            Publications.Built built, ApiDocs.Access access, AssemblyContext context) {
        return assertThrows(RestConfigurationException.class, () -> render(built, access, context));
    }

    /**
     * Both rendered forms of one document.
     *
     * @param json the JSON bytes
     * @param yaml the YAML bytes
     */
    record Rendering(byte[] json, byte[] yaml) {

        /**
         * Returns the JSON form as text.
         *
         * @return the UTF-8 decoded JSON bytes
         */
        String jsonText() {
            return new String(json, StandardCharsets.UTF_8);
        }

        /**
         * Returns the YAML form as text.
         *
         * @return the UTF-8 decoded YAML bytes
         */
        String yamlText() {
            return new String(yaml, StandardCharsets.UTF_8);
        }

        /**
         * Parses the JSON form.
         *
         * @return the document, members in written order
         */
        JsonObject document() {
            return new JsonObject(jsonText());
        }

        /**
         * Parses the JSON form into a Jackson tree.
         *
         * @return the tree
         */
        JsonNode jsonTree() {
            return read(JSON, json);
        }

        /**
         * Parses the YAML form into a Jackson tree; it equals {@link #jsonTree()} for every document
         * the assembler writes.
         *
         * @return the tree
         */
        JsonNode yamlTree() {
            return read(YAML, yaml);
        }

        /**
         * Reports whether either form contains a text, as bytes.
         *
         * @param text the text
         * @return {@code true} when the JSON or the YAML text contains it
         */
        boolean contains(String text) {
            return jsonText().contains(text) || yamlText().contains(text);
        }

        /**
         * Returns the root {@code x-vertique-validation} object.
         *
         * @return the object, or {@code null} when absent
         */
        @Nullable
        JsonObject rootValidation() {
            return document().getJsonObject("x-vertique-validation");
        }

        private static JsonNode read(ObjectMapper mapper, byte[] bytes) {
            try {
                return mapper.readTree(bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
