// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InputTraversalContext}'s carriage of the {@link InputFieldNameResolver}.
 *
 * <p>The resolver reaches generated processors through the traversal context rather than through a
 * new {@link GeneratedInputProcessor} parameter, so the context is the only thing keeping the
 * projection alive across a nested descent and across the codegen↔reflection boundary. A context
 * that lost the resolver at either hop would silently fall back to identity naming and stop
 * matching renamed fields — the defect this seam exists to close.
 */
class InputTraversalContextTest {

    @Test
    @DisplayName("the name resolver survives descend and the reflective continuation boundary")
    void shouldCarryTheResolverAcrossDescentAndReflectiveContinuation() {
        InputFieldNameResolver projection =
                (ownerType, wireName) -> "user_name".equals(wireName) ? "userName" : wireName;

        InputTraversalContext root = InputTraversalContext.fromPolicies(EffectiveInputPolicies.NONE, projection);
        assertEquals(
                "userName",
                root.logicalFieldName(Holder.class, "user_name"),
                "a context seeded from the invocation policies must expose the supplied projection");
        assertEquals(
                "other",
                root.logicalFieldName(Holder.class, "other"),
                "an unrecognized wire name passes through the context accessor unchanged");

        // --- descend: chain-free layer, then a layer that actually composes a new chain ---

        InputTraversalContext child = root.descend(List.of(), List.of(), false, false, null, null, false, false);
        assertEquals(
                "userName",
                child.logicalFieldName(Holder.class, "user_name"),
                "descend must carry the resolver into the child context even when it reuses the "
                        + "parent's chain references");

        InputTraversalContext grandChild = child.descend(
                List.of(NoOpCanonicalizer.class), List.of(), false, false, List.of(), List.of(), false, false);
        assertEquals(
                "userName",
                grandChild.logicalFieldName(Holder.class, "user_name"),
                "descend must carry the resolver when it allocates a freshly composed chain too");

        InputTraversalContext skipped = grandChild.descend(List.of(), List.of(), true, true, null, null, false, false);
        assertEquals(
                "userName",
                skipped.logicalFieldName(Holder.class, "user_name"),
                "a sticky skip empties the chains, not the name projection — a skipped subtree must "
                        + "still resolve field names to select the right per-field metadata");

        // --- codegen↔reflection boundary: dispatchNested and walkUnknown ---

        List<InputTraversalContext> observed = new ArrayList<>();
        GeneratedInputProcessorDispatcher dispatcher =
                new GeneratedInputProcessorDispatcher(new GeneratedInputProcessorDispatcher.ReflectiveContinuation() {
                    @Override
                    public Object continueAt(
                            Object intermediate,
                            Class<?> targetType,
                            InputTraversalContext ctx,
                            InputLocation location,
                            String fieldPath,
                            Class<?> ownerType) {
                        observed.add(ctx);
                        return intermediate;
                    }

                    @Override
                    public Object walkUnknown(
                            Object intermediate,
                            InputTraversalContext ctx,
                            InputLocation location,
                            String fieldPath,
                            Class<?> ownerType) {
                        observed.add(ctx);
                        return intermediate;
                    }
                });
        ChainResolver passthrough = (value, canonicalizers, sanitizers, valueContext) -> value;

        Map<String, Object> fragment = new LinkedHashMap<>();
        fragment.put("user_name", "alice");

        // Holder has no _InputProcessor companion, so the dispatcher falls through to continueAt.
        dispatcher.dispatchNested(
                fragment,
                Holder.class,
                EffectiveInputPolicies.NONE,
                InputLocation.BODY,
                passthrough,
                child,
                "holder",
                Holder.class);
        dispatcher.walkUnknown(fragment, child, InputLocation.BODY, "holder", Holder.class);

        assertEquals(2, observed.size(), "both continuation entry points must have been reached");
        for (InputTraversalContext crossed : observed) {
            assertNotNull(crossed, "the continuation must receive a context, never null");
            assertEquals(
                    "userName",
                    crossed.logicalFieldName(Holder.class, "user_name"),
                    "the context handed back to the reflective walker must still carry the projection; "
                            + "losing it here would make a generated processor's nested arms match Java "
                            + "names against raw wire keys");
        }
    }

    /** Owner type with no {@code _InputProcessor} companion, so nested dispatch falls through. */
    static final class Holder {
        String userName;
    }

    /** Chain member used only to force {@code descend} down its composing branch. */
    static final class NoOpCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }
}
