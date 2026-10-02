// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.publication;

import dev.vertique.rest.jaxrs.publication.OperationPublication;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a published JAX-RS operation's Vert.x route can answer a document URL.
 *
 * <p>The decision reproduces how Vert.x Web routes a request on a sub-router mounted at the JAX-RS
 * mount path, from the route value exactly as the operation registered it:
 *
 * <ul>
 *   <li>only a {@code GET} or {@code HEAD} operation can answer, since documents are requested with
 *       those methods;
 *   <li>the mount point is the mount path without its trailing {@code *}, and the URL must start with
 *       it (literal, case-sensitive);
 *   <li>a regex route matches when its pattern fully matches the URL relative to the mount point;
 *   <li>a route with {@code :} path parameters is compiled to the same pattern Vert.x builds: the
 *       characters {@code ( ) $ + .} are escaped, a trailing {@code *} becomes a capture of the rest,
 *       each parameter token of Vert.x's parameter-name grammar (the extended grammar when the system
 *       property {@code io.vertx.web.route.param.extended-pattern} is {@code true}) becomes one or more
 *       characters other than {@code /}, and an exact path not ending in {@code /} accepts an optional
 *       trailing {@code /};
 *   <li>any other route is compared with the URL as a literal path, exactly or as a prefix when it
 *       ends with {@code *}, with Vert.x's handling of the trailing slash.
 * </ul>
 *
 * <p>The matcher decides by path and method only and ignores consumes/produces and virtual-host
 * constraints, so it may report a route that Vert.x would answer with 415/406, or not at all, as
 * colliding: it can over-refuse but never under-refuse.
 */
final class DocumentRouteMatcher {

    /** The system property that selects Vert.x's extended path-parameter name grammar. */
    private static final String EXTENDED_PATTERN_PROPERTY = "io.vertx.web.route.param.extended-pattern";

    /** Vert.x's path-parameter name grammar, read once as Vert.x reads it. */
    private static final String VAR_NAME =
            Boolean.getBoolean(EXTENDED_PATTERN_PROPERTY) ? "[A-Za-z_$][A-Za-z0-9_$-]*" : "[A-Za-z0-9_]+";

    /** A {@code :<name>} path-parameter token. */
    private static final Pattern TOKEN = Pattern.compile(":(" + VAR_NAME + ")");

    /** The regex operators Vert.x escapes in a parameterized path. */
    private static final Pattern OPERATORS_NO_STAR = Pattern.compile("([\\(\\)\\$\\+\\.])");

    private DocumentRouteMatcher() {}

    /**
     * Reports whether the route of an operation can answer a document URL.
     *
     * @param mountPath the mount path of the application that publishes the operation, as registered
     * @param operation the published operation
     * @param documentUrl the document URL, including the documentation prefix
     * @return {@code true} when the operation is a {@code GET} or {@code HEAD} operation whose route
     *     Vert.x would match for the URL
     */
    static boolean canAnswer(String mountPath, OperationPublication operation, String documentUrl) {
        String method = operation.httpMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String mountPoint = mountPath.endsWith("*") ? mountPath.substring(0, mountPath.length() - 1) : mountPath;
        if (mountPoint.isEmpty() || !documentUrl.startsWith(mountPoint)) {
            return false;
        }
        String route = operation.vertxRouteValue();
        if (operation.vertxRouteIsRegex()) {
            return Pattern.compile(route)
                    .matcher(relative(mountPoint, documentUrl))
                    .matches();
        }
        if (route.indexOf(':') >= 0) {
            return parameterPattern(route)
                    .matcher(relative(mountPoint, documentUrl))
                    .matches();
        }
        return literalMatches(mountPoint, route, documentUrl);
    }

    /**
     * Returns the URL relative to the mount point, keeping the leading {@code /} when the mount point
     * ends with one.
     */
    private static String relative(String mountPoint, String documentUrl) {
        int strip = mountPoint.length();
        if (mountPoint.charAt(strip - 1) == '/') {
            strip--;
        }
        return documentUrl.substring(strip);
    }

    /** Builds the pattern Vert.x compiles for a route path holding {@code :} parameter tokens. */
    private static Pattern parameterPattern(String route) {
        String stripped = route.endsWith("*") ? route.substring(0, route.length() - 1) : route;
        boolean pathEndsWithSlash = stripped.endsWith("/");
        String path = OPERATORS_NO_STAR.matcher(route).replaceAll("\\\\$1");
        boolean exactPath;
        if (path.charAt(path.length() - 1) == '*') {
            path = path.substring(0, path.length() - 1) + "(?<rest>.*)";
            exactPath = false;
        } else {
            exactPath = true;
        }
        Matcher tokens = TOKEN.matcher(path);
        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (tokens.find()) {
            tokens.appendReplacement(regex, "(?<p" + index + ">[^/]+)");
            index++;
        }
        tokens.appendTail(regex);
        if (exactPath && !pathEndsWithSlash) {
            regex.append("/?");
        }
        return Pattern.compile(regex.toString());
    }

    /** Compares a literal route path with the URL as Vert.x does for a route on a sub-router. */
    private static boolean literalMatches(String mountPoint, String route, String documentUrl) {
        boolean exactPath = route.charAt(route.length() - 1) != '*';
        String path = exactPath ? route : route.substring(0, route.length() - 1);
        boolean routeEndsWithSlash = path.endsWith("/");
        boolean mountPointEndsWithSlash = mountPoint.charAt(mountPoint.length() - 1) == '/';
        String fullPath;
        boolean pathEndsWithSlash;
        if (path.length() == 1) {
            fullPath = mountPoint;
            pathEndsWithSlash = mountPointEndsWithSlash;
        } else {
            fullPath = mountPointEndsWithSlash ? mountPoint + path.substring(1) : mountPoint + path;
            pathEndsWithSlash = routeEndsWithSlash;
        }
        if (exactPath) {
            return matchesExact(fullPath, documentUrl, pathEndsWithSlash);
        }
        if (pathEndsWithSlash) {
            int pathLength = fullPath.length();
            int urlLength = documentUrl.length();
            if (urlLength < pathLength - 2) {
                return false;
            }
            if (urlLength == pathLength - 1 && fullPath.regionMatches(0, documentUrl, 0, pathLength - 1)) {
                return true;
            }
        }
        return documentUrl.startsWith(fullPath);
    }

    /** Compares an exact route path with the URL, ignoring or requiring a trailing slash as Vert.x does. */
    private static boolean matchesExact(String base, String url, boolean significantSlash) {
        int length = url.length();
        if (significantSlash) {
            if (url.charAt(length - 1) != '/') {
                return false;
            }
        } else if (url.charAt(length - 1) == '/') {
            length--;
            return base.length() == length && url.regionMatches(0, base, 0, length);
        }
        return url.equals(base);
    }
}
