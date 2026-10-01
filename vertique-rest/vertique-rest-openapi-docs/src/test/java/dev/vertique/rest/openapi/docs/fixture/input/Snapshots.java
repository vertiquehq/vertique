// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Deep copies of schema objects, taken before a step that must not write to them, and the check
 * that each object still deep-equals its copy afterwards.
 *
 * <pre>{@code
 * Snapshots.Snapshot before = Snapshots.of(built.publication());
 * assemble(built);
 * before.assertUnchanged();
 * }</pre>
 */
public final class Snapshots {

    private Snapshots() {}

    /**
     * Copies every captured schema of a publication: each operation's body schema and each captured
     * parameter schema.
     *
     * @param publication the publication whose captured schemas are copied
     * @return the snapshot, holding each schema object by reference beside its copy
     * @throws AssertionError if the publication captures no schema at all, so the later check
     *     cannot pass vacuously
     */
    public static Snapshot of(MountPublication publication) {
        List<Entry> entries = new ArrayList<>();
        for (OperationPublication operation : publication.operations()) {
            OperationDetail detail = operation.detail();
            if (detail == null) {
                continue;
            }
            CapturedSchemas schemas = detail.schemas();
            if (schemas.body() != null) {
                entries.add(new Entry(
                        operation.operationId() + " body",
                        schemas.body(),
                        schemas.body().copy()));
            }
            schemas.parameters()
                    .forEach((key, schema) -> entries.add(new Entry(
                            operation.operationId() + " " + key.location() + " '" + key.name() + "'",
                            schema,
                            schema.copy())));
        }
        assertFalse(entries.isEmpty(), "the publication captures no schema to compare");
        return new Snapshot(List.copyOf(entries));
    }

    /**
     * Deep-copies each object.
     *
     * @param objects the objects to copy
     * @return the copies, in the given order
     */
    public static List<JsonObject> deepCopyAll(Collection<JsonObject> objects) {
        return objects.stream().map(JsonObject::copy).toList();
    }

    /**
     * Asserts that every object deep-equals the copy at the same position.
     *
     * @param originals the objects as they are now
     * @param copies    the copies taken earlier, by {@link #deepCopyAll}
     */
    public static void assertUnchanged(List<JsonObject> originals, List<JsonObject> copies) {
        assertEquals(copies.size(), originals.size(), "object count");
        for (int index = 0; index < originals.size(); index++) {
            assertEquals(copies.get(index), originals.get(index), "object " + index + " changed");
        }
    }

    /**
     * The captured schemas of one publication, each beside the deep copy taken from it.
     *
     * @param entries one entry per captured schema
     */
    public record Snapshot(List<Entry> entries) {

        /** Asserts that every captured schema still deep-equals its copy, naming the first that does not. */
        public void assertUnchanged() {
            for (Entry entry : entries) {
                assertEquals(entry.copy(), entry.live(), "the captured schema of " + entry.input() + " changed");
            }
        }
    }

    /**
     * One captured schema and its copy.
     *
     * @param input names the operation and input, for example {@code "getItem QUERY 'q'"}
     * @param live  the captured schema object, by reference
     * @param copy  the deep copy taken when the snapshot was made
     */
    public record Entry(String input, JsonObject live, JsonObject copy) {}
}
