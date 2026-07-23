// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase-0 SPI proof: demonstrates that {@link ServiceMethodMeta.ParamMeta} can expose a
 * {@link ParameterMetadata} view (the new {@code dev.vertique.core.codegen} SPI) without replacing
 * the record's existing public shape — composition, not replacement.
 *
 * <p>The view supplies {@code index()} positionally (the parameter's position in the method's
 * parameter list); {@code name()}/{@code type()} delegate to the {@code ParamMeta}; annotation
 * lookups are empty (services tracks no per-parameter annotations); and {@code genericType()} is
 * outside the reflection-free core, so it throws {@link UnsupportedOperationException}. The
 * domain-only fields {@code source()}/{@code lookupKey()} remain directly on {@code ParamMeta}.
 *
 * <p>This is a RED test: it references the not-yet-existing {@code ParamMeta#asParameterMetadata(int)}
 * producer, so it must fail to compile until the GREEN step adds the view.
 */
class ServiceParamMetaComposesParameterMetadataTest {

    /** Marker annotation used only to prove the view reports no parameter annotations. */
    private @interface Ann {}

    /** Placeholder payload type for the view's {@code type()} assertions. */
    private static final class SomeType {}

    /** Placeholder dispatch-context value type for the view's {@code type()} assertions. */
    private static final class X {}

    @Test
    @DisplayName("PAYLOAD ParamMeta exposes a ParameterMetadata view delegating name/type with positional index")
    void payloadParamMetaExposesView() {
        ParamMeta payload = new ParamMeta("payload", ParamSource.PAYLOAD, SomeType.class, null);

        ParameterMetadata view = payload.asParameterMetadata(0);

        assertEquals("payload", view.name());
        assertEquals(SomeType.class, view.type());
        assertEquals(0, view.index());
        assertTrue(view.findAnnotation(Ann.class).isEmpty());
        assertFalse(view.hasAnnotation(Ann.class));
        assertThrows(UnsupportedOperationException.class, view::genericType);

        // Composition, not replacement: domain fields stay on ParamMeta itself.
        assertEquals(ParamSource.PAYLOAD, payload.source());
        assertNull(payload.lookupKey());
    }

    @Test
    @DisplayName("DISPATCH_CONTEXT ParamMeta exposes a view at its position while retaining source/lookupKey")
    void dispatchContextParamMetaExposesViewAndRetainsDomainFields() {
        ParamMeta ctx = new ParamMeta("ctx", ParamSource.DISPATCH_CONTEXT, X.class, "someKey");

        ParameterMetadata view = ctx.asParameterMetadata(1);

        assertEquals("ctx", view.name());
        assertEquals(X.class, view.type());
        assertEquals(1, view.index());
        assertTrue(view.findAnnotation(Ann.class).isEmpty());
        assertFalse(view.hasAnnotation(Ann.class));
        assertThrows(UnsupportedOperationException.class, view::genericType);

        // Domain fields retained directly on ParamMeta (not folded into the view).
        assertEquals(ParamSource.DISPATCH_CONTEXT, ctx.source());
        assertEquals("someKey", ctx.lookupKey());
    }

    @Test
    @DisplayName("DISPATCH_CONTEXT lookupKey non-null invariant remains intact (composition does not weaken it)")
    void dispatchContextLookupKeyInvariantHolds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParamMeta("ctx", ParamSource.DISPATCH_CONTEXT, X.class, null));
    }

    @Test
    @DisplayName("View name/type are consistent with the ParamMeta's own accessors (no divergence)")
    void viewDelegatesConsistentlyWithParamMetaAccessors() {
        ParamMeta payload = new ParamMeta("payload", ParamSource.PAYLOAD, SomeType.class, null);

        ParameterMetadata view = payload.asParameterMetadata(0);

        assertEquals(payload.name(), view.name());
        assertEquals(payload.type(), view.type());
    }
}
