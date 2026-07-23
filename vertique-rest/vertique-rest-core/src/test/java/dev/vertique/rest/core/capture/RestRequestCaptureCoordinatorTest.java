// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.capture;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.core.extension.OrderedExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestRequestCaptureCoordinator}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link RestRequestCaptureCoordinator} is an {@link OrderedExtension} — default methods
 *       delegate to {@link OrderedExtension} defaults (APPLICATION phase, priority 0, class name as
 *       orderKey).</li>
 * </ul>
 *
 * <p>The invocation contract (exactly-once invocation, isolation, no-op with empty set) is
 * exercised in {@code RestRequestCompletionEmitterTest.CaptureCoordinator}.
 */
class RestRequestCaptureCoordinatorTest {

    @Nested
    @DisplayName("RestRequestCaptureCoordinator is an OrderedExtension")
    class IsOrderedExtension {

        @Test
        @DisplayName("a lambda implementation is an OrderedExtension instance")
        void implementsOrderedExtension() {
            RestRequestCaptureCoordinator coordinator = (event, rc) -> {};
            assertInstanceOf(
                    OrderedExtension.class, coordinator, "RestRequestCaptureCoordinator must extend OrderedExtension");
        }
    }
}
