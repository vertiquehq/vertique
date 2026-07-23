// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import java.util.regex.Pattern;

/**
 * Translates a JAX-RS {@code @Path} template into a Vert.x route, distinguishing plain templates
 * (registered with {@link io.vertx.ext.web.Router#route(io.vertx.core.http.HttpMethod, String)}) from
 * regex-constrained templates (registered with
 * {@link io.vertx.ext.web.Router#routeWithRegex(io.vertx.core.http.HttpMethod, String)}).
 *
 * <p>JAX-RS path parameters take two shapes:
 * <ul>
 *   <li>{@code {name}} — an unconstrained path variable. Translated to the Vert.x colon form
 *       {@code :name}, so {@link io.vertx.ext.web.RoutingContext#pathParams()} binds it by name.</li>
 *   <li>{@code {name:regex}} — a path variable constrained by a regular expression. Because Vert.x's
 *       colon form cannot carry a per-segment constraint, the <em>whole</em> template is translated to
 *       a single anchored regex in which each variable becomes a named capture group
 *       {@code (?<name>regex)} (an unconstrained variable in a regex template uses the default
 *       {@code [^/]+}). Vert.x populates {@code pathParams()} from the regex's named groups, so binding
 *       still happens by name (PRD-REST-017 slice-9 addendum item 5).</li>
 * </ul>
 *
 * <p>A template is treated as regex-constrained — and therefore compiled to the regex form — as soon as
 * any one of its variables carries a {@code :regex} constraint; the remaining unconstrained variables in
 * the same template fall back to {@code [^/]+}.
 */
final class JaxRsPathTemplate {

    /**
     * Matches a single JAX-RS path variable {@code {name}} or {@code {name:regex}}. Group 1 is the
     * variable name; group 2 (optional) is the regex constraint without the leading colon. The regex
     * portion allows nested braces (e.g. {@code \d{3}}) via a reluctant balanced match on non-brace and
     * brace-delimited runs.
     */
    private static final Pattern VARIABLE =
            Pattern.compile("\\{\\s*(\\w[\\w.-]*)\\s*(?::\\s*((?:[^{}]|\\{[^{}]*})+))?\\s*}");

    private final boolean regex;
    private final String vertxValue;

    private JaxRsPathTemplate(boolean regex, String vertxValue) {
        this.regex = regex;
        this.vertxValue = vertxValue;
    }

    /**
     * Translates a JAX-RS path template into its Vert.x route form.
     *
     * @param jaxRsPath the JAX-RS {@code @Path} template, e.g. {@code "/users/{id}"} or
     *                  {@code "/users/{id:\\d+}"}; must not be {@code null}
     * @return the translated template, carrying whether it must be registered as a regex route and the
     *     Vert.x path/regex value to register
     */
    static JaxRsPathTemplate translate(String jaxRsPath) {
        boolean hasConstraint = hasRegexConstraint(jaxRsPath);
        return hasConstraint
                ? new JaxRsPathTemplate(true, toAnchoredRegex(jaxRsPath))
                : new JaxRsPathTemplate(false, toColonForm(jaxRsPath));
    }

    /**
     * Returns whether the route must be registered with
     * {@link io.vertx.ext.web.Router#routeWithRegex(io.vertx.core.http.HttpMethod, String)} (the
     * template carries at least one {@code :regex}-constrained variable) rather than the plain colon
     * form.
     *
     * @return {@code true} for a regex-constrained template, {@code false} for a plain template
     */
    boolean isRegex() {
        return regex;
    }

    /**
     * Returns the Vert.x route value to register: the colon-form path for a plain template, or the
     * anchored regex (with named capture groups) for a regex-constrained template.
     *
     * @return the Vert.x path or regex string
     */
    String vertxValue() {
        return vertxValue;
    }

    /**
     * Returns whether the template carries any {@code :regex}-constrained path variable.
     *
     * @param jaxRsPath the JAX-RS path template
     * @return {@code true} when at least one variable has a regex constraint
     */
    private static boolean hasRegexConstraint(String jaxRsPath) {
        var matcher = VARIABLE.matcher(jaxRsPath);
        while (matcher.find()) {
            if (matcher.group(2) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a plain JAX-RS template to the Vert.x colon form, replacing each {@code {name}} with
     * {@code :name}.
     *
     * @param jaxRsPath the plain JAX-RS path template (no regex constraints)
     * @return the colon-form Vert.x path
     */
    private static String toColonForm(String jaxRsPath) {
        var matcher = VARIABLE.matcher(jaxRsPath);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, ":" + matcher.group(1));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Converts a regex-constrained JAX-RS template to a single anchored Java regex in which each
     * variable becomes a named capture group. A constrained variable {@code {name:regex}} becomes
     * {@code (?<name>regex)}; an unconstrained variable {@code {name}} in the same template becomes
     * {@code (?<name>[^/]+)}. Literal (non-variable) text between variables is regex-quoted so that
     * characters such as {@code .} match literally. The result is anchored with {@code ^…$} so the full
     * path must match.
     *
     * @param jaxRsPath the JAX-RS path template carrying at least one regex constraint
     * @return the anchored regex with named capture groups
     */
    private static String toAnchoredRegex(String jaxRsPath) {
        var matcher = VARIABLE.matcher(jaxRsPath);
        StringBuilder out = new StringBuilder("^");
        int last = 0;
        while (matcher.find()) {
            out.append(Pattern.quote(jaxRsPath.substring(last, matcher.start())));
            String name = matcher.group(1);
            String constraint = matcher.group(2);
            String body = constraint != null ? constraint.trim() : "[^/]+";
            out.append("(?<").append(name).append('>').append(body).append(')');
            last = matcher.end();
        }
        out.append(Pattern.quote(jaxRsPath.substring(last)));
        out.append('$');
        return out.toString();
    }
}
