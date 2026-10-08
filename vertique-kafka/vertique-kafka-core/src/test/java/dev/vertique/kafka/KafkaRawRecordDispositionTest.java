// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.payload.PayloadSources;
import dev.vertique.kafka.interceptor.KafkaRawRecordDisposition;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaRawRecordDispositionTest {

    @Test
    @DisplayName("later changes to the map the headers were built from do not reach the identity")
    void shouldSnapshotHeaders() {
        Map<String, String> source = new HashMap<>(Map.of("a", "1"));

        KafkaRawRecordDisposition identity =
                new KafkaRawRecordDisposition("c", "t", 0, 1L, "k", source, PayloadSources.absent(), 0L, 0);
        source.put("b", "2");

        assertEquals(Map.of("a", "1"), identity.headers());
    }

    @Test
    @DisplayName("the identity's headers cannot be modified")
    void shouldExposeUnmodifiableHeaders() {
        KafkaRawRecordDisposition identity = new KafkaRawRecordDisposition(
                "c", "t", 0, 1L, "k", new HashMap<>(Map.of("a", "1")), PayloadSources.absent(), 0L, 0);

        assertThrows(
                UnsupportedOperationException.class, () -> identity.headers().put("b", "2"));
    }
}
