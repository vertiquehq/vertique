// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes a method of a dependency-injected application type as a Model Context Protocol tool.
 *
 * <p>The declaring type and the annotated method are {@code public}. Abstract, static, private,
 * protected, package-private, bridge, synthetic, and overloaded tool methods are rejected at
 * compile time. The annotation processor generates a direct invoker for every accepted method;
 * there is no reflective invocation fallback and no classpath scanning.
 *
 * <p>Tool names are explicit, unique, case-sensitive, 1–128 characters long, and match
 * {@code [A-Za-z0-9_.-]+}. A blank {@link #title()} is omitted from the wire. Descriptions are
 * non-blank and at most 4,096 characters.
 *
 * <p>The four hint members carry the tool annotations advertised to clients. They are hints only:
 * a client may not rely on them for safety, and the framework does not enforce them.
 *
 * <p><strong>Execution contract.</strong> The annotated method is invoked on the request-owning
 * Vert.x event loop and must not block it: offload blocking work (I/O, CPU-bound computation, a
 * synchronous client call) onto a worker thread or a non-blocking client instead of running it
 * inline. The returned {@link io.vertx.core.Future} may complete on any thread — a worker thread,
 * an executor callback, a different event loop — and the framework re-anchors the result back onto
 * the request-owning context before continuing; a handler does not need to hop contexts itself. A
 * long-running or cooperative handler should poll
 * {@link dev.vertique.mcp.tool.McpCancellationSignal#isCancelled()} or observe
 * {@link dev.vertique.mcp.tool.McpCancellationSignal#cancelled()} to stop work promptly once the
 * call is cancelled; the framework cannot forcibly stop a handler that ignores the signal.
 *
 * <p><strong>Security: an unannotated tool is public.</strong> A tool method carrying no
 * authorization annotation — no {@code @PermitAll}, {@code @DenyAll}, {@code @RolesAllowed}, or
 * {@code @RequiresAction} — is permitted to every anonymous and authenticated caller, exactly as an
 * unannotated REST resource method is. The absence of an annotation is not a safe default for a
 * side-effecting tool; annotate it explicitly with the access requirement it needs.
 *
 * <pre>{@code
 * @Singleton
 * public class WeatherTools {
 *
 *     @McpTool(name = "weather.lookup", description = "Looks up the current weather", readOnlyHint = true)
 *     public Forecast lookup(@McpToolParam(description = "City name") String city) {
 *         ...
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface McpTool {

    /**
     * The unique protocol name of the tool.
     *
     * @return a 1–128 character name matching {@code [A-Za-z0-9_.-]+}
     */
    String name();

    /**
     * The optional human-readable display title of the tool.
     *
     * @return the display title, or the empty string to omit it from the wire
     */
    String title() default "";

    /**
     * The human-readable description of what the tool does.
     *
     * @return a non-blank description of at most 4,096 characters
     */
    String description();

    /**
     * The top-level client capabilities required before this tool may be invoked.
     *
     * <p>Names are validated by the MCP annotation processor and are emitted into the generated
     * invoker. A request that does not advertise every named capability is rejected before input
     * preparation or application invocation.
     *
     * @return required top-level client capability names, or an empty array when none are required
     */
    String[] requiredClientCapabilities() default {};

    /**
     * Hint that the tool does not modify its environment.
     *
     * @return {@code true} when the tool only reads
     */
    boolean readOnlyHint() default false;

    /**
     * Hint that the tool may perform destructive updates.
     *
     * @return {@code true} when the tool may destroy or overwrite state
     */
    boolean destructiveHint() default true;

    /**
     * Hint that repeated calls with the same arguments have no additional effect.
     *
     * @return {@code true} when the tool is idempotent
     */
    boolean idempotentHint() default false;

    /**
     * Hint that the tool interacts with an open world of external entities.
     *
     * @return {@code true} when the tool reaches beyond a closed, known domain
     */
    boolean openWorldHint() default true;
}
