// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.DiffFlags;
import com.flipkart.zjsonpatch.JsonDiff;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.EnumSet;
import java.util.Objects;

/**
 * INTERNAL: computes the RFC 6902 patch that turns one state into another, with the JSON Patch library.
 *
 * <p>The patch holds only {@code add}, {@code remove} and {@code replace} operations: the diff flags are passed
 * explicitly, so neither a {@code move} nor a {@code copy} is emitted and the library's defaults are not relied on. A
 * {@code remove} carries no value, as RFC 6902 requires, and no operation carries the value it replaces, so a patch
 * never holds an old value. An unchanged state gives an empty array.
 */
@Singleton
public class JsonPatchDiff {

    /** The flags of every diff: no move, no copy, and no value on a removal. */
    private static final EnumSet<DiffFlags> FLAGS =
            EnumSet.of(DiffFlags.OMIT_MOVE_OPERATION, DiffFlags.OMIT_COPY_OPERATION, DiffFlags.OMIT_VALUE_ON_REMOVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Creates the diff. */
    @Inject
    public JsonPatchDiff() {
        // stateless
    }

    /**
     * Computes the patch from one state to another.
     *
     * @param before the state before the change
     * @param after  the state after the change
     * @return the patch; applying it to {@code before} yields {@code after}. An empty array when the states are equal
     */
    public JsonArray diff(JsonObject before, JsonObject after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        try {
            JsonNode source = MAPPER.readTree(before.encode());
            JsonNode target = MAPPER.readTree(after.encode());
            JsonNode patch = JsonDiff.asJson(source, target, FLAGS);
            return new JsonArray(MAPPER.writeValueAsString(patch));
        } catch (JsonProcessingException e) {
            // the states were just encoded by Vert.x; failing to read them back is a defect, and carries no value
            throw new IllegalStateException("A state could not be read for the diff", e);
        }
    }
}
