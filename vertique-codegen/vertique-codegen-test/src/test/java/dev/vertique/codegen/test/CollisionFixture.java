// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

/**
 * Test-only fixture used by the parent-collision regression test for
 * {@link ProcessorTestHarness.Result#loadGeneratedClass(String)}.
 *
 * <p>This class is on the parent (test) classpath. The regression test compiles an inline
 * source with the same fully-qualified name and asserts that
 * {@code loadGeneratedClass} returns the in-memory compiled bytes — discoverable by the
 * different value of {@link #SOURCE} — rather than this parent-classpath copy.
 */
public final class CollisionFixture {

    /** Marker that distinguishes this parent-classpath copy from compiled-in-memory copies. */
    public static final String SOURCE = "test-classpath";

    private CollisionFixture() {}
}
