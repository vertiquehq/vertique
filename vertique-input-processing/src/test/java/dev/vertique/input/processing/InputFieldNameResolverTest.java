// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InputFieldNameResolver} — the codec-neutral wire-name to Java-property-name
 * projection consulted on the request path.
 *
 * <p>The contract under test is <strong>totality</strong>: the function maps in the wire → Java
 * direction (the only direction able to express an alias's many-to-one mapping), and a wire name it
 * does not recognize is returned unchanged rather than yielding {@code null} or throwing. A
 * projection that could miss would silently drop a declared canonicalizer or sanitizer, which is
 * exactly the failure mode this seam exists to remove.
 */
class InputFieldNameResolverTest {

    @Test
    @DisplayName("an unknown wire name is returned unchanged by IDENTITY and by a partial projection")
    void shouldReturnTheWireNameForUnknownKeysAndNeverNull() {
        InputFieldNameResolver identity = InputFieldNameResolver.IDENTITY;
        assertNotNull(identity, "IDENTITY is the resolver every transport whose keys are already Java names uses");

        // A reference not shared with any literal, so assertSame proves pass-through rather than
        // constant-pool interning.
        String unmapped = new StringBuilder("no_such_field").toString();

        assertSame(
                unmapped,
                identity.logicalName(Holder.class, unmapped),
                "IDENTITY must hand back the wire name itself, for every owner type");
        assertEquals(
                "userName",
                identity.logicalName(Holder.class, "userName"),
                "IDENTITY leaves a name that is already the Java property name alone");
        assertEquals(
                "user_name",
                identity.logicalName(Object.class, "user_name"),
                "IDENTITY ignores the owner type — it never consults a per-type projection");

        Map<String, String> projection = Map.of("user_name", "userName", "home_page", "homePage");
        InputFieldNameResolver partial = (ownerType, wireName) -> projection.getOrDefault(wireName, wireName);

        assertEquals(
                "userName",
                partial.logicalName(Holder.class, "user_name"),
                "a recognized wire name projects to the Java property name");
        assertEquals(
                "homePage",
                partial.logicalName(Holder.class, "home_page"),
                "every recognized wire name projects, not only the first");

        assertSame(
                unmapped,
                partial.logicalName(Holder.class, unmapped),
                "a partial projection is still total: an unrecognized wire name comes back unchanged, "
                        + "never null and never as an exception");
        assertEquals(
                "",
                partial.logicalName(Holder.class, ""),
                "the empty wire name is unrecognized like any other and is returned unchanged");
        assertNotNull(
                partial.logicalName(Holder.class, "userName"),
                "a Java name arriving on the wire is unrecognized by a wire→Java projection and must "
                        + "still resolve to itself, not to null");
    }

    /** Owner type stand-in — the resolver is consulted with the type that declares the field. */
    static final class Holder {
        String userName;
    }
}
