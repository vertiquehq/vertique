// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a path template with {@code {param}} placeholders into a regex pattern and
 * extracts path parameter values from concrete URIs.
 *
 * <p>Path templates follow the same syntax as JAX-RS {@code @Path}: segments enclosed in
 * curly braces are treated as named parameters (e.g. {@code /ws/chat/{roomId}}). Each
 * placeholder matches exactly one URI path segment (no slashes).
 */
class WebSocketPathMatcher {

    private final Pattern pattern;
    private final List<String> paramNames;

    /**
     * Compiles the given path template into a match pattern.
     *
     * @param pathTemplate the path template with optional {@code {param}} placeholders; must not be {@code null}
     */
    WebSocketPathMatcher(String pathTemplate) {
        List<String> names = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\{([^/}]+)}").matcher(pathTemplate);
        int last = 0;
        while (m.find()) {
            sb.append(Pattern.quote(pathTemplate.substring(last, m.start())));
            names.add(m.group(1));
            sb.append("([^/]+)");
            last = m.end();
        }
        sb.append(Pattern.quote(pathTemplate.substring(last)));
        this.pattern = Pattern.compile("^" + sb + "$");
        this.paramNames = List.copyOf(names);
    }

    /**
     * Extracts path parameter values from the given URI path.
     *
     * @param path the concrete request path to match against the template
     * @return an immutable parameter name-value map, or an empty map if the path does not match
     */
    Map<String, String> extractParams(String path) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), m.group(i + 1));
        }
        return Collections.unmodifiableMap(params);
    }

    /**
     * Returns the list of parameter names in declaration order.
     *
     * @return an immutable list of parameter names; never {@code null}
     */
    List<String> paramNames() {
        return paramNames;
    }
}
