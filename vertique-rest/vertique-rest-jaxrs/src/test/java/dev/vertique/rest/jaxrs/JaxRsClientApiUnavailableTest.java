// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterizes that the JAX-RS client API is not usable on this module's classpath: the API
 * artifact carries {@link ClientBuilder}, but no client implementation is provided.
 */
class JaxRsClientApiUnavailableTest {

    @Test
    @DisplayName("ClientBuilder.newClient() fails because no JAX-RS client implementation is available")
    void newClientFails() {
        RuntimeException failure = assertThrows(RuntimeException.class, ClientBuilder::newClient);

        assertTrue(
                hasCause(failure, ClassNotFoundException.class),
                "the failure must trace back to a missing client implementation class");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
