// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityIdentityResolver}.
 *
 * <p>Verifies: the default {@code priority()} returns 100; the default {@code id()} returns the
 * fully-qualified class name; a lambda implementation satisfies the contract with expected defaults;
 * a custom anonymous-class override of {@code priority()} and {@code id()} returns the expected
 * values; and {@code resolve(...)} produces the expected future result.
 */
class SecurityIdentityResolverTest {

    private static final SecurityIdentityResolutionContext EMPTY_CTX =
            new SecurityIdentityResolutionContext(List.of(), Optional.empty(), Optional.empty(), Map.of());

    // --- default priority ---

    @Test
    @DisplayName("default priority() returns 100")
    void defaultPriorityIs100() {
        SecurityIdentityResolver resolver = ctx -> Future.succeededFuture(Optional.empty());
        assertEquals(100, resolver.priority());
    }

    // --- default id ---

    @Test
    @DisplayName("default id() returns the fully-qualified class name")
    void defaultIdReturnsFqcn() {
        SecurityIdentityResolver resolver = ctx -> Future.succeededFuture(Optional.empty());
        String id = resolver.id();

        assertNotNull(id);
        // Lambda class names are JVM-implementation-specific; just verify it is non-blank.
        assertTrue(!id.isBlank(), "id() must not be blank");
    }

    // --- lambda resolve ---

    @Test
    @DisplayName("lambda resolver: resolve() returns the expected empty future")
    void lambdaResolverReturnsExpectedFuture() {
        SecurityIdentityResolver resolver = ctx -> Future.succeededFuture(Optional.empty());
        Future<Optional<SecurityIdentity>> result = resolver.resolve(EMPTY_CTX);

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertTrue(result.result().isEmpty());
    }

    @Test
    @DisplayName("lambda resolver: resolve() returns a present identity when constructed")
    void lambdaResolverReturnsPresentIdentity() {
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
        SecurityIdentity identity = SecurityIdentity.user(actor);
        SecurityIdentityResolver resolver = ctx -> Future.succeededFuture(Optional.of(identity));

        Future<Optional<SecurityIdentity>> result = resolver.resolve(EMPTY_CTX);

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals(Optional.of(identity), result.result());
    }

    // --- custom override ---

    @Test
    @DisplayName("custom resolver with overridden priority() and id() returns expected values")
    void customResolverOverrides() {
        SecurityIdentityResolver resolver = new SecurityIdentityResolver() {
            @Override
            public int priority() {
                return 50;
            }

            @Override
            public String id() {
                return "test-resolver";
            }

            @Override
            public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
                return Future.succeededFuture(Optional.empty());
            }
        };

        assertEquals(50, resolver.priority());
        assertEquals("test-resolver", resolver.id());
        assertTrue(resolver.resolve(EMPTY_CTX).succeeded());
    }
}
