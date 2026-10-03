// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import io.vertx.ext.web.Router;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T006 TP-001's per-mount event correlator: joins {@code RouterLifecycleHook.afterRouterCreated}
 * (which receives only the {@link Router}), {@code MountPublicationHook.mountBuilt} (which
 * receives only the mount path, via the {@link io.vertx.core.json.JsonObject}-free
 * {@code MountPublication.mountPath()}), and {@code MountCustomizer.customize} (which receives
 * both) into one ordered event list per mount path.
 *
 * <p>Because {@code HttpVerticle} composes mounts with a strictly sequential {@code Future.compose}
 * chain (one mount's {@code createRouter} and its {@code MountCustomizer} calls complete before the
 * next mount's {@code createRouter} starts), at most one mount's events are ever "in flight": a
 * single pending slot, finalized by {@link #customize}, is enough to build one ordered list per
 * mount. {@link #customize} also asserts that the {@link Router} instance it receives is the exact
 * same instance {@link #afterRouterCreated} recorded for the mount now being finalized (E13).
 */
public final class PublicationEventRecorder {

    private final Map<String, List<String>> eventsByMountPath = new LinkedHashMap<>();
    private final List<String> pendingEvents = new ArrayList<>();
    private Router pendingRouter;
    private String pendingMountPath;

    /**
     * Records an {@code afterRouterCreated} call in the pending event group. Events already pending are kept,
     * so a {@code mountBuilt} that arrives before this call stays visible to the order assertion.
     *
     * @param router the router {@code RouterLifecycleHook.afterRouterCreated} received
     */
    public synchronized void afterRouterCreated(Router router) {
        pendingEvents.add("afterRouterCreated");
        pendingRouter = router;
        pendingMountPath = null;
    }

    /**
     * Records a {@code mountBuilt} call for the given mount path. For an empty mount, no preceding
     * {@link #afterRouterCreated} call ever happens, so this opens the pending group instead.
     *
     * @param mountPath the publication's mount path
     */
    public synchronized void mountBuilt(String mountPath) {
        pendingEvents.add("mountBuilt");
        pendingMountPath = mountPath;
    }

    /**
     * Records a {@code customize} call, finalizing the pending event group for {@code mountPath}. A
     * {@code MountCustomizer} runs for every {@code RouterMount} in the composition, including a
     * non-JAX-RS mount this recorder was never told about (no {@link #afterRouterCreated} or
     * {@link #mountBuilt} call ever precedes it); such a call has no pending group to finalize and is
     * ignored.
     *
     * @param router    the router {@code MountCustomizer.customize} received
     * @param mountPath the mount path {@code MountCustomizer.customize}'s {@code MountMeta} names
     * @throws AssertionError if {@code router} is not the same instance {@link #afterRouterCreated}
     *                        recorded for the pending group this call finalizes
     */
    public synchronized void customize(Router router, String mountPath) {
        if (pendingMountPath == null || !pendingMountPath.equals(mountPath)) {
            return;
        }
        if (pendingRouter != null && pendingRouter != router) {
            throw new AssertionError(
                    "customize for '" + mountPath + "' received a different Router instance than afterRouterCreated");
        }
        pendingEvents.add("customize");
        eventsByMountPath.put(mountPath, List.copyOf(pendingEvents));
        pendingEvents.clear();
        pendingRouter = null;
        pendingMountPath = null;
    }

    /**
     * Returns the finalized ordered event list for the given mount path.
     *
     * @param mountPath the mount path to look up
     * @return the ordered events ({@code afterRouterCreated}, {@code mountBuilt}, {@code customize}
     *     for a non-empty mount; {@code mountBuilt}, {@code customize} for an empty mount)
     * @throws AssertionError if no event group was ever finalized for {@code mountPath}
     */
    public synchronized List<String> eventsFor(String mountPath) {
        List<String> events = eventsByMountPath.get(mountPath);
        if (events == null) {
            throw new AssertionError("no finalized event list recorded for mount '" + mountPath + "'");
        }
        return events;
    }
}
