// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.core.util.AnnotationResolver;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package-private {@link UriBuilder} implementation used by {@link SimpleRuntimeDelegate}.
 *
 * <p>Supports RFC 3986 URI construction with URI template resolution, contextual percent-encoding,
 * path/query/matrix manipulation, and positional or named template substitution. Templates are
 * preserved through encoding operations and resolved at build time.
 *
 * <p>Encoding strategy: each component uses context-specific safe-character sets derived from
 * RFC 3986. Already-encoded {@code %XX} sequences are never double-encoded. URI templates
 * ({@code {name}} or {@code {name : regex}}) are preserved through encoding by temporarily
 * replacing them with marker tokens.
 *
 * @see SimpleRuntimeDelegate
 */
class SimpleUriBuilder extends UriBuilder {

    // --- RFC 3986 safe-character constants ---

    /** pchar without {@code /}: safe in a single path segment. */
    private static final String PCHAR_SAFE = ":@!$&'()*+,;=";

    /** pchar with {@code /}: safe in full path strings (preserves slash separators). */
    private static final String PATH_SAFE = ":@!$&'()*+,;=/";

    /** Safe characters in a query string (key or value when {@code &} and {@code =} are allowed). */
    private static final String QUERY_SAFE = ":@!$&'()*+,;=/?";

    /**
     * Safe characters for individual query parameter names and values.
     * {@code &} and {@code =} are intentionally excluded so they are encoded.
     */
    private static final String QUERY_PARAM_SAFE = ":@!$'()*+,;/?";

    /** Safe characters in a URI fragment. */
    private static final String FRAGMENT_SAFE = ":@!$&'()*+,;=/?";

    /** Safe characters in the userinfo component (excludes {@code @} and {@code /}). */
    private static final String USERINFO_SAFE = ":!$&'()*+,;=";

    /** Safe characters for the host (reg-name) component: sub-delimiters only. */
    private static final String HOST_SAFE = "!$&'()*+,;=";

    // --- Template handling ---

    /** Regex fragment matching content inside template braces, including nested brace groups like {@code {1,3}}. */
    private static final String BRACE_CONTENT = "(?:[^{}]|\\{[^}]*\\})*";

    /** Matches a URI template token, e.g. {@code {name}} or {@code {name : regex}}. */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{" + BRACE_CONTENT + "\\}");

    /** Extracts the variable name from a template token, with optional regex constraint after {@code :}. */
    private static final Pattern TEMPLATE_NAME_PATTERN =
            Pattern.compile("\\{\\s*([^:}]+?)\\s*(?::" + BRACE_CONTENT + ")?\\}");

