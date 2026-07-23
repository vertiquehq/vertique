// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.Vertx;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RestClientBuilder}'s generated-proxy selection logic.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>When a generated proxy is present on the classpath, the builder selects it over the JDK
 *       dynamic fallback.</li>
 *   <li>A nested client interface ({@code NestedClientHost$NestedClient}) is found via its
 *       flattened companion name ({@code NestedClientHost_NestedClient_RestClientProxy}) — i.e.
 *       the builder uses {@link dev.vertique.core.util.GeneratedNames#companionFqn}, not
 *       {@code clientInterface.getName() + suffix}.</li>
 *   <li>When no generated proxy is on the classpath, the builder falls back silently to the JDK
 *       dynamic proxy.</li>
 *   <li>When a generated proxy is present but fails to instantiate, the builder throws
 *       {@link RestClientConfigurationException} loudly instead of silently degrading.</li>
 * </ul>
 *
 * <p>Fixture interfaces and hand-written stand-in proxies are top-level (or deliberately nested
 * inside {@link NestedClientHost}) test classes in the same package so that
 * {@link dev.vertique.core.util.GeneratedNames#companionFqn} resolves them correctly.
 */
@DisplayName("RestClientBuilder generated-proxy selection")
class RestClientBuilderProxySelectionTest {

    // --- Shared Vert.x instance ---

    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // --- Selection tests ---

    @Nested
    @DisplayName("top-level client")
    class TopLevel {

        @Test
        @DisplayName("selects the generated proxy when present on the classpath")
        void usesGeneratedProxyWhenPresent() {
            // SelectionTestClient_RestClientProxy is on the test classpath.
            // GeneratedNames.companionFqn(SelectionTestClient.class, "_RestClientProxy")
            // = "dev.vertique.rest.client.SelectionTestClient_RestClientProxy" (no '$', no nesting)
            Object proxy = RestClientBuilder.create(vertx)
                    .baseUrl("http://localhost:9999")
                    .build(SelectionTestClient.class);

            assertNotNull(proxy);
            assertInstanceOf(SelectionTestClient_RestClientProxy.class, proxy);
            assertFalse(Proxy.isProxyClass(proxy.getClass()), "should not be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("falls back to JDK dynamic proxy when no generated proxy exists")
        void fallsBackToJdkProxyWhenAbsent() {
            // AbsentProxyClient has no stand-in proxy on the classpath.
            Object proxy = RestClientBuilder.create(vertx)
                    .baseUrl("http://localhost:9999")
                    .build(AbsentProxyClient.class);

            assertNotNull(proxy);
            assertTrue(Proxy.isProxyClass(proxy.getClass()), "should be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("throws RestClientConfigurationException when present generated proxy is broken")
        void throwsWhenGeneratedProxyBroken() {
            // BrokenSelectionClient_RestClientProxy has wrong constructor — must fail loudly.
            assertThrows(RestClientConfigurationException.class, () -> RestClientBuilder.create(vertx)
                    .baseUrl("http://localhost:9999")
                    .build(BrokenSelectionClient.class));
        }
    }

    @Nested
    @DisplayName("nested client interface")
    class NestedClientSelection {

        @Test
        @DisplayName("selects flattened companion name for a nested client (Outer_Inner_RestClientProxy)")
        void usesGeneratedProxyForNestedClient() {
            // NestedClientHost.NestedClient binary name: dev.vertique.rest.client.NestedClientHost$NestedClient
            // GeneratedNames.companionFqn produces (replace '$' with '_'):
            //   dev.vertique.rest.client.NestedClientHost_NestedClient_RestClientProxy
            // The hand-written stand-in (same package/name) must be found.
            // A regression to clientInterface.getName() + suffix would look for
            //   dev.vertique.rest.client.NestedClientHost$NestedClient_RestClientProxy
            // which does not match any class, causing a silent JDK fallback.
            Object proxy = RestClientBuilder.create(vertx)
                    .baseUrl("http://localhost:9999")
                    .build(NestedClientHost.NestedClient.class);

            assertNotNull(proxy);
            assertInstanceOf(NestedClientHost_NestedClient_RestClientProxy.class, proxy);
            assertFalse(Proxy.isProxyClass(proxy.getClass()), "should not be a JDK dynamic proxy");
        }
    }
}
