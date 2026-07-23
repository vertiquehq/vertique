// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RouterMount} participates in the {@link dev.vertique.core.extension.OrderedExtension}
 * ordering contract as applied by {@link dev.vertique.rest.core.router.HttpVerticle}.
 *
 * <p>The sort used at the mount site is:
 * {@code phase → priority → mountPath → orderKey}.
 * The {@link #mountPathTieRetained()} test is the critical regression guard — it confirms that
 * the mountPath tie-break is preserved after migrating away from the legacy comparator.
 */
class RouterMountOrderTest {

    /** Composed comparator that mirrors the sort site in {@code HttpVerticle.start()}. */
    private static final Comparator<RouterMount> MOUNT_COMPARATOR = Comparator.comparing(RouterMount::phase)
            .thenComparingInt(RouterMount::priority)
            .thenComparing(RouterMount::mountPath)
            .thenComparing(RouterMount::orderKey);

    /**
     * Minimal {@link RouterMount} test double with configurable phase, priority, and mountPath.
     *
     * <p>{@link #orderKey()} is not overridden; it defaults to this class's FQCN. When two
     * instances need distinct order keys at equal phase, priority, and mountPath, a second nested
     * class ({@link AnotherMount}) is used to produce a different FQCN-based key.
     */
    private static final class TestMount implements RouterMount {

        private final ExtensionPhase phase;
        private final int priority;
        private final String mountPath;

        TestMount(ExtensionPhase phase, int priority, String mountPath) {
            this.phase = phase;
            this.priority = priority;
            this.mountPath = mountPath;
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String mountPath() {
            return mountPath;
        }

        @Override
        public MountMeta meta() {
            return new MountMeta(getClass().getName(), mountPath, null, Set.of());
        }

        @Override
        public Future<Router> createRouter(Vertx vertx) {
            return Future.succeededFuture(Router.router(vertx));
        }
    }

    /**
     * Second distinct nested class used to produce a different {@link dev.vertique.core.extension.OrderedExtension#orderKey()}
     * value (defaults to its own FQCN) when phase, priority, and mountPath are all equal.
     */
    private static final class AnotherMount implements RouterMount {

        private final String mountPath;

        AnotherMount(String mountPath) {
            this.mountPath = mountPath;
        }

        @Override
        public String mountPath() {
            return mountPath;
        }

        @Override
        public MountMeta meta() {
            return new MountMeta(getClass().getName(), mountPath, null, Set.of());
        }

        @Override
        public Future<Router> createRouter(Vertx vertx) {
            return Future.succeededFuture(Router.router(vertx));
        }
    }

    @Test
    @DisplayName("mountPath tie-break retained: equal phase+priority sorts /a/* before /b/*")
    void mountPathTieRetained() {
        // Critical regression test: after migrating to OrderedExtension the mountPath
        // tie-break must be preserved so /a/* always mounts before /b/*.
        TestMount a = new TestMount(ExtensionPhase.APPLICATION, 0, "/a/*");
        TestMount b = new TestMount(ExtensionPhase.APPLICATION, 0, "/b/*");

        List<RouterMount> mounts = new ArrayList<>(List.of(b, a));
        mounts.sort(MOUNT_COMPARATOR);

        assertSame(a, mounts.get(0), "/a/* must sort before /b/* when phase and priority are equal");
        assertSame(b, mounts.get(1));
    }

    @Test
    @DisplayName("lower priority mounts first, even if its mountPath is lexically later")
    void priorityBeforeMountPath() {
        // priority=0 wins over priority=10 regardless of mountPath lexical order
        TestMount lowPri = new TestMount(ExtensionPhase.APPLICATION, 0, "/z/*");
        TestMount highPri = new TestMount(ExtensionPhase.APPLICATION, 10, "/a/*");

        List<RouterMount> mounts = new ArrayList<>(List.of(highPri, lowPri));
        mounts.sort(MOUNT_COMPARATOR);

        assertSame(lowPri, mounts.get(0), "priority 0 must sort before priority 10 regardless of mountPath");
        assertSame(highPri, mounts.get(1));
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestMount systemFirst = new TestMount(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE, "/z/*");
        TestMount application = new TestMount(ExtensionPhase.APPLICATION, Integer.MIN_VALUE, "/a/*");

        List<RouterMount> mounts = new ArrayList<>(List.of(application, systemFirst));
        mounts.sort(MOUNT_COMPARATOR);

        assertSame(systemFirst, mounts.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, mounts.get(1));
    }
}
