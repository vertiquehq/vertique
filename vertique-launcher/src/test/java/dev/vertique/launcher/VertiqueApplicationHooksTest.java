// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link VertiqueApplication#afterConfigParsed(JsonObject)} contract without
 * launching a full Vert.x instance.
 *
 * <p>Specifically: the method must return the exact input it receives (stock parity), including
 * {@code null}. The internal normalisation (null → empty JsonObject) is an implementation detail
 * verified indirectly via the launch tests.
 */
class VertiqueApplicationHooksTest {

    @Test
    @DisplayName("afterConfigParsed returns the same JsonObject instance it received")
    void afterConfigParsedReturnsSameInstance() {
        VertiqueApplication app = new TestVertiqueApplication(new String[] {});
        JsonObject config = new JsonObject().put("key", "value");

        JsonObject result = app.afterConfigParsed(config);

        assertSame(config, result, "afterConfigParsed must return the exact same instance");
    }

    @Test
    @DisplayName("afterConfigParsed returns null when given null (stock parity)")
    void afterConfigParsedReturnsNullWhenGivenNull() {
        VertiqueApplication app = new TestVertiqueApplication(new String[] {});

        JsonObject result = app.afterConfigParsed(null);

        assertNull(result, "afterConfigParsed must return null when given null");
    }
}
