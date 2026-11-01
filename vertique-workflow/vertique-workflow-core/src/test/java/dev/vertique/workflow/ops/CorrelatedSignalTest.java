// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks that {@link CorrelatedSignal} and {@link SignalProvenance} refuse unusable values and copy faithfully. */
class CorrelatedSignalTest {

    private interface OrderContract {}

    private static final Map<String, String> PAYLOAD = Map.of("amount", "10");

    private static CorrelatedSignal signalWithKey(String key) {
        return CorrelatedSignal.of(OrderContract.class, "payment-captured", key, PAYLOAD, "d-1");
    }

    private static CorrelatedSignal signalWithDedupKey(String dedupKey) {
        return CorrelatedSignal.of(OrderContract.class, "payment-captured", "PAY-77", PAYLOAD, dedupKey);
    }

    private static void assertRefusedWithoutEchoing(Runnable construction, String component, String marker) {
        assertThatThrownBy(construction::run)
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class)
                .hasMessageContaining(component)
                .satisfies(
                        error -> assertThat(String.valueOf(error.getMessage())).doesNotContain(marker));
    }

    @Test
    @DisplayName("construction refuses a null, blank, over-long, NUL-containing key and a blank dedup key")
    void constructionEnforcesTheKeyAndDedupKeyRules() {
        // Given a contract class, a signal name and a payload
        // When a signal is made with each unusable key or dedup key
        // Then it is refused for the component and the message never repeats the value
        assertRefusedWithoutEchoing(() -> signalWithKey(null), "key", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithKey(""), "key", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithKey("   "), "key", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithKey("leak-marker" + "a".repeat(245)), "key", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithKey("leak-marker" + (char) 0 + "x"), "key", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithDedupKey(null), "dedupKey", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithDedupKey(""), "dedupKey", "leak-marker");
        assertRefusedWithoutEchoing(() -> signalWithDedupKey("   "), "dedupKey", "leak-marker");

        // And the longest allowed key and a normal key are accepted unchanged
        assertThatCode(() -> signalWithKey("a".repeat(255))).doesNotThrowAnyException();
        assertThat(signalWithKey("a".repeat(255)).key()).isEqualTo("a".repeat(255));
        assertThat(signalWithKey("PAY-77").key()).isEqualTo("PAY-77");
        assertThat(signalWithDedupKey("d-1").dedupKey()).isEqualTo("d-1");
    }

    @Test
    @DisplayName("narrowing and provenance copies change only their own component")
    void narrowingAndProvenanceCopiesChangeOnlyTheirOwnComponent() {
        // Given a signal made with of
        CorrelatedSignal original = signalWithKey("PAY-77");
        SignalProvenance provenance = SignalProvenance.kafka("orders-consumer", "orders", 3, 42L);

        // When each narrowing and the provenance copy is applied
        CorrelatedSignal withDefinition = original.withDefinition("order-v1");
        CorrelatedSignal withScope = original.withScope("acme");
        CorrelatedSignal withProvenance = original.withProvenance(provenance);

        // Then each copy differs from the original only in its component, and the original is unchanged
        assertThat(withDefinition)
                .isEqualTo(new CorrelatedSignal(
                        OrderContract.class, "payment-captured", "PAY-77", PAYLOAD, "d-1", "order-v1", null, null));
        assertThat(withScope)
                .isEqualTo(new CorrelatedSignal(
                        OrderContract.class, "payment-captured", "PAY-77", PAYLOAD, "d-1", null, "acme", null));
        assertThat(withProvenance)
                .isEqualTo(new CorrelatedSignal(
                        OrderContract.class, "payment-captured", "PAY-77", PAYLOAD, "d-1", null, null, provenance));
        assertThat(original).isEqualTo(signalWithKey("PAY-77"));
        assertThat(original.definitionId()).isNull();
        assertThat(original.scopeKey()).isNull();
        assertThat(original.provenance()).isNull();

        // And of left the optional components null
        assertThat(original.contract()).isEqualTo(OrderContract.class);
        assertThat(original.payload()).isEqualTo(PAYLOAD);

        // And a scope key that breaks the scope key rules is refused
        assertThatThrownBy(() -> original.withScope("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.withScope("ac" + (char) 9 + "me"))
                .isInstanceOf(IllegalArgumentException.class);

        // And the factories set transport and source and put the remaining arguments under their argument names
        SignalProvenance kafka = SignalProvenance.kafka("orders-consumer", "orders", 3, 42L);
        SignalProvenance camel = SignalProvenance.camel("orders-route", "trigger-9", "abc123");
        assertThat(kafka.transport()).isEqualTo("kafka");
        assertThat(kafka.source()).isEqualTo("orders-consumer");
        assertThat(kafka.attributes())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "topic", "orders",
                        "partition", "3",
                        "offset", "42"));
        assertThat(camel.transport()).isEqualTo("camel");
        assertThat(camel.source()).isEqualTo("orders-route");
        assertThat(camel.attributes())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "triggerId", "trigger-9",
                        "messageHash", "abc123"));

        // And the record refuses a null component and holds an immutable copy of its attributes
        assertThatThrownBy(() -> new SignalProvenance(null, "source", Map.of()))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalProvenance("kafka", null, Map.of()))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalProvenance("kafka", "source", null))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        Map<String, String> mutable = new HashMap<>();
        mutable.put("topic", "orders");
        SignalProvenance copied = new SignalProvenance("kafka", "source", mutable);
        mutable.put("partition", "3");
        assertThat(copied.attributes()).containsExactlyEntriesOf(Map.of("topic", "orders"));
        assertThatThrownBy(() -> copied.attributes().put("offset", "1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
