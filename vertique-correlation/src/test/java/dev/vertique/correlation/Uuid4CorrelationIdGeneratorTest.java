// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Uuid4CorrelationIdGenerator}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code generate()} returns a parseable RFC 4122 UUID v4 string.</li>
 *   <li>{@code INSTANCE} is the same object on every reference.</li>
 *   <li>100 generations return 100 distinct values.</li>
 * </ul>
 */
class Uuid4CorrelationIdGeneratorTest {

    @Test
    @DisplayName("generate() returns a parseable UUID v4 string")
    void generateReturnsUuidV4() {
        String id = Uuid4CorrelationIdGenerator.INSTANCE.generate();
        UUID parsed = UUID.fromString(id);
        assertEquals(4, parsed.version(), "UUID version must be 4");
    }

    @Test
    @DisplayName("INSTANCE is the same reference on every access")
    void instanceIsSameReference() {
        assertSame(Uuid4CorrelationIdGenerator.INSTANCE, Uuid4CorrelationIdGenerator.INSTANCE);
    }

    @Test
    @DisplayName("100 generations produce 100 distinct values")
    void hundredGenerationsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(Uuid4CorrelationIdGenerator.INSTANCE.generate());
        }
        assertEquals(100, ids.size(), "Expected 100 distinct UUID values");
    }
}
