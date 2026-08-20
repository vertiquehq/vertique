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
