// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link HolderBackedSecurityRuntime}.
 *
 * <p>Verifies that {@code current()} reads from the unified {@link dev.vertique.core.context.ContextHolder},
 * {@code bindCurrent(sc)} stores the value and returns a scope that clears it on close, and
 * {@code toJaxRs} delegates to the injected {@link JaxRsSecurityContextFactory}.
 *
 * <p>Tests that require an active Vert.x duplicated context use the {@code VertxExtension} for
 * context injection; tests that assert fail-fast behaviour outside a context run on plain JUnit
 * threads.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HolderBackedSecurityRuntimeTest {

    private JaxRsSecurityContextFactory mockFactory;
    private HolderBackedSecurityRuntime runtime;

    @BeforeEach
    void setUp() {
        mockFactory = mock(JaxRsSecurityContextFactory.class);
        runtime = new HolderBackedSecurityRuntime(mockFactory);
    }

    // --- current() outside Vert.x ---

    @Nested
    @DisplayName("current() — lenient outside Vert.x")
    class CurrentOutsideContext {

        @Test
        @DisplayName("returns null when called outside any Vert.x context")
        void returnsNullOutsideVertxContext() {
            assertNull(runtime.current(), "current() must return null outside a Vert.x context");
        }
    }

    // --- bindCurrent() outside Vert.x ---

    @Nested
    @DisplayName("bindCurrent() — fail-fast outside Vert.x")
    class BindCurrentOutsideContext {

        @Test
        @DisplayName("throws IllegalStateException when called outside any Vert.x context")
        void throwsOutsideVertxContext() {
            SecurityContext sc = mock(SecurityContext.class);
            assertThrows(
                    IllegalStateException.class,
                    () -> runtime.bindCurrent(sc),
                    "bindCurrent() must throw outside a Vert.x context");
        }

        @Test
        @DisplayName("throws NullPointerException when context argument is null")
        void throwsNpeForNullArgument() {
            assertThrows(NullPointerException.class, () -> runtime.bindCurrent(null));
        }
    }

    // --- bindCurrent() on a duplicated context ---

    @Nested
    @DisplayName("bindCurrent() + current() on a duplicated Vert.x context")
    class BindCurrentOnDuplicatedContext {

        @Test
        @DisplayName("current() returns the bound SecurityContext after bindCurrent()")
        void currentReturnsValueAfterBind(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try {
                    SecurityContext sc = mock(SecurityContext.class);
                    ContextHolder.Scope scope = runtime.bindCurrent(sc);

                    assertNotNull(scope, "bindCurrent() must return a non-null scope");
                    assertSame(sc, runtime.current(), "current() must return the value that was bound");
                    // Also verify via the unified facade
                    assertSame(sc, ContextValues.current(SecurityContext.class).orElse(null));

                    scope.close();
                    assertNull(runtime.current(), "current() must return null after scope close");
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("closing the scope clears the SecurityContext from current()")
        void closingScopeClearsCurrent(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try {
                    SecurityContext sc = mock(SecurityContext.class);
                    ContextHolder.Scope scope = runtime.bindCurrent(sc);
                    assertNotNull(runtime.current());

                    scope.close();
                    assertNull(runtime.current(), "current() must return null after the scope is closed");
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("nested bindCurrent() restores the prior SecurityContext on scope close (LIFO)")
        void nestedBindRestoresPriorValueLifo(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try {
                    SecurityContext outer = mock(SecurityContext.class);
                    SecurityContext inner = mock(SecurityContext.class);

                    ContextHolder.Scope outerScope = runtime.bindCurrent(outer);
                    assertSame(outer, runtime.current());

                    ContextHolder.Scope innerScope = runtime.bindCurrent(inner);
                    assertSame(inner, runtime.current(), "inner bind should shadow outer");

                    innerScope.close();
                    assertSame(outer, runtime.current(), "outer value should be restored after inner close");

                    outerScope.close();
                    assertNull(runtime.current(), "current() must be null after all scopes closed");
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("scope close is idempotent — closing twice does not corrupt the holder")
        void scopeCloseIsIdempotent(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try {
                    SecurityContext sc = mock(SecurityContext.class);
                    ContextHolder.Scope scope = runtime.bindCurrent(sc);

                    scope.close();
                    assertDoesNotThrow(scope::close, "second close must not throw");
                    assertNull(runtime.current());
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }
    }

    // --- toJaxRs() ---

    @Nested
    @DisplayName("toJaxRs() — delegates to JaxRsSecurityContextFactory")
    class ToJaxRs {

        @Test
        @DisplayName("returns the factory result when factory is present")
        void delegatesToFactory() {
            SecurityContext sc = mock(SecurityContext.class);
            jakarta.ws.rs.core.SecurityContext jaxRs = mock(jakarta.ws.rs.core.SecurityContext.class);
            when(mockFactory.create(sc, true)).thenReturn(jaxRs);

            assertSame(jaxRs, runtime.toJaxRs(sc, true));
            verify(mockFactory).create(sc, true);
        }

        @Test
        @DisplayName("rejects null factory at construction (SecurityModule must provide it)")
        void rejectsNullFactoryAtConstruction() {
            assertThrows(NullPointerException.class, () -> new HolderBackedSecurityRuntime(null));
        }

        @Test
        @DisplayName("passes null SecurityContext to factory for anonymous requests")
        void passesNullSecurityContextForAnonymous() {
            jakarta.ws.rs.core.SecurityContext jaxRs = mock(jakarta.ws.rs.core.SecurityContext.class);
            when(mockFactory.create(null, false)).thenReturn(jaxRs);

            assertSame(jaxRs, runtime.toJaxRs(null, false));
            verify(mockFactory).create(null, false);
        }
    }
}
