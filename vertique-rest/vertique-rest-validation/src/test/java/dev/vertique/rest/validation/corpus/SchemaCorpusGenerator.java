// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Regenerates the golden schema corpus for the frozen {@link SchemaCorpus#FIXTURES fixture set}.
 *
 * <p>This is a plain helper, not a test. It asserts nothing, and its name matches neither the
 * surefire nor the failsafe include patterns, so a normal build never executes it. It exists so that
 * every corpus directory is produced by exactly one piece of code: a golden file written by hand, or
 * by a second generation path, is not evidence.
 *
 * <p>Three corpora are produced from the same fixture list:
 *
 * <ul>
 *   <li><strong>legacy</strong> — {@link #writeCorpus(Path)}, which generates through
 *       {@code AnnotationJsonSchemaGenerator.withVictoolsDefaults()}. Run this <em>before</em> any
 *       generation change; it is the pre-change baseline every delta is measured against.
 *   <li><strong>profiled</strong> — {@link #writeCorpus(Path, JsonMapperProfile)} once per built-in
 *       profile, which generates through {@code AnnotationJsonSchemaGenerator.forInputProfile}.
 * </ul>
 *
 * <p><strong>On-disk form.</strong> A golden file holds the generator's canonical document — compact,
 * with object keys in canonical order — followed by exactly one line feed. The canonical document is
 * emitted through a plain Jackson writer, so a consumer holding the schema seam's {@code JsonNode}
 * reproduces the file's exact bytes with {@link #render(JsonNode)} and compares them directly against
 * {@code Files.readString(...)}.
 *
 * <p>From a command line the helper runs off the module's test classpath:
 *
 * <pre>{@code
 * java -cp <test-classpath> dev.vertique.rest.validation.corpus.SchemaCorpusGenerator \
 *     src/test/resources/schema-corpus/legacy
 * java -cp <test-classpath> dev.vertique.rest.validation.corpus.SchemaCorpusGenerator \
 *     src/test/resources/schema-corpus/system system
 * }</pre>
 */
public final class SchemaCorpusGenerator {

    /** Test-resource directory holding one subdirectory per corpus. */
    public static final String CORPUS_ROOT = "schema-corpus";

    /** Subdirectory holding the pre-change, profile-free corpus. */
    public static final String LEGACY_DIRECTORY = "legacy";

    /** The plain writer every corpus document is emitted through; matches the generator's own. */
    private static final ObjectWriter WRITER = new ObjectMapper().writer();

    private SchemaCorpusGenerator() {}

    /**
     * Generates the legacy (profile-free) canonical document for one fixture.
     *
     * @param fixture the corpus subject
     * @return the canonical document, without a trailing line feed
     */
    public static String document(CorpusFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        return AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(fixture.generatedType());
    }

    /**
     * Generates the canonical document for one fixture under a profile's input direction.
     *
     * @param fixture the corpus subject
     * @param profile the profile whose mapper and input-applicable overrides drive generation
     * @return the canonical document, without a trailing line feed
     */
    public static String document(CorpusFixture fixture, JsonMapperProfile profile) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(profile, "profile");
        return AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(fixture.generatedType());
    }

    /**
     * Regenerates the legacy corpus: one file per fixture, in fixture order, under {@code directory}.
     *
     * @param directory the corpus directory; created when absent
     * @return the written files, in fixture order
     * @throws IOException if a file cannot be written
     */
    public static List<Path> writeCorpus(Path directory) throws IOException {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        return write(directory, generator);
    }

    /**
     * Regenerates a profiled corpus: one file per fixture, in fixture order, under {@code directory}.
     *
     * @param directory the corpus directory; created when absent
     * @param profile   the profile whose mapper and input-applicable overrides drive generation
     * @return the written files, in fixture order
     * @throws IOException if a file cannot be written
     */
    public static List<Path> writeCorpus(Path directory, JsonMapperProfile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        return write(directory, AnnotationJsonSchemaGenerator.forInputProfile(profile));
    }

    /**
     * Renders a document node in the corpus's on-disk form, so a consumer can compare a generated
     * document against a golden file byte for byte.
     *
     * @param document the document node, typically the schema seam's own
     * @return the compact JSON text followed by exactly one line feed
     */
    public static String render(JsonNode document) {
        Objects.requireNonNull(document, "document");
        try {
            return WRITER.writeValueAsString(document) + "\n";
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("a generated JSON Schema document could not be re-serialized", unwritable);
        }
    }

    /**
     * Resolves a fixture's golden file within a corpus directory.
     *
     * @param directory the corpus directory
     * @param fixture   the corpus subject
     * @return the golden file path
     */
    public static Path fileOf(Path directory, CorpusFixture fixture) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(fixture, "fixture");
        return directory.resolve(fixture.fileName());
    }

    /**
     * Writes one document per fixture, in {@link SchemaCorpus#FIXTURES} order, through one generator.
     *
     * @param directory the corpus directory; created when absent
     * @param generator the configured generator
     * @return the written files, in fixture order
     * @throws IOException if a file cannot be written
     */
    private static List<Path> write(Path directory, AnnotationJsonSchemaGenerator generator) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Files.createDirectories(directory);

        List<Path> written = new ArrayList<>(SchemaCorpus.FIXTURES.size());
        for (CorpusFixture fixture : SchemaCorpus.FIXTURES) {
            Path file = fileOf(directory, fixture);
            Files.writeString(
                    file, generator.generateCanonical(fixture.generatedType()) + "\n", StandardCharsets.UTF_8);
            written.add(file);
        }
        return written;
    }

    /**
     * Command-line entry point: regenerates one corpus directory.
     *
     * @param args {@code <directory> [profile-id]}; with no profile id the legacy corpus is produced
     * @throws IOException if a file cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: SchemaCorpusGenerator <directory> [profile-id]");
        }

        Path directory = Path.of(args[0]);
        List<Path> written =
                args.length == 1 ? writeCorpus(directory) : writeCorpus(directory, profile(JsonProfileId.of(args[1])));

        written.forEach(file -> System.out.println(file.toAbsolutePath()));
    }

    /**
     * Resolves a built-in profile from a registry holding no application profiles.
     *
     * @param id the profile id
     * @return the resolved profile
     */
    private static JsonMapperProfile profile(JsonProfileId id) {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(id);
    }
}
