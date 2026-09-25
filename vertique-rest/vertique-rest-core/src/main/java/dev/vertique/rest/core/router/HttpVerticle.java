// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Generic HTTP server verticle that composes a main router from {@link RouterMount}
 * sub-routers, {@link Middleware} handlers, and {@link RouterCustomizer} hooks.
 *
 * <p>Extension points:
 * <ul>
 *   <li>{@link RouterMount} — provide sub-routers mounted at specific paths</li>
 *   <li>{@link MountCustomizer} — customize individual mount routers after creation</li>
 *   <li>{@link RouterCustomizer} — customize the main router (before or after mounts)</li>
 *   <li>{@link Middleware} — auto-registered request handlers (ROOT scope on main router)</li>
 *   <li>{@link HttpServerOptions} — configure SSL, compression, etc.</li>
 *   <li>{@link HttpConfig} — HTTP server configuration (port, body size, timeouts, SSL, etc.)</li>
 * </ul>
 *
 * <p>Startup flow:
 * <ol>
 *   <li>Validate and sort mount paths (fail-fast on invalid paths)</li>
 *   <li>Run {@link MountCompositionValidator}s once mount paths are valid (fail-fast on any violation)</li>
 *   <li>Create main router and mount ROOT-scoped middlewares</li>
 *   <li>Run {@link RouterCustomizer.MountPhase#BEFORE_MOUNTS} customizers</li>
 *   <li>Create each mount's router, apply {@link MountCustomizer}s, mount as sub-router</li>
 *   <li>Run {@link RouterCustomizer.MountPhase#AFTER_MOUNTS} customizers</li>
 *   <li>Start HTTP server on the port from {@link HttpConfig#getPort()}</li>
 * </ol>
 */
@Slf4j
public class HttpVerticle extends AbstractVerticle {

    private final HttpServerOptions serverOptions;
    private final Set<RouterCustomizer> routerCustomizers;
    private final Set<Middleware> middlewares;
    private final Set<RouterMount> routerMounts;
    private final Set<MountCustomizer> mountCustomizers;
    private final Set<MountCompositionValidator> mountCompositionValidators;

    /**
     * Creates a new {@code HttpVerticle} with all required HTTP server and routing dependencies,
     * running no {@link MountCompositionValidator}.
     *
     * @param serverOptions     Vert.x {@link HttpServerOptions} (built from {@link HttpConfig} by the DI module)
     * @param routerCustomizers customizers applied to the main router before or after mounts
     * @param middlewares       scoped request handlers mounted automatically on the main router
     * @param routerMounts      sub-routers to compose into the main router
     * @param mountCustomizers  per-mount customization hooks applied after each router is created
     */
    public HttpVerticle(
            HttpServerOptions serverOptions,
            Set<RouterCustomizer> routerCustomizers,
            Set<Middleware> middlewares,
            Set<RouterMount> routerMounts,
            Set<MountCustomizer> mountCustomizers) {
        this(serverOptions, routerCustomizers, middlewares, routerMounts, mountCustomizers, Set.of());
    }

    /**
     * Creates a new {@code HttpVerticle} with all required HTTP server and routing dependencies,
     * including the {@link MountCompositionValidator}s to run before any mount router is created.
     *
     * @param serverOptions              Vert.x {@link HttpServerOptions} (built from {@link HttpConfig} by the DI module)
     * @param routerCustomizers          customizers applied to the main router before or after mounts
     * @param middlewares                scoped request handlers mounted automatically on the main router
     * @param routerMounts               sub-routers to compose into the main router
     * @param mountCustomizers           per-mount customization hooks applied after each router is created
     * @param mountCompositionValidators INTERNAL composition validators run once mount paths are valid,
     *                                   before any mount router is created
     */
    @Inject
    HttpVerticle(
            HttpServerOptions serverOptions,
            Set<RouterCustomizer> routerCustomizers,
            Set<Middleware> middlewares,
            Set<RouterMount> routerMounts,
            Set<MountCustomizer> mountCustomizers,
            Set<MountCompositionValidator> mountCompositionValidators) {
        this.serverOptions = serverOptions;
        this.routerCustomizers = routerCustomizers;
        this.middlewares = middlewares;
        this.routerMounts = routerMounts;
        this.mountCustomizers = mountCustomizers;
        this.mountCompositionValidators = mountCompositionValidators;
    }

    /**
     * Starts the HTTP server by composing the main router from all registered mounts,
     * applying customizers and middlewares, then binding to the configured port.
     *
     * <p>Fails the {@code startPromise} immediately if any mount path is invalid or if
     * the HTTP server fails to bind.
     *
     * @param startPromise the promise to complete when the server is ready, or fail on error
     */
    @Override
    public void start(Promise<Void> startPromise) {
        // 1. Sort mounts deterministically: phase ASC, priority ASC, mountPath ASC, orderKey ASC.
        // mountPath is retained as an explicit tie-break before orderKey to preserve the historical
        // behaviour where more-specific paths (e.g. /api/*) mount before broader catch-alls (e.g. /*).
        List<RouterMount> sortedMounts = routerMounts.stream()
                .sorted(Comparator.comparing(RouterMount::phase)
                        .thenComparingInt(RouterMount::priority)
                        .thenComparing(RouterMount::mountPath)
                        .thenComparing(RouterMount::orderKey))
                .toList();

        // 2. Validate mount paths
        List<String> violations = validateMountPaths(sortedMounts);
        if (!violations.isEmpty()) {
            startPromise.fail(
                    new IllegalStateException("Invalid mount configuration:\n  " + String.join("\n  ", violations)));
            return;
        }

        // 3. Run composition validators once mount paths are valid, before any mount router is created
        List<String> validatorViolations = new ArrayList<>();
        try {
            for (MountCompositionValidator validator : mountCompositionValidators) {
                validatorViolations.addAll(validator.validate(sortedMounts));
            }
        } catch (RuntimeException validatorException) {
            startPromise.fail(validatorException);
            return;
        }
        if (!validatorViolations.isEmpty()) {
            List<String> sortedViolations =
                    validatorViolations.stream().sorted().toList();
            startPromise.fail(new IllegalStateException(
                    "Invalid mount configuration:\n  " + String.join("\n  ", sortedViolations)));
            return;
        }

        // 4. Detect overlapping mounts (log warnings, don't fail)
        detectOverlaps(sortedMounts);

        // 5. Sort mount customizers: phase ASC, priority ASC, orderKey ASC (full OrderedExtension contract)
        List<MountCustomizer> sortedCustomizers =
                mountCustomizers.stream().sorted(OrderedExtension.comparator()).toList();

        // 6. Create main router
        Router mainRouter = Router.router(vertx);

        // 7. Mount ROOT-scoped middlewares sorted by OrderedExtension order (phase → priority → orderKey)
        middlewares.stream()
                .filter(m -> m.scope() == MiddlewareScope.ROOT)
                .sorted(OrderedExtension.comparator())
                .forEach(m -> mainRouter.route(m.path()).handler(m));

        // 8. Run BEFORE_MOUNTS customizers (sorted by OrderedExtension order: phase → priority → orderKey)
        routerCustomizers.stream()
                .filter(c -> c.mountPhase() == RouterCustomizer.MountPhase.BEFORE_MOUNTS)
                .sorted(OrderedExtension.comparator())
                .forEach(c -> c.customize(mainRouter));

        // 9. Create and mount all routers sequentially (to preserve ordering)
        Future<Void> chain = Future.succeededFuture();
        for (RouterMount mount : sortedMounts) {
            chain = chain.compose(v -> mount.createRouter(vertx).map(router -> {
                // Apply matching MountCustomizers
                MountMeta meta = mount.meta();
                for (MountCustomizer customizer : sortedCustomizers) {
                    if (customizer.matches(meta)) {
                        customizer.customize(router, meta);
                    }
                }
                mainRouter.route(mount.mountPath()).subRouter(router);
                log.info(
                        "Mounted router at {} (priority={}, id={})",
                        mount.mountPath(),
                        mount.priority(),
                        meta.mountId());
                return (Void) null;
            }));
        }

        // 10. Run AFTER_MOUNTS customizers (sorted by OrderedExtension order: phase → priority → orderKey)
        chain.compose(v -> {
                    routerCustomizers.stream()
                            .filter(c -> c.mountPhase() == RouterCustomizer.MountPhase.AFTER_MOUNTS)
                            .sorted(OrderedExtension.comparator())
                            .forEach(c -> c.customize(mainRouter));

                    return vertx.createHttpServer(serverOptions)
                            .requestHandler(mainRouter)
                            .listen();
                })
                .onSuccess(server -> {
                    vertx.sharedData().getLocalMap("vertique").put("http.port", server.actualPort());
                    log.info("HTTP server started on port {}", server.actualPort());
                    startPromise.complete();
                })
                .onFailure(cause -> {
                    log.error("Failed to start HTTP server", cause);
                    startPromise.fail(cause);
                });
    }

    /**
     * Validates all mount paths for structural correctness. Each path must be non-blank,
     * start with {@code /}, end with {@code /*}, and contain no double slashes, query
     * strings, or fragment identifiers.
     *
     * @param mounts the ordered list of mounts to validate
     * @return a list of human-readable violation messages; empty if all paths are valid
     */
    private List<String> validateMountPaths(List<RouterMount> mounts) {
        List<String> violations = new ArrayList<>();
        for (RouterMount mount : mounts) {
            String path = mount.mountPath();
            String id = mount.meta().mountId();
            if (path == null || path.isBlank()) {
                violations.add("Mount " + id + " has null or blank mountPath");
            } else {
                if (!path.startsWith("/")) {
                    violations.add("Mount " + id + " path must start with '/': " + path);
                }
                if (!path.endsWith("/*")) {
                    violations.add("Mount " + id + " path must end with '/*': " + path);
                }
                if (path.contains("//")) {
                    violations.add("Mount " + id + " path contains double slash: " + path);
                }
                if (path.contains("?") || path.contains("#")) {
                    violations.add("Mount " + id + " path contains query or fragment: " + path);
                }
            }
        }
        return violations;
    }

    /**
     * Detects and logs warnings for duplicate and overlapping mount paths. Duplicate paths
     * result in only the first-mounted router being reachable. Containment overlaps (where
     * one path is a prefix of another) cause requests matching both to be handled exclusively
     * by the first-mounted router.
     *
     * @param mounts the ordered list of mounts to inspect for overlaps
     */
    private void detectOverlaps(List<RouterMount> mounts) {
        List<String> paths = mounts.stream().map(RouterMount::mountPath).toList();

        // Check for duplicates
        Map<String, Long> pathCounts = paths.stream().collect(Collectors.groupingBy(p -> p, Collectors.counting()));
        pathCounts.forEach((path, count) -> {
            if (count > 1) {
                log.warn(
                        "Duplicate mount path '{}' registered {} times; only the first-mounted is reachable",
                        path,
                        count);
            }
        });

        // Check for containment overlaps (more specific path shadowed by broader one)
        for (int i = 0; i < paths.size(); i++) {
            String outer = paths.get(i);
            String outerPrefix = outer.substring(0, outer.length() - 1); // strip trailing *
            for (int j = i + 1; j < paths.size(); j++) {
                String inner = paths.get(j);
                if (!inner.equals(outer) && inner.startsWith(outerPrefix)) {
                    log.warn(
                            "Mount path '{}' (priority={}) is a prefix of '{}' (priority={}); "
                                    + "requests matching both will be handled by the first-mounted",
                            outer,
                            mounts.get(i).priority(),
                            inner,
                            mounts.get(j).priority());
                }
            }
        }
    }
}
