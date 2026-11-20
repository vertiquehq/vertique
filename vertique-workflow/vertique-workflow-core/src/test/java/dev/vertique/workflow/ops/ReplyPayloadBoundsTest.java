// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplyPayloadBoundsTest {
    @Test
    void acceptsSixtyFourContainersAndRefusesSixtyFiveWithoutPayloadText() {
        for (int kind = 0; kind < 5; kind++) {
            Object allowed = nested(64, kind);
            Object refused = nested(65, kind);
            assertThatCode(() -> ReplyPayloadBounds.validate(allowed, 10_000)).doesNotThrowAnyException();
            assertThatThrownBy(() -> ReplyPayloadBounds.validate(refused, 10_000))
                    .isInstanceOf(WorkflowInvalidPayloadException.class)
                    .hasMessageContaining("depth")
                    .hasMessageNotContaining("SECRET-DEPTH");
        }
    }

    @Test
    void countsEncodedObjectDepthAndIgnoresEscapedDelimitersInStrings() {
        assertThatCode(() -> ReplyPayloadBounds.validate(nestedObjects(64), 10_000))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ReplyPayloadBounds.validate(nestedObjects(65), 10_000))
                .isInstanceOf(WorkflowInvalidPayloadException.class)
                .hasMessageContaining("depth")
                .hasMessageNotContaining("SECRET-DEPTH");
        assertThatCode(() -> ReplyPayloadBounds.validate("\\\"{[\\\\]}".repeat(100), 10_000))
                .doesNotThrowAnyException();
    }

    @Test
    void oversizedIterableStopsBeingTraversedAtTheEncodedSizeBound() {
        java.util.concurrent.atomic.AtomicInteger visits = new java.util.concurrent.atomic.AtomicInteger();
        Iterable<Integer> finite = () -> new java.util.Iterator<>() {
            private int remaining = 100_000;

            @Override
            public boolean hasNext() {
                return remaining > 0;
            }

            @Override
            public Integer next() {
                remaining--;
                visits.incrementAndGet();
                return 1;
            }
        };

        assertThatThrownBy(() -> ReplyPayloadBounds.validate(finite, 1024))
                .isInstanceOf(WorkflowInvalidPayloadException.class)
                .hasMessageContaining("maxPayloadBytes");
        assertThat(visits.get()).isLessThan(100_000);
    }

    @Test
    void refusesNulButAcceptsLiteralEscapedText() {
        assertThatThrownBy(() -> ReplyPayloadBounds.encode(Map.of("value", "before\u0000after"), 10_000))
                .isInstanceOf(WorkflowInvalidPayloadException.class)
                .hasMessageContaining("cannot be encoded");
        assertThatCode(() -> ReplyPayloadBounds.encode("\\u0000", 10_000)).doesNotThrowAnyException();
    }

    private static Object nested(int depth, int kind) {
        Object value = "SECRET-DEPTH";
        for (int i = 0; i < depth; i++) {
            value = switch (kind) {
                case 0 -> List.of(value);
                case 1 -> Map.of("value", value);
                case 2 -> new JsonArray().add(value);
                case 3 -> new JsonObject().put("value", value);
                default -> new Object[] {value};
            };
        }
        return value;
    }

    private static Object nestedObjects(int depth) {
        Object value = "SECRET-DEPTH";
        for (int i = 0; i < depth; i++) value = new Node(value);
        return value;
    }

    public record Node(Object child) {}
}
