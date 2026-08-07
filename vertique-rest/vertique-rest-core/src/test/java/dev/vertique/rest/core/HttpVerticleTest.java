// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterCustomizer;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link HttpVerticle}.
 *
 * <p>Covers mount path validation, priority-based ordering, customizer phase execution,
 * and {@link MountCustomizer} matching semantics. All tests deploy {@link HttpVerticle}
 * on port {@code 0} (random) to avoid port conflicts.
 */
@ExtendWith(VertxExtension.class)
class HttpVerticleTest {

    // --- Mount path validation (fail-fast) ---

    @Test
    @DisplayName("Should reject mount path without leading slash")
    void shouldRejectMountPathWithoutLeadingSlash(Vertx vertx, VertxTestContext ctx) {
        RouterMount badMount = new RouterMount() {
            @Override
            public String mountPath() {
                return "api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(badMount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> assertTrue(err.getMessage().contains("must start with '/'")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Should reject mount path without trailing star")
    void shouldRejectMountPathWithoutTrailingStar(Vertx vertx, VertxTestContext ctx) {
        RouterMount badMount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(badMount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> assertTrue(err.getMessage().contains("must end with '/*'")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Should reject blank mount path")
    void shouldRejectBlankMountPath(Vertx vertx, VertxTestContext ctx) {
        RouterMount badMount = new RouterMount() {
            @Override
            public String mountPath() {
                return " ";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(badMount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> assertTrue(err.getMessage().contains("null or blank")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Should reject mount path with double slash")
    void shouldRejectMountPathWithDoubleSlash(Vertx vertx, VertxTestContext ctx) {
        RouterMount badMount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api//v1/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(badMount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> assertTrue(err.getMessage().contains("double slash")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Should reject mount path with query string")
    void shouldRejectMountPathWithQueryString(Vertx vertx, VertxTestContext ctx) {
        RouterMount badMount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api?v=1/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(badMount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> assertTrue(err.getMessage().contains("query or fragment")));
            ctx.completeNow();
        }));
    }

    // --- Mount priority ordering ---

    @Test
    @DisplayName("Should mount in priority order (lowest priority first)")
    void shouldMountInPriorityOrder(Vertx vertx, VertxTestContext ctx) {
        List<String> mountOrder = new CopyOnWriteArrayList<>();

        RouterMount mountPrio100 = new RouterMount() {
            @Override
            public String mountPath() {
                return "/c/*";
            }

            @Override
            public int priority() {
                return 100;
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                mountOrder.add("prio100");
                return Future.succeededFuture(Router.router(v));
            }
        };

        RouterMount mountPrio0 = new RouterMount() {
            @Override
            public String mountPath() {
                return "/a/*";
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                mountOrder.add("prio0");
                return Future.succeededFuture(Router.router(v));
            }
        };

        RouterMount mountPrio50 = new RouterMount() {
            @Override
            public String mountPath() {
                return "/b/*";
            }

            @Override
            public int priority() {
                return 50;
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                mountOrder.add("prio50");
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(mountPrio100, mountPrio0, mountPrio50),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> {
                assertEquals(3, mountOrder.size(), "All 3 mounts should have been called");
                assertEquals("prio0", mountOrder.get(0), "Priority 0 mount should be first");
                assertEquals("prio50", mountOrder.get(1), "Priority 50 mount should be second");
                assertEquals("prio100", mountOrder.get(2), "Priority 100 mount should be last");
            });
            ctx.completeNow();
        }));
    }

    // --- Deterministic tie-breaking ---

    @Test
    @DisplayName("Should break priority ties by mount path then class name")
    void shouldBreakTiesByMountPathThenClassName(Vertx vertx, VertxTestContext ctx) {
        List<String> mountOrder = new CopyOnWriteArrayList<>();

        // Both have the same priority; /beta/* should come before /gamma/* lexicographically
        RouterMount mountBeta = new RouterMount() {
            @Override
            public String mountPath() {
                return "/beta/*";
            }

            @Override
            public int priority() {
                return 10;
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                mountOrder.add("beta");
                return Future.succeededFuture(Router.router(v));
            }
        };

        RouterMount mountGamma = new RouterMount() {
            @Override
            public String mountPath() {
                return "/gamma/*";
            }

            @Override
            public int priority() {
                return 10;
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                mountOrder.add("gamma");
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(mountGamma, mountBeta),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> {
                assertEquals(2, mountOrder.size());
                assertEquals("beta", mountOrder.get(0), "/beta/* should be mounted before /gamma/*");
                assertEquals("gamma", mountOrder.get(1));
            });
            ctx.completeNow();
        }));
    }

    // --- Empty mount set ---

    @Test
    @DisplayName("Should start successfully with no mounts")
    void shouldStartWithNoMounts(Vertx vertx, VertxTestContext ctx) {
        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0), Set.of(), Set.of(), Set.of(), Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> ctx.completeNow()));
    }

    // --- Fail-fast on mount failure ---

    @Test
    @DisplayName("Should fail startup when createRouter returns a failed Future")
    void shouldFailStartupWhenMountFails(Vertx vertx, VertxTestContext ctx) {
        RouterMount failingMount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/fail/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.failedFuture(new RuntimeException("router creation failed"));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(failingMount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> assertEquals("router creation failed", err.getMessage()));
            ctx.completeNow();
        }));
    }

    // --- RouterCustomizer phases ---

    @Test
    @DisplayName("Should run BEFORE_MOUNTS customizer before any mount's createRouter")
    void shouldRunBeforeMountsCustomizersBeforeMounts(Vertx vertx, VertxTestContext ctx) {
        List<String> callOrder = new CopyOnWriteArrayList<>();

        RouterCustomizer beforeCustomizer = router -> callOrder.add("BEFORE_MOUNTS");

        RouterMount mount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                callOrder.add("createRouter");
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(beforeCustomizer),
                Set.of(),
                Set.of(mount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> {
                assertEquals(2, callOrder.size());
                assertEquals("BEFORE_MOUNTS", callOrder.get(0), "BEFORE_MOUNTS customizer must run before mount");
                assertEquals("createRouter", callOrder.get(1));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Should run AFTER_MOUNTS customizer after all mounts")
    void shouldRunAfterMountsCustomizersAfterMounts(Vertx vertx, VertxTestContext ctx) {
        List<String> callOrder = new CopyOnWriteArrayList<>();

        RouterCustomizer afterCustomizer = new RouterCustomizer() {
            @Override
            public void customize(Router router) {
                callOrder.add("AFTER_MOUNTS");
            }

            @Override
            public MountPhase mountPhase() {
                return MountPhase.AFTER_MOUNTS;
            }
        };

        RouterMount mount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                callOrder.add("createRouter");
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(afterCustomizer),
                Set.of(),
                Set.of(mount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> {
                assertEquals(2, callOrder.size());
                assertEquals("createRouter", callOrder.get(0), "Mount createRouter must run before AFTER_MOUNTS");
                assertEquals("AFTER_MOUNTS", callOrder.get(1));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Default mountPhase() is BEFORE_MOUNTS; AFTER_MOUNTS customizer runs after mounts")
    void mountPhaseDefaultAndAfterMountsOrdering(Vertx vertx, VertxTestContext ctx) {
        // Verify the default: a lambda RouterCustomizer has mountPhase() == BEFORE_MOUNTS
        RouterCustomizer lambda = router -> {};
        assertEquals(
                RouterCustomizer.MountPhase.BEFORE_MOUNTS,
                lambda.mountPhase(),
                "Default mountPhase() must be BEFORE_MOUNTS");

        // Verify an AFTER_MOUNTS customizer runs after createRouter()
        List<String> callOrder = new CopyOnWriteArrayList<>();

        RouterCustomizer afterCustomizer = new RouterCustomizer() {
            @Override
            public void customize(Router router) {
                callOrder.add("AFTER");
            }

            @Override
            public MountPhase mountPhase() {
                return MountPhase.AFTER_MOUNTS;
            }
        };

        RouterMount mount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                callOrder.add("MOUNT");
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(afterCustomizer),
                Set.of(),
                Set.of(mount),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> {
                assertEquals(2, callOrder.size());
                assertEquals("MOUNT", callOrder.get(0), "Mount createRouter must run before AFTER_MOUNTS customizer");
                assertEquals("AFTER", callOrder.get(1));
            });
            ctx.completeNow();
        }));
    }

    // --- MountCustomizer ---

    @Test
    @DisplayName("Should apply MountCustomizer when it matches the mount")
    void shouldApplyMatchingMountCustomizers(Vertx vertx, VertxTestContext ctx) {
        List<String> customized = new CopyOnWriteArrayList<>();

        MountCustomizer matchingCustomizer = new MountCustomizer() {
            @Override
            public boolean matches(MountMeta meta) {
                return meta.mountPath().equals("/api/*");
            }

            @Override
            public void customize(Router mountRouter, MountMeta meta) {
                customized.add("applied:" + meta.mountPath());
            }
        };

        RouterMount mount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(mount),
                Set.of(matchingCustomizer));

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> {
                assertEquals(1, customized.size(), "Matching MountCustomizer should be applied once");
                assertEquals("applied:/api/*", customized.get(0));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Should skip MountCustomizer when it does not match the mount")
    void shouldSkipNonMatchingMountCustomizers(Vertx vertx, VertxTestContext ctx) {
        List<String> customized = new CopyOnWriteArrayList<>();

        MountCustomizer nonMatchingCustomizer = new MountCustomizer() {
            @Override
            public boolean matches(MountMeta meta) {
                return meta.mountPath().equals("/other/*");
            }

            @Override
            public void customize(Router mountRouter, MountMeta meta) {
                customized.add("applied:" + meta.mountPath());
            }
        };

        RouterMount mount = new RouterMount() {
            @Override
            public String mountPath() {
                return "/api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx v) {
                return Future.succeededFuture(Router.router(v));
            }
        };

        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(mount),
                Set.of(nonMatchingCustomizer));

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            ctx.verify(() -> assertTrue(customized.isEmpty(), "Non-matching MountCustomizer must not be applied"));
            ctx.completeNow();
        }));
    }
}