    /** Scheme validation: must start with a letter followed by letters, digits, {@code +}, {@code -}, or {@code .}. */
    private static final Pattern SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+\\-.]*");

    // --- Builder state ---

    private String scheme;
    private String ssp; // scheme-specific-part for opaque URIs only
    private String userInfo;
    private String host;
    private int port = -1;
    private String path;
    private final List<QueryEntry> queryParams = new ArrayList<>();
    private String fragment;

    // --- Internal record ---

    /** A single query parameter name-value pair. A {@code null} value means a name-only parameter. */
    private record QueryEntry(String name, String value) {}

    // --- clone ---

    /**
     * Creates a deep copy of this builder. The cloned builder has the same state but is
     * completely independent — mutations to either do not affect the other.
     *
     * @return a new {@code SimpleUriBuilder} with identical state
     */
    @Override
    public UriBuilder clone() {
        SimpleUriBuilder copy = new SimpleUriBuilder();
        copy.scheme = this.scheme;
        copy.ssp = this.ssp;
        copy.userInfo = this.userInfo;
        copy.host = this.host;
        copy.port = this.port;
        copy.path = this.path;
        copy.queryParams.addAll(this.queryParams);
        copy.fragment = this.fragment;
        return copy;
    }

    /** Clears hierarchical URI fields (userInfo, host, port, path, query) when switching to an opaque URI. */
    private void clearHierarchicalState() {
        this.userInfo = null;
        this.host = null;
        this.port = -1;
        this.path = null;
        this.queryParams.clear();
    }

    // --- URI initializers ---

    /**
     * Copies non-null components from the supplied URI into this builder, replacing any existing
     * values for those components.
     *
     * @param uri the URI to copy components from; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code uri} is {@code null}
     */
    @Override
    public UriBuilder uri(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        if (uri.getScheme() != null) {
            this.scheme = uri.getScheme();
        }
        if (uri.isOpaque()) {
            this.ssp = uri.getRawSchemeSpecificPart();
            clearHierarchicalState();
            if (uri.getRawFragment() != null) {
                this.fragment = uri.getRawFragment();
            }
            return this;
        }
        this.ssp = null;
        if (uri.getRawAuthority() != null) {
            if (uri.getRawUserInfo() != null) {
                this.userInfo = uri.getRawUserInfo();
            }
            if (uri.getHost() != null) {
                this.host = uri.getHost();
            }
            if (uri.getPort() >= 0) {
                this.port = uri.getPort();
            }
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            this.path = uri.getRawPath();
        }
        if (uri.getRawQuery() != null) {
            parseQueryInto(uri.getRawQuery(), this.queryParams, true);
        }
        if (uri.getRawFragment() != null) {
            this.fragment = uri.getRawFragment();
        }
        return this;
    }

    /**
     * Parses the {@code uriTemplate} string and copies the parsed components into this builder,
     * replacing any existing values. URI template tokens ({@code {name}}) are preserved.
     *
     * @param uriTemplate the URI template string; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code uriTemplate} is {@code null} or cannot be parsed
     */
    @Override
    public UriBuilder uri(String uriTemplate) {
        if (uriTemplate == null) {
            throw new IllegalArgumentException("uriTemplate must not be null");
        }
        // Replace template tokens with safe placeholders before parsing
        String mp = safeMarkerPrefix(uriTemplate);
        Matcher m = TEMPLATE_PATTERN.matcher(uriTemplate);
        List<String> templates = new ArrayList<>();
        String sanitized = m.replaceAll(mr -> {
            templates.add(mr.group());
            return mp + (templates.size() - 1) + "x";
        });
        try {
            URI parsed = new URI(sanitized);
            // Restore templates in each component before storing
            String rawScheme = parsed.getScheme();
            String rawUserInfo = parsed.getRawUserInfo();
            String rawHost = parsed.getHost();
            int rawPort = parsed.getPort();
            String rawPath = parsed.getRawPath();
            String rawQuery = parsed.getRawQuery();
            String rawFragment = parsed.getRawFragment();

            if (rawScheme != null) {
                this.scheme = restoreTemplates(rawScheme, templates, mp);
            }
            if (parsed.isOpaque()) {
                this.ssp = restoreTemplates(parsed.getRawSchemeSpecificPart(), templates, mp);
                clearHierarchicalState();
                if (rawFragment != null) {
                    this.fragment = restoreTemplates(rawFragment, templates, mp);
                }
                return this;
            }
            this.ssp = null;
            if (rawHost != null) {
                // Standard case: Java parsed the host successfully
                if (rawUserInfo != null) {
                    this.userInfo = restoreTemplates(rawUserInfo, templates, mp);
                }
                this.host = restoreTemplates(rawHost, templates, mp);
                if (rawPort >= 0) {
                    this.port = rawPort;
                }
            } else if (parsed.getRawAuthority() != null) {
                // Fallback: host contains template placeholders that Java rejected
                parseAuthority(parsed.getRawAuthority(), templates, mp);
            }
            if (rawPath != null && !rawPath.isEmpty()) {
                this.path = restoreTemplates(rawPath, templates, mp);
            }
            if (rawQuery != null) {
                parseQueryInto(restoreTemplates(rawQuery, templates, mp), this.queryParams, true);
            }
            if (rawFragment != null) {
                this.fragment = restoreTemplates(rawFragment, templates, mp);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI template: " + uriTemplate, e);
        }
        return this;
    }

    // --- Component setters ---

    /**
     * Sets the URI scheme.
     *
     * @param scheme the URI scheme, or {@code null} to clear; must match {@code [a-zA-Z][a-zA-Z0-9+\-.]*} if non-null
     * @return this builder
     * @throws IllegalArgumentException if {@code scheme} is syntactically invalid
     */
    @Override
    public UriBuilder scheme(String scheme) {
        if (scheme != null
                && !SCHEME_PATTERN.matcher(scheme).matches()
                && !TEMPLATE_PATTERN.matcher(scheme).find()) {
            throw new IllegalArgumentException("Invalid URI scheme: " + scheme);
        }
        this.scheme = scheme;
        return this;
    }

    /**
     * Sets the URI scheme-specific-part, overwriting any existing authority, path, and query.
     *
     * @param ssp the URI scheme-specific-part; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code ssp} is {@code null} or cannot be parsed
     */
    @Override
    public UriBuilder schemeSpecificPart(String ssp) {
        if (ssp == null) {
            throw new IllegalArgumentException("ssp must not be null");
        }
        this.ssp = ssp;
        // Also parse to fill authority/path/query fields
        try {
            URI parsed = new URI(scheme != null ? scheme : "x", ssp, null);
            if (parsed.getRawUserInfo() != null) {
                this.userInfo = parsed.getRawUserInfo();
            }
            if (parsed.getHost() != null) {
                this.host = parsed.getHost();
            }
            this.port = parsed.getPort();
            if (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()) {
                this.path = parsed.getRawPath();
            }
            queryParams.clear();
            if (parsed.getRawQuery() != null) {
                parseQueryInto(parsed.getRawQuery(), this.queryParams, false);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot parse SSP: " + ssp, e);
        }
        return this;
    }

    /**
     * Sets the URI user-info component.
     *
     * @param ui the user-info string, or {@code null} to clear
     * @return this builder
     */
    @Override
    public UriBuilder userInfo(String ui) {
        this.userInfo = ui;
        return this;
    }

    /**
     * Sets the URI host.
     *
     * @param host the host name or IP address, or {@code null} to clear
     * @return this builder
     * @throws IllegalArgumentException if {@code host} is syntactically invalid
     */
    @Override
    public UriBuilder host(String host) {
        this.host = host;
        return this;
    }

    /**
     * Sets the URI port.
     *
     * @param port the port number; {@code -1} clears an explicit port
     * @return this builder
     * @throws IllegalArgumentException if {@code port} is less than {@code -1}
     */
    @Override
    public UriBuilder port(int port) {
        if (port < -1) {
            throw new IllegalArgumentException("port must be >= -1, got: " + port);
        }
        this.port = port;
        return this;
    }

    /**
     * Sets the URI fragment.
     *
     * @param fragment the fragment string, or {@code null} to remove any existing fragment
     * @return this builder
     */
    @Override
    public UriBuilder fragment(String fragment) {
        this.fragment = fragment;
        return this;
    }

    // --- Path methods ---

    /**
     * Replaces the entire path with the supplied value, discarding any existing path and matrix parameters.
     *
     * @param path the new path, or {@code null} to clear the path component
     * @return this builder
     */
    @Override
    public UriBuilder replacePath(String path) {
        this.path = path;
        return this;
    }

    /**
     * Appends the given path to the existing path, inserting a {@code /} separator if necessary.
     * Existing {@code /} characters within the path are preserved.
     *
     * @param path the path segment(s) to append; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code path} is {@code null}
     */
    @Override
    public UriBuilder path(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (this.path == null || this.path.isEmpty()) {
            this.path = path;
        } else if (this.path.endsWith("/") && path.startsWith("/")) {
            // Avoid double slash
            this.path = this.path + path.substring(1);
        } else if (this.path.endsWith("/") || path.startsWith("/")) {
            this.path = this.path + path;
        } else {
            this.path = this.path + "/" + path;
        }
        return this;
    }

    /**
     * Appends the path value from the effective {@link Path} annotation on the given resource class.
     *
     * <p>Uses {@link AnnotationResolver#resolveClassAnnotations(Class)} to resolve {@code @Path}
     * from the class itself, its superclass chain, and all transitively implemented interfaces,
     * so that interface-declared {@code @Path} values are honoured for concrete implementations
     * that carry no direct annotation.
     *
     * @param resource the JAX-RS resource class; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code resource} is {@code null} or no effective
     *         {@code @Path} is found in its class hierarchy
     */
    @SuppressWarnings("rawtypes")
    @Override
    public UriBuilder path(Class resource) {
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        List<java.lang.annotation.Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(resource);
        Path annotation = findPathAnnotation(classAnnotations);
        if (annotation == null) {
            throw new IllegalArgumentException("Class is not annotated with @Path: " + resource.getName());
        }
        return path(annotation.value());
    }

    /**
     * Appends the path value from the effective {@link Path} annotation on the named method of
     * the given class. The method must exist and exactly one variant must carry an effective
     * {@code @Path} (direct or interface-declared).
     *
     * <p>Uses {@link AnnotationResolver#resolveMethodAnnotations(Method)} for each candidate so
     * that interface-declared {@code @Path} values are resolved.
     *
     * @param resource the class containing the method; must not be {@code null}
     * @param method   the method name; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code resource} or {@code method} is {@code null}, or
     *         if zero or more than one method with that name carries an effective {@code @Path}
     */
    @SuppressWarnings("rawtypes")
    @Override
    public UriBuilder path(Class resource, String method) {
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        List<Method> annotated = Arrays.stream(resource.getDeclaredMethods())
                .filter(m -> m.getName().equals(method)
                        && findPathAnnotation(AnnotationResolver.resolveMethodAnnotations(m)) != null)
                .toList();
        if (annotated.isEmpty()) {
            throw new IllegalArgumentException(
                    "No method named '" + method + "' annotated with @Path found in " + resource.getName());
        }
        if (annotated.size() > 1) {
            throw new IllegalArgumentException(
                    "More than one method named '" + method + "' annotated with @Path found in " + resource.getName());
        }
        Path pathAnn = findPathAnnotation(AnnotationResolver.resolveMethodAnnotations(annotated.get(0)));
        return path(pathAnn.value());
    }

    /**
     * Appends the path value from the effective {@link Path} annotation on the given method.
     *
     * <p>Uses {@link AnnotationResolver#resolveMethodAnnotations(Method)} so that
     * interface-declared {@code @Path} values are honoured for concrete implementations that
     * carry no direct annotation.
     *
     * @param method the resource method; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code method} is {@code null} or no effective
     *         {@code @Path} is found in its method hierarchy
     */
    @Override
    public UriBuilder path(Method method) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        Path annotation = findPathAnnotation(AnnotationResolver.resolveMethodAnnotations(method));
        if (annotation == null) {
            throw new IllegalArgumentException("Method is not annotated with @Path: " + method);
        }
        return path(annotation.value());
    }

    /**
     * Finds the first {@link Path} annotation in a pre-resolved annotation list.
     *
     * @param annotations the merged annotation list
     * @return the first {@link Path} annotation, or {@code null} if none present
     */
    private static Path findPathAnnotation(List<java.lang.annotation.Annotation> annotations) {
        for (java.lang.annotation.Annotation ann : annotations) {
            if (ann instanceof Path p) {
                return p;
            }
        }
        return null;
    }

    /**
     * Appends each segment as a distinct path segment. Literal {@code /} characters within a segment
     * value are encoded as {@code %2F} so they are treated as part of the segment, not as separators.
     *
     * @param segments the segment values to append; must not be {@code null}, and no element may be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code segments} or any element is {@code null}
     */
    @Override
    public UriBuilder segment(String... segments) {
        if (segments == null) {
            throw new IllegalArgumentException("segments must not be null");
        }
        for (String seg : segments) {
            if (seg == null) {
                throw new IllegalArgumentException("segment element must not be null");
            }
            // Encode literal slashes so each arg is a single segment
            path(seg.replace("/", "%2F"));
        }
        return this;
    }

    // --- Matrix parameter methods ---

    /**
     * Replaces all matrix parameters on the last path segment with the given matrix string.
     * Passing {@code null} removes all matrix parameters from the last segment.
     *
     * @param matrix the matrix parameter string (without leading {@code ;}), or {@code null} to clear
     * @return this builder
     */
    @Override
    public UriBuilder replaceMatrix(String matrix) {
        if (this.path == null) {
            if (matrix != null && !matrix.isEmpty()) {
                this.path = ";" + matrix;
            }
            return this;
        }
        int lastSlash = this.path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? this.path.substring(lastSlash + 1) : this.path;
        String prefix = lastSlash >= 0 ? this.path.substring(0, lastSlash + 1) : "";

        // Strip existing matrix params from last segment
        int semicolon = lastSegment.indexOf(';');
        String segBase = semicolon >= 0 ? lastSegment.substring(0, semicolon) : lastSegment;

        if (matrix == null || matrix.isEmpty()) {
            this.path = prefix + segBase;
        } else {
            this.path = prefix + segBase + ";" + matrix;
        }
        return this;
    }

    /**
     * Appends matrix parameters to the last path segment. Each value is appended as
     * {@code ;name=value}.
     *
     * @param name the matrix parameter name; must not be {@code null}
     * @param values the values to append; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code name} or {@code values} is {@code null}
     */
    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        if (name == null) {
            throw new IllegalArgumentException("matrix parameter name must not be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("matrix parameter values must not be null");
        }
        if (this.path == null) {
            this.path = "";
        }
        for (Object v : values) {
            this.path = this.path + ";" + name + "=" + v;
        }
        return this;
    }

    /**
     * Replaces all matrix parameters with the given name on the last path segment.
     * Passing {@code null} or an empty array removes all occurrences of the parameter.
     *
     * @param name the matrix parameter name; must not be {@code null}
     * @param values the replacement values, or {@code null}/empty to remove
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is {@code null}
     */
    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        if (name == null) {
            throw new IllegalArgumentException("matrix parameter name must not be null");
        }
        if (this.path == null) {
            if (values != null && values.length > 0) {
                this.path = "";
                for (Object v : values) {
                    this.path = this.path + ";" + name + "=" + v;
                }
            }
            return this;
        }
        int lastSlash = this.path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? this.path.substring(lastSlash + 1) : this.path;
        String prefix = lastSlash >= 0 ? this.path.substring(0, lastSlash + 1) : "";

        // Rebuild last segment, filtering out all ;name=... occurrences
        int firstSemi = lastSegment.indexOf(';');
        String segBase = firstSemi >= 0 ? lastSegment.substring(0, firstSemi) : lastSegment;
        List<String> kept = new ArrayList<>();
        if (firstSemi >= 0) {
            String matrixStr = lastSegment.substring(firstSemi + 1);
            for (String part : matrixStr.split(";")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String pName = eq >= 0 ? part.substring(0, eq) : part;
                if (!pName.equals(name)) {
                    kept.add(";" + part);
                }
            }
        }
        // Append new values
        List<String> newParts = new ArrayList<>(kept);
        if (values != null) {
            for (Object v : values) {
                newParts.add(";" + name + "=" + v);
            }
        }
        this.path = prefix + segBase + String.join("", newParts);
        return this;
    }

    // --- Query parameter methods ---

    /**
     * Replaces all query parameters with those parsed from the given query string.
     * Passing {@code null} clears all query parameters.
     *
     * @param query the raw query string (without leading {@code ?}), or {@code null} to clear
     * @return this builder
     */
    @Override
    public UriBuilder replaceQuery(String query) {
        queryParams.clear();
        if (query != null && !query.isEmpty()) {
            parseQueryInto(query, queryParams, false);
        }
        return this;
    }

    /**
     * Appends query parameters with the given name. One entry is added per value.
     *
     * @param name the query parameter name; must not be {@code null}
     * @param values the query parameter values; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code name} or {@code values} is {@code null}
     */
    @Override
    public UriBuilder queryParam(String name, Object... values) {
        if (name == null) {
            throw new IllegalArgumentException("query parameter name must not be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("query parameter values must not be null");
        }
        for (Object v : values) {
            queryParams.add(new QueryEntry(name, v != null ? v.toString() : null));
        }
        return this;
    }

    /**
     * Replaces all query parameters with the given name. Passing {@code null} or an empty array removes
     * all occurrences.
     *
     * @param name the query parameter name; must not be {@code null}
     * @param values the replacement values, or {@code null}/empty to remove
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is {@code null}
     */
    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        if (name == null) {
            throw new IllegalArgumentException("query parameter name must not be null");
        }
        queryParams.removeIf(e -> e.name().equals(name));
        if (values != null) {
            for (Object v : values) {
                queryParams.add(new QueryEntry(name, v != null ? v.toString() : null));
            }
        }
        return this;
    }

    // --- Template resolution ---

    /**
     * Resolves the named URI template by substituting the given value, encoding the value
     * appropriately for each URI component context (default: encode {@code /} in path templates).
     *
     * @param name the template name; must not be {@code null}
     * @param value the substitution value; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code name} or {@code value} is {@code null}
     */
    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        return resolveTemplate(name, value, true);
    }

    /**
     * Resolves the named URI template by substituting the given value, encoding the value
     * for each URI component context.
     *
     * @param name the template name; must not be {@code null}
     * @param value the substitution value; must not be {@code null}
     * @param encodeSlashInPath if {@code true}, {@code /} in the value is percent-encoded when substituted
     *        into a path component; otherwise {@code /} is preserved
     * @return this builder
     * @throws IllegalArgumentException if {@code name} or {@code value} is {@code null}
     */
    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        if (name == null) {
            throw new IllegalArgumentException("template name must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("template value must not be null");
        }
        String strVal = value.toString();
        Pattern p = templatePattern(name);
        String pathSafe = encodeSlashInPath ? PCHAR_SAFE : PATH_SAFE;
        scheme = replaceTemplate(scheme, p, encode(strVal, ""));
        ssp = replaceTemplate(ssp, p, encode(strVal, QUERY_SAFE));
        userInfo = replaceTemplate(userInfo, p, encode(strVal, USERINFO_SAFE));
        host = replaceTemplate(host, p, encode(strVal, HOST_SAFE));
        path = replaceTemplate(path, p, encode(strVal, pathSafe));
        for (int i = 0; i < queryParams.size(); i++) {
            QueryEntry e = queryParams.get(i);
            String newName = replaceTemplate(e.name(), p, encode(strVal, QUERY_PARAM_SAFE));
            String newValue = replaceTemplate(e.value(), p, encode(strVal, QUERY_PARAM_SAFE));
            if (!java.util.Objects.equals(newName, e.name()) || !java.util.Objects.equals(newValue, e.value())) {
                queryParams.set(i, new QueryEntry(newName, newValue));
            }
        }
        fragment = replaceTemplate(fragment, p, encode(strVal, FRAGMENT_SAFE));
        return this;
    }

    /**
     * Resolves the named URI template using a pre-encoded value. The value is used as-is without
     * additional encoding.
     *
     * @param name the template name; must not be {@code null}
     * @param value the already-encoded substitution value; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code name} or {@code value} is {@code null}
     */
    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        if (name == null) {
            throw new IllegalArgumentException("template name must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("template value must not be null");
        }
        String strVal = value.toString();
        Pattern p = templatePattern(name);
        scheme = replaceTemplate(scheme, p, strVal);
        ssp = replaceTemplate(ssp, p, strVal);
        userInfo = replaceTemplate(userInfo, p, strVal);
        host = replaceTemplate(host, p, strVal);
        path = replaceTemplate(path, p, strVal);
        for (int i = 0; i < queryParams.size(); i++) {
            QueryEntry e = queryParams.get(i);
            String newName = replaceTemplate(e.name(), p, strVal);
            String newValue = replaceTemplate(e.value(), p, strVal);
            if (!java.util.Objects.equals(newName, e.name()) || !java.util.Objects.equals(newValue, e.value())) {
                queryParams.set(i, new QueryEntry(newName, newValue));
            }
        }
        fragment = replaceTemplate(fragment, p, strVal);
        return this;
    }

    /**
     * Resolves all named URI templates using entries from the given map (encoding each value).
     *
     * @param templateValues the map of template names to values; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if the map or any key/value is {@code null}
     */
    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        return resolveTemplates(templateValues, true);
    }

    /**
     * Resolves all named URI templates using entries from the given map.
     *
     * @param templateValues the map of template names to values; must not be {@code null}
     * @param encodeSlashInPath if {@code true}, {@code /} in values is encoded in path templates
     * @return this builder
     * @throws IllegalArgumentException if the map or any key/value is {@code null}
     */
    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) {
        if (templateValues == null) {
            throw new IllegalArgumentException("templateValues must not be null");
        }
        for (Map.Entry<String, Object> entry : templateValues.entrySet()) {
            resolveTemplate(entry.getKey(), entry.getValue(), encodeSlashInPath);
        }
        return this;
    }

    /**
     * Resolves all named URI templates using entries from the given map, treating each value as
     * already percent-encoded.
     *
     * @param templateValues the map of template names to pre-encoded values; must not be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if the map or any key/value is {@code null}
     */
    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        if (templateValues == null) {
            throw new IllegalArgumentException("templateValues must not be null");
        }
        for (Map.Entry<String, Object> entry : templateValues.entrySet()) {
            resolveTemplateFromEncoded(entry.getKey(), entry.getValue());
        }
        return this;
    }

    // --- Build methods ---

    /**
     * Builds a URI from the current state, replacing any remaining template parameters with
     * the supplied values in order of first appearance. Slashes in path template values are encoded.
     *
     * @param values the positional template values; must not be {@code null} or contain {@code null} elements
     * @return the built URI
     * @throws IllegalArgumentException if a value is {@code null} or templates remain unresolved
     * @throws UriBuilderException if the resulting string is not a valid URI
     */
    @Override
    public URI build(Object... values) {
        return build(values, true);
    }

    /**
     * Builds a URI from the current state, replacing any remaining template parameters with
     * the supplied values in order of first appearance.
     *
     * @param values the positional template values; must not be {@code null} or contain {@code null} elements
     * @param encodeSlashInPath if {@code true}, {@code /} in path template values is percent-encoded
     * @return the built URI
     * @throws IllegalArgumentException if a value is {@code null} or templates remain unresolved
     * @throws UriBuilderException if the resulting string is not a valid URI
     */
    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        Map<String, Object> valueMap = positionalValuesToMap(values);
        return buildFromMap(valueMap, encodeSlashInPath);
    }

    /**
     * Builds a URI from the current state, replacing template parameters with values from the map.
     * Slashes in path template values are encoded.
     *
     * @param values the map of template names to values; must not be {@code null}
     * @return the built URI
     * @throws IllegalArgumentException if any template remains unresolved or a value is {@code null}
     * @throws UriBuilderException if the resulting string is not a valid URI
     */
    @Override
    public URI buildFromMap(Map<String, ?> values) {
        return buildFromMap(values, true);
    }

    /**
     * Builds a URI from the current state, replacing template parameters with values from the map.
     *
     * @param values the map of template names to values; must not be {@code null}
     * @param encodeSlashInPath if {@code true}, {@code /} in path template values is percent-encoded
     * @return the built URI
     * @throws IllegalArgumentException if any template remains unresolved or a value is {@code null}
     * @throws UriBuilderException if the resulting string is not a valid URI
     */
    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        SimpleUriBuilder b = (SimpleUriBuilder) clone();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("template name must not be null");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("template value must not be null for key: " + entry.getKey());
            }
            b.resolveTemplate(entry.getKey(), entry.getValue(), encodeSlashInPath);
        }
        return b.buildUri();
    }

    /**
     * Builds a URI from the current state, replacing template parameters with values from the map.
     * Values are treated as already percent-encoded; any {@code %} not followed by two hex digits
     * will be encoded.
     *
     * @param values the map of template names to pre-encoded values; must not be {@code null}
     * @return the built URI
     * @throws IllegalArgumentException if any template remains unresolved or a value is {@code null}
     * @throws UriBuilderException if the resulting string is not a valid URI
     */
    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        SimpleUriBuilder b = (SimpleUriBuilder) clone();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("template name must not be null");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("template value must not be null for key: " + entry.getKey());
            }
            b.resolveTemplateFromEncoded(entry.getKey(), entry.getValue());
        }
        return b.buildUri();
    }

    /**
     * Builds a URI from the current state, replacing template parameters with the supplied values
     * in order of first appearance. Values are treated as already encoded; bare {@code %} characters
     * not forming valid escape sequences will be encoded.
     *
     * @param values the positional encoded values
     * @return the built URI
     * @throws IllegalArgumentException if templates remain unresolved
     * @throws UriBuilderException if the resulting string is not a valid URI
     */
    @Override
    public URI buildFromEncoded(Object... values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        Map<String, Object> valueMap = positionalValuesToMap(values);
        SimpleUriBuilder b = (SimpleUriBuilder) clone();
        for (Map.Entry<String, Object> entry : valueMap.entrySet()) {
            b.resolveTemplateFromEncoded(entry.getKey(), entry.getValue());
        }
        return b.buildUri();
    }

    /**
     * Returns the URI template string represented by the current builder state. Components are
     * assembled without encoding; template tokens are preserved as-is.
     *
     * @return the URI template string
     */
    @Override
    public String toTemplate() {
        return assembleUri(false);
    }

    // --- Private helpers ---

    /**
     * Assembles the URI string from the current builder state.
     *
     * @param encode if {@code true}, encode each component; if {@code false}, return the raw template string
     * @return the assembled URI string
     */
    private String assembleUri(boolean encode) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append(':');
        }
        if (ssp != null) {
            sb.append(encode ? encodePreservingTemplates(ssp, QUERY_SAFE) : ssp);
        } else {
            if (host != null) {
                sb.append("//");
                if (userInfo != null) {
                    sb.append(encode ? encodePreservingTemplates(userInfo, USERINFO_SAFE) : userInfo)
                            .append('@');
                }
                sb.append(host);
                if (port >= 0) {
                    sb.append(':').append(port);
                }
            }
            if (path != null) {
                String p = encode ? encodePreservingTemplates(path, PATH_SAFE) : path;
                if (host != null && !p.isEmpty() && !p.startsWith("/")) {
                    sb.append('/');
                }
                sb.append(p);
            }
        }
        if (!queryParams.isEmpty()) {
            sb.append('?');
            StringJoiner sj = new StringJoiner("&");
            for (QueryEntry qp : queryParams) {
                String eName = encode ? encodePreservingTemplates(qp.name(), QUERY_PARAM_SAFE) : qp.name();
                if (qp.value() != null) {
                    String eValue = encode ? encodePreservingTemplates(qp.value(), QUERY_PARAM_SAFE) : qp.value();
                    sj.add(eName + "=" + eValue);
                } else {
                    sj.add(eName);
                }
            }
            sb.append(sj);
        }
        if (fragment != null) {
            sb.append('#').append(encode ? encodePreservingTemplates(fragment, FRAGMENT_SAFE) : fragment);
        }
        return sb.toString();
    }

    /**
     * Builds and returns the final URI after all templates have been resolved. Encodes each component
     * and validates that no unresolved templates remain.
     *
     * @return the final URI
     * @throws IllegalArgumentException if any URI template remains unresolved
     * @throws UriBuilderException if the assembled string is not a valid URI
     */
    private URI buildUri() {
        String uriString = assembleUri(true);
        Matcher m = TEMPLATE_PATTERN.matcher(uriString);
        if (m.find()) {
            throw new IllegalArgumentException("Unresolved URI template: " + m.group());
        }
        try {
            return new URI(uriString);
        } catch (URISyntaxException e) {
            throw new UriBuilderException("Failed to build URI: " + uriString, e);
        }
    }

    /**
     * Collects unique template names from all components in first-appearance order, then maps each
     * positional value to its corresponding name.
     *
     * @param values the positional values to substitute
     * @return an ordered map of template name to value
     * @throws IllegalArgumentException if more values are provided than there are unique template names,
     *         or if any value is {@code null}
     */
    private Map<String, Object> positionalValuesToMap(Object[] values) {
        List<String> names = collectTemplateNames();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length && i < names.size(); i++) {
            if (values[i] == null) {
                throw new IllegalArgumentException("Template value at index " + i + " must not be null");
            }
            map.put(names.get(i), values[i]);
        }
        return map;
    }

    /**
     * Collects all unique template variable names from all URI components in first-appearance order.
     *
     * @return list of unique template names in order of first appearance
     */
    private List<String> collectTemplateNames() {
        String template = toTemplate();
        Matcher m = TEMPLATE_NAME_PATTERN.matcher(template);
        List<String> names = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1).trim();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Parses a raw query string into name-value pairs and adds them to the given list.
     *
     * @param query the raw query string (without leading {@code ?})
     * @param target the list to append parsed entries into
     * @param replace if {@code true}, clears {@code target} before adding
     */
    private static void parseQueryInto(String query, List<QueryEntry> target, boolean replace) {
        if (replace) {
            target.clear();
        }
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                target.add(new QueryEntry(pair.substring(0, eq), pair.substring(eq + 1)));
            } else {
                target.add(new QueryEntry(pair, null));
            }
        }
    }

    /**
     * Builds a pattern that matches a URI template token for the given variable name,
     * e.g. {@code {name}} or {@code {name : regex}}.
     *
     * @param name the template variable name
     * @return the compiled pattern
     */
    private static Pattern templatePattern(String name) {
        return Pattern.compile("\\{\\s*" + Pattern.quote(name) + "(\\s*:" + BRACE_CONTENT + ")?\\s*\\}");
    }

    /**
     * Replaces all occurrences of the template pattern in {@code input} with {@code replacement}.
     * Returns {@code null} if {@code input} is {@code null}.
     *
     * @param input the string to scan, or {@code null}
     * @param pattern the compiled template pattern
     * @param replacement the literal replacement string
     * @return the modified string, or {@code null} if input was {@code null}
     */
    private static String replaceTemplate(String input, Pattern pattern, String replacement) {
        if (input == null) {
            return null;
        }
        return pattern.matcher(input).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * Finds a marker prefix that does not collide with any text in the input, including
     * inside template bodies. Starts with {@code "tpl"} and appends {@code "z"} until safe.
     */
    private static String safeMarkerPrefix(String input) {
        String prefix = "tpl";
        while (input.contains(prefix)) {
            prefix += "z";
        }
        return prefix;
    }

    /**
     * Restores previously extracted template tokens back into a string by replacing the
     * marker tokens with their original values.
     *
     * @param input the string containing marker tokens
     * @param templates the original template strings indexed by their marker position
     * @param markerPrefix the prefix used when the markers were created
     * @return the string with template tokens restored
     */
    private static String restoreTemplates(String input, List<String> templates, String markerPrefix) {
        for (int i = 0; i < templates.size(); i++) {
            input = input.replace(markerPrefix + i + "x", templates.get(i));
        }
        return input;
    }

    /**
     * Manually parses a raw authority string into userInfo, host, and port components.
     * Used as a fallback when {@code java.net.URI} fails to parse the host because template
     * placeholders are not valid DNS labels.
     *
     * @param rawAuthority the raw authority string (e.g. {@code "user:pass@tpl0x.example.com:8080"})
     * @param templates the template token list for restoration
     * @param markerPrefix the marker prefix used during sanitization
     */
    private void parseAuthority(String rawAuthority, List<String> templates, String markerPrefix) {
        String remaining = rawAuthority;

        // Extract userInfo (everything before the first '@')
        int atIdx = remaining.indexOf('@');
        if (atIdx >= 0) {
            this.userInfo = restoreTemplates(remaining.substring(0, atIdx), templates, markerPrefix);
            remaining = remaining.substring(atIdx + 1);
        }

        // Handle IPv6 brackets: [::1] or [::1]:8080
        if (remaining.startsWith("[")) {
            int closeBracket = remaining.indexOf(']');
            if (closeBracket >= 0) {
                this.host = restoreTemplates(remaining.substring(0, closeBracket + 1), templates, markerPrefix);
                remaining = remaining.substring(closeBracket + 1);
                if (remaining.startsWith(":")) {
                    String portStr = remaining.substring(1);
                    if (!portStr.isEmpty() && portStr.chars().allMatch(Character::isDigit)) {
                        this.port = Integer.parseInt(portStr);
                    }
                }
                return;
            }
        }

        // Extract port from last ':digits'
        int lastColon = remaining.lastIndexOf(':');
        if (lastColon >= 0) {
            String portStr = remaining.substring(lastColon + 1);
            if (!portStr.isEmpty() && portStr.chars().allMatch(Character::isDigit)) {
                this.port = Integer.parseInt(portStr);
                remaining = remaining.substring(0, lastColon);
            }
        }

        this.host = restoreTemplates(remaining, templates, markerPrefix);
    }

    /**
     * Percent-encodes characters in {@code value} that are not RFC 3986 unreserved characters and
     * not in {@code safeChars}. Already-encoded {@code %XX} sequences are passed through unchanged.
     * Encoding uses UTF-8 byte representation.
     *
     * @param value the string to encode
     * @param safeChars additional characters that must not be encoded
     * @return the percent-encoded string
     */
    private static String encode(String value, String safeChars) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            int charCount = Character.charCount(cp);
            // Pass through already-encoded %XX sequences
            if (cp == '%'
                    && i + 2 < value.length()
                    && isHexDigit(value.charAt(i + 1))
                    && isHexDigit(value.charAt(i + 2))) {
                sb.append('%').append(value.charAt(i + 1)).append(value.charAt(i + 2));
                i += 3;
                continue;
            }
            // Pass through unreserved characters and safe chars (ASCII only per RFC 3986)
            if (cp < 128 && (isUnreserved((char) cp) || safeChars.indexOf(cp) >= 0)) {
                sb.append((char) cp);
                i += charCount;
                continue;
            }
            // Percent-encode all other characters using UTF-8 bytes
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
            i += charCount;
        }
        return sb.toString();
    }

    /**
     * Encodes a string while preserving any URI template tokens ({@code {name}}) unchanged.
     * Templates are temporarily replaced with marker tokens before encoding and restored after.
     *
     * @param value the string to encode, possibly containing template tokens; may be {@code null}
     * @param safeChars additional characters that must not be encoded
     * @return the encoded string with templates preserved, or {@code null} if {@code value} is {@code null}
     */
    private static String encodePreservingTemplates(String value, String safeChars) {
        if (value == null) {
            return null;
        }
        String mp = safeMarkerPrefix(value);
        Matcher m = TEMPLATE_PATTERN.matcher(value);
        List<String> templates = new ArrayList<>();
        String sanitized = m.replaceAll(mr -> {
            templates.add(mr.group());
            return mp + (templates.size() - 1) + "x";
        });
        String encoded = encode(sanitized, safeChars);
        return restoreTemplates(encoded, templates, mp);
    }

    /**
     * Returns {@code true} if {@code c} is an RFC 3986 unreserved character
     * ({@code A-Z}, {@code a-z}, {@code 0-9}, {@code -}, {@code .}, {@code _}, {@code ~}).
     *
     * @param c the character to test
     * @return {@code true} if unreserved
     */
    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '.'
                || c == '_'
                || c == '~';
    }

    /**
     * Returns {@code true} if {@code c} is an ASCII hexadecimal digit ({@code 0-9}, {@code A-F}, {@code a-f}).
     *
     * @param c the character to test
     * @return {@code true} if a hex digit
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
