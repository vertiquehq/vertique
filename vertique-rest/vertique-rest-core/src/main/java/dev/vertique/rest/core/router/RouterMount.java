// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.Set;

/**
 * Provides a sub-router to be mounted on the main HTTP router at a specified path.
 *
 * <p>Implementations produce a {@link Router} (potentially asynchronously) that
 * {@link HttpVerticle} mounts on the main router at {@link #mountPath()}.
 *
 * <p><b>Ordering:</b> mounts are sorted by {@link OrderedExtension} fields — phase first, then
 * {@link #priority()} ascending — with {@link #mountPath()} as an additional tie-break before
 * {@link #orderKey()} (see {@link HttpVerticle}). This ensures that, within the same phase and
 * priority, more-specific paths (e.g. {@code /api/*}) are mounted before broader catch-alls
 * (e.g. {@code /\*}), preserving the historical path-based tie-break behaviour.
 *
 * <p>Register via Dagger multibinding:
 * <pre>
 * {@literal @}Provides {@literal @}IntoSet
 * RouterMount healthMount() {
 *     return new RouterMount() {
 *         {@literal @}Override public String mountPath() { return "/health/*"; }
 *         {@literal @}Override public Future&lt;Router&gt; createRouter(Vertx vertx) {
 *             Router r = Router.router(vertx);
 *             r.get("/").handler(ctx -&gt; ctx.response().end("OK"));
 *             return Future.succeededFuture(r);
 *         }
 *     };
 * }
 * </pre>
 *
 * @see OrderedExtension
 */
public interface RouterMount extends OrderedExtension {

    /**
     * Path prefix where this sub-router is mounted. Must start with {@code /} and end with
     * {@code /*}.
     *
     * @return the mount path pattern (default {@code "/*"})
     */
    default String mountPath() {
        return "/*";
    }

    /**
     * Creates the {@link Router} for this mount. The returned future may be asynchronous
     * (e.g. loading an OpenAPI contract). If the future fails, {@link HttpVerticle} fails
     * startup immediately.
     *
     * @param vertx the Vert.x instance
     * @return a future resolving to the configured router
     */
    Future<Router> createRouter(Vertx vertx);

    /**
     * Metadata describing this mount, used by {@link MountCustomizer#matches(MountMeta)}.
     *
     * @return mount metadata (defaults to FQCN-based id)
     */
    default MountMeta meta() {
        return new MountMeta(getClass().getName(), mountPath(), null, Set.of());
    }
}
