// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the JAX-RS path templates of a mount's operations as OpenAPI path keys and groups the
 * operations into path items.
 *
 * <p>A path key is the operation's mount-relative JAX-RS template with every variable, {@code
 * {name}} or {@code {name: regex}} with optional whitespace, written as {@code {name}}; the regular
 * expression of a variable never reaches the document. Two templates whose rendered keys have the
 * same literal text with variables at the same positions are equivalent: they must use the same
 * variable names and differ in method, and then share one path item. Any other equivalence fails
 * publication, naming the mount, both routes by method and operation id, and their rendered paths.
 */
final class RenderedPaths {

    /**
     * Matches one JAX-RS path variable, {@code {name}} or {@code {name: regex}}, with the grammar
     * the JAX-RS route registrar uses. Group 1 is the variable name.
     */
    private static final Pattern VARIABLE =
            Pattern.compile("\\{\\s*(\\w[\\w.-]*)\\s*(?::\\s*((?:[^{}]|\\{[^{}]*})+))?\\s*}");

    /** The lowercase methods of a Path Item Object, in the order the document lists them. */
    static final List<String> METHOD_ORDER =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** Orders the routes of one equivalence class by rendered path, then method, then operation id. */
    private static final Comparator<Route> ROUTE_ORDER = Comparator.comparing(Route::path)
            .thenComparing(Route::method)
            .thenComparing(route -> route.operation().operationId());

    /** Orders the operations of one path item by their method's position in a Path Item Object. */
    private static final Comparator<Route> METHOD_POSITION = Comparator.comparingInt(
                    (Route route) -> methodPosition(route.key()))
            .thenComparing(Route::key);

    private RenderedPaths() {}

    /**
     * Renders a JAX-RS path template as an OpenAPI path key.
     *
     * @param template the mount-relative JAX-RS path template
     * @return the template with every variable written as {@code {name}}
     */
    static String render(String template) {
        return VARIABLE.matcher(template).replaceAll(match -> Matcher.quoteReplacement("{" + match.group(1) + "}"));
    }

    /**
     * Groups the operations of a mount into path items.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operations the operations of the mount
     * @return each path key, in natural order, with its operations ordered by method as a Path Item
     *     Object lists them
     * @throws RestConfigurationException when two routes render to equivalent paths but use
     *     different variable names or the same method
     */
    static SortedMap<String, List<OperationPublication>> pathItems(
            String subject, List<OperationPublication> operations) {
        SortedMap<String, List<Route>> byShape = new TreeMap<>();
        for (OperationPublication operation : operations) {
            Route route = new Route(
                    render(operation.jaxRsPathTemplate()),
                    shape(operation.jaxRsPathTemplate()),
                    operation.httpMethod().toUpperCase(Locale.ROOT),
                    operation);
            byShape.computeIfAbsent(route.shape(), shape -> new ArrayList<>()).add(route);
        }
        SortedMap<String, List<OperationPublication>> items = new TreeMap<>();
        for (List<Route> routes : byShape.values()) {
            routes.sort(ROUTE_ORDER);
            checkEquivalent(subject, routes);
            routes.sort(METHOD_POSITION);
            items.put(
                    routes.getFirst().path(),
                    routes.stream().map(Route::operation).toList());
        }
        return items;
    }

    /**
     * Returns the lowercase key of an operation within its path item.
     *
     * @param operation the operation
     * @return the lowercase HTTP method
     */
    static String methodKey(OperationPublication operation) {
        return operation.httpMethod().toLowerCase(Locale.ROOT);
    }

    /**
     * Fails for the first pair, in route order, of equivalent routes that render to different paths
     * or share a method.
     */
    private static void checkEquivalent(String subject, List<Route> routes) {
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                Route first = routes.get(i);
                Route second = routes.get(j);
                if (!first.path().equals(second.path()) || first.method().equals(second.method())) {
                    throw new RestConfigurationException(subject + ": routes " + describe(first) + " and "
                            + describe(second) + " render to equivalent paths; routes at one path must use the"
                            + " same variable names and differ in method");
                }
            }
        }
    }

    /** Names a route by method, operation id, and rendered path. */
    private static String describe(Route route) {
        return route.method() + " (operation '" + route.operation().operationId() + "') at '" + route.path() + "'";
    }

    /** Renders a template with every variable written as {@code {}}, so equivalent templates match. */
    private static String shape(String template) {
        return VARIABLE.matcher(template).replaceAll("{}");
    }

    /** Returns the position of a lowercase method in a Path Item Object, unknown methods last. */
    static int methodPosition(String key) {
        int position = METHOD_ORDER.indexOf(key);
        return position < 0 ? METHOD_ORDER.size() : position;
    }

    /**
     * One operation with its rendered path.
     *
     * @param path the rendered path key
     * @param shape the rendered path with every variable written as {@code {}}
     * @param method the upper-case HTTP method
     * @param operation the operation
     */
    private record Route(String path, String shape, String method, OperationPublication operation) {

        /** Returns the lowercase method key of the route. */
        String key() {
            return method.toLowerCase(Locale.ROOT);
        }
    }
}
