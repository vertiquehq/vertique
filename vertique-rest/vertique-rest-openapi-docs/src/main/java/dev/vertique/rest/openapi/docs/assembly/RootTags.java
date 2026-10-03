// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.assembly;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.metadata.AnnotationValues;
import dev.vertique.rest.openapi.docs.metadata.OperationMetadata;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Merges the {@link Tag} declarations of a document's published operations into its root {@code
 * tags}.
 *
 * <p>Declarations are added in document order. Each name becomes one Tag Object with {@code name},
 * {@code description}, and {@code externalDocs}, in that order. The two members are merged
 * separately: a {@code description} is set when it is not blank and an {@code externalDocs} when its
 * {@code url} is not blank (its value being the rendered External Documentation Object); a default
 * member never conflicts with a set one, and the set value is published. Two set values of one
 * member that differ fail publication, naming the mount, the tag, the operation that first set the
 * member, and the first later operation whose value differs, never either value. The root {@code
 * tags} lists the names in {@link String#compareTo} order.
 */
final class RootTags {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String subject;

    private final Map<String, Merged> byName = new TreeMap<>();

    /**
     * Creates an empty merge for one document.
     *
     * @param subject the failure-message subject naming the application and its mount
     */
    RootTags(String subject) {
        this.subject = Objects.requireNonNull(subject, "subject");
    }

    /**
     * Adds the tag declarations of one operation, in their declared order.
     *
     * @param operationId the runtime operation id
     * @param metadata the operation's metadata
     * @throws RestConfigurationException when a declaration sets a member of a tag to a value other
     *     than the one an earlier declaration set
     */
    void add(String operationId, OperationMetadata metadata) {
        for (Tag tag : metadata.declaredTags()) {
            Merged merged = byName.computeIfAbsent(tag.name(), name -> new Merged());
            if (AnnotationValues.isSet(tag.description())) {
                merged.description =
                        merge(tag.name(), "description", merged.description, tag.description(), operationId);
            }
            ObjectNode externalDocs = OperationMetadata.externalDocs(tag.externalDocs());
            if (externalDocs != null) {
                merged.externalDocs = merge(tag.name(), "externalDocs", merged.externalDocs, externalDocs, operationId);
            }
        }
    }

    /**
     * Renders the root {@code tags}.
     *
     * @return the Tag Objects sorted by name, or {@code null} when no published operation declares a
     *     tag
     */
    @Nullable
    ArrayNode render() {
        if (byName.isEmpty()) {
            return null;
        }
        ArrayNode tags = NODES.arrayNode();
        byName.forEach((name, merged) -> {
            ObjectNode tag = tags.addObject();
            tag.put("name", name);
            if (merged.description != null) {
                tag.put("description", merged.description.value());
            }
            if (merged.externalDocs != null) {
                tag.set("externalDocs", merged.externalDocs.value().deepCopy());
            }
        });
        return tags;
    }

    /**
     * Returns the member's setting after a declaration sets it: the earlier setting when there is one
     * and the values are equal, else a new setting by this operation.
     */
    private <T> Setting<T> merge(
            String name, String member, @Nullable Setting<T> earlier, T value, String operationId) {
        if (earlier == null) {
            return new Setting<>(value, operationId);
        }
        if (!earlier.value().equals(value)) {
            throw new RestConfigurationException(subject + ": tag '" + name + "' is declared with a different "
                    + member + " by operation '" + earlier.operationId() + "' and operation '" + operationId
                    + "'; every declaration of a tag that sets " + member + " must set the same value");
        }
        return earlier;
    }

    /** The merged members of one tag name. */
    private static final class Merged {

        @Nullable
        private Setting<String> description;

        @Nullable
        private Setting<ObjectNode> externalDocs;
    }

    /**
     * One set member of a tag and the operation that first set it.
     *
     * @param value the member value
     * @param operationId the first operation that set it
     */
    private record Setting<T>(T value, String operationId) {}
}
