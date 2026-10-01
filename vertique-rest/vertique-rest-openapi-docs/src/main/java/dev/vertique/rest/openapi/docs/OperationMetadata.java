// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tags;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The documentation metadata of one operation, read from its resolved method and class annotations.
 *
 * <p>The {@code summary}, {@code description}, {@code deprecated}, and {@code externalDocs} members
 * come from the first {@link Operation} among the method annotations; a blank string is unset, only
 * {@code deprecated = true} is published, and {@code externalDocs} is published only with a non-blank
 * {@code url}. The operation's {@code tags} are the distinct names of {@link Operation#tags()} in
 * declared order, then of the method's {@link Tag}s, then of the class's {@link Tag}s, each in
 * resolved order, the first occurrence kept and blank names ignored; a {@link Tags} container is
 * unwrapped in place. The method's and class's {@link Tag}s, in that order, are also the operation's
 * tag declarations, which the document merges into its root {@code tags} (see {@link RootTags});
 * a name that appears only in {@link Operation#tags()} declares nothing.
 *
 * @param tags the distinct tag names of the operation, in order
 * @param summary the summary, or {@code null}
 * @param description the description, or {@code null}
 * @param externalDocs the External Documentation Object, or {@code null}
 * @param deprecated whether the operation is deprecated
 * @param declaredTags the {@link Tag} declarations of the method, then of the class, with non-blank
 *     names, in resolved order
 */
record OperationMetadata(
        List<String> tags,
        @Nullable String summary,
        @Nullable String description,
        @Nullable ObjectNode externalDocs,
        boolean deprecated,
        List<Tag> declaredTags) {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The metadata of an operation that carries none. */
    static final OperationMetadata NONE = new OperationMetadata(List.of(), null, null, null, false, List.of());

    /** Stores unmodifiable copies of the lists. */
    OperationMetadata {
        tags = List.copyOf(tags);
        declaredTags = List.copyOf(declaredTags);
    }

    /**
     * Reads the metadata of one operation.
     *
     * @param facts the operation's descriptor facts, or {@code null} when none were taken
     * @return the metadata; {@link #NONE} when no facts were taken
     */
    static OperationMetadata of(@Nullable OperationFacts facts) {
        if (facts == null) {
            return NONE;
        }
        Operation operation = null;
        for (Annotation annotation : facts.methodAnnotations()) {
            if (annotation instanceof Operation found) {
                operation = found;
                break;
            }
        }
        List<Tag> methodTags = tags(facts.methodAnnotations());
        List<Tag> classTags = tags(facts.classAnnotations());

        Set<String> names = new LinkedHashSet<>();
        if (operation != null) {
            for (String name : operation.tags()) {
                if (isSet(name)) {
                    names.add(name);
                }
            }
        }
        List<Tag> declared = new ArrayList<>(methodTags.size() + classTags.size());
        declared.addAll(methodTags);
        declared.addAll(classTags);
        for (Tag tag : declared) {
            names.add(tag.name());
        }

        if (operation == null) {
            return new OperationMetadata(List.copyOf(names), null, null, null, false, declared);
        }
        return new OperationMetadata(
                List.copyOf(names),
                setOrNull(operation.summary()),
                setOrNull(operation.description()),
                externalDocs(operation.externalDocs()),
                operation.deprecated(),
                declared);
    }

    /**
     * Writes the members that precede {@code operationId} in an Operation Object: {@code tags} when
     * there are any, {@code summary}, {@code description}, and {@code externalDocs}, each when set.
     *
     * @param operation the Operation Object, still without an {@code operationId}
     */
    void writeLeading(ObjectNode operation) {
        if (!tags.isEmpty()) {
            tags.forEach(operation.putArray("tags")::add);
        }
        if (summary != null) {
            operation.put("summary", summary);
        }
        if (description != null) {
            operation.put("description", description);
        }
        if (externalDocs != null) {
            operation.set("externalDocs", externalDocs.deepCopy());
        }
    }

    /**
     * Writes the members that follow the request body in an Operation Object: {@code deprecated: true}
     * when the operation is deprecated.
     *
     * @param operation the Operation Object
     */
    void writeTrailing(ObjectNode operation) {
        if (deprecated) {
            operation.put("deprecated", true);
        }
    }

    /**
     * Builds an External Documentation Object, with {@code description} when it is not blank and then
     * {@code url}.
     *
     * @param externalDocs the annotation, or {@code null}
     * @return the object, or {@code null} when the annotation is absent or its {@code url} is blank
     */
    @Nullable
    static ObjectNode externalDocs(@Nullable ExternalDocumentation externalDocs) {
        if (externalDocs == null || !isSet(externalDocs.url())) {
            return null;
        }
        ObjectNode node = NODES.objectNode();
        if (isSet(externalDocs.description())) {
            node.put("description", externalDocs.description());
        }
        node.put("url", externalDocs.url());
        return node;
    }

    /**
     * Tells whether an annotation string member is set.
     *
     * @param value the member value
     * @return {@code true} when it is neither {@code null} nor blank
     */
    static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /** Returns the value when it is set, else {@code null}. */
    @Nullable
    private static String setOrNull(@Nullable String value) {
        return isSet(value) ? value : null;
    }

    /**
     * Returns the {@link Tag}s among annotations with non-blank names, in order, each {@link Tags}
     * container unwrapped in place.
     */
    private static List<Tag> tags(List<Annotation> annotations) {
        List<Tag> tags = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (annotation instanceof Tag tag) {
                addNamed(tags, tag);
            } else if (annotation instanceof Tags container) {
                for (Tag tag : container.value()) {
                    addNamed(tags, tag);
                }
            }
        }
        return tags;
    }

    private static void addNamed(List<Tag> tags, Tag tag) {
        if (isSet(tag.name())) {
            tags.add(tag);
        }
    }
}
