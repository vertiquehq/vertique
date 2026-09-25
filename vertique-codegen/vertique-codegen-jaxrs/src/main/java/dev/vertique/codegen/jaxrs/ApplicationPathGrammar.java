// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import javax.lang.model.element.TypeElement;

/**
 * The single compile-time authority for the application path grammar: the
 * {@code @ApplicationPath} normalization steps and the rule that rejects a malformed result.
 *
 * <p>This class implements all four compile-time steps:
 *
 * <ol>
 *   <li><strong>Step 0 — the annotation is required.</strong> {@link #rawValue} reads
 *       {@code @ApplicationPath} from the nearest class in the application's superclass chain,
 *       starting with the application itself. An interface is never read. Returns {@code null}
 *       when no class in the chain carries the annotation; the caller
 *       ({@link JaxRsApplicationScanner}) is responsible for reporting the compile error.</li>
 *   <li><strong>Step 1 — normalize the start.</strong> An empty value becomes {@code "/"}; a
 *       value without a leading {@code /} gets one.</li>
 *   <li><strong>Step 2 — normalize the end.</strong> One terminal {@code /*} is removed, then
 *       every trailing {@code /}; an empty result becomes {@code "/"}.</li>
 *   <li><strong>Step 3 — reject a malformed result.</strong> {@link #violatedRule} checks the
 *       normalized path against a fixed, ordered list of rules and returns the first one it
 *       violates: {@code wildcard}, {@code router pattern}, {@code query}, {@code fragment},
 *       {@code repeated separator}, {@code dot segment}, {@code encoded separator}, and
 *       {@code unsupported character}. Returns {@code null} when the normalized path violates
 *       none of them, meaning it is made only of {@code /} and the RFC 3986 unreserved characters
 *       ({@code A-Z a-z 0-9 . _ ~ -}).</li>
 *   <li><strong>Step 4 — report the rejection.</strong> The caller
 *       ({@link JaxRsApplicationScanner}) reports one compile error naming the application class,
 *       the {@code @ApplicationPath} value as written ({@link #originalValue}), and the violated
 *       rule.</li>
 * </ol>
 *
 * <p>Step 5 (mount-path derivation) belongs to the runtime composer, not the processor.
 *
 * <p>Since {@code jakarta.ws.rs-api} is a test-scope dependency of this module, the
 * {@code @ApplicationPath} annotation is never imported here; it is located by its
 * fully-qualified name through {@link AnnotationMirrors}, mirroring
 * {@link EffectiveJaxRsContractResolver}'s handling of {@code @Path}.
 */
public final class ApplicationPathGrammar {

    /** FQN of {@code jakarta.ws.rs.ApplicationPath}. */
    private static final String APPLICATION_PATH_FQN = "jakarta.ws.rs.ApplicationPath";

    private ApplicationPathGrammar() {}

    /**
     * Computes the normalized {@code @ApplicationPath} literal (steps 0 to 2) for the given
     * application.
     *
     * @param application the concrete {@code Application} subtype to inspect; must not be
     *                     {@code null}
     * @param ctx          the shared codegen context; must not be {@code null}
     * @return the normalized path, starting with {@code /} and never ending with a trailing
     *     {@code /} other than the root path itself; or {@code null} when no class in
     *     {@code application}'s superclass chain (itself included) carries
     *     {@code @ApplicationPath} (step 0 failure)
     */
    public static String normalize(TypeElement application, CodegenContext ctx) {
        String raw = rawValue(application, ctx);
        if (raw == null) {
            return null;
        }
        return normalizeEnd(normalizeStart(raw));
    }

    /**
     * Returns the {@code @ApplicationPath} value as written, before normalization, for the error
     * message accompanying a step-3 rejection.
     *
     * @param application the concrete {@code Application} subtype to inspect; must not be
     *                     {@code null}
     * @param ctx          the shared codegen context; must not be {@code null}
     * @return the value as written; or {@code null} when no class in {@code application}'s
     *     superclass chain carries {@code @ApplicationPath} (step 0 failure)
     */
    public static String originalValue(TypeElement application, CodegenContext ctx) {
        return rawValue(application, ctx);
    }

    /**
     * Step 3: checks the normalized path against a fixed, ordered list of rules and returns the
     * first one it violates, in this order: {@code wildcard} (contains an asterisk); {@code
     * router pattern} (contains a colon or a curly brace); {@code query} (contains a question
     * mark); {@code fragment} (contains a hash sign); {@code repeated separator} (contains
     * {@code //}); {@code dot segment} (a {@code /}-separated segment equal to {@code .} or
     * {@code ..}); {@code encoded separator} (contains {@code %2F}, {@code %2f}, {@code %5C}, or
     * {@code %5c}); {@code unsupported character} (any character other than {@code /} outside
     * {@code A-Z a-z 0-9 . _ ~ -}, including any other percent sign).
     *
     * @param normalizedPath the result of {@link #normalize}; must not be {@code null}
     * @return the first violated rule's name; or {@code null} when {@code normalizedPath}
     *     violates none of them
     */
    public static String violatedRule(String normalizedPath) {
        if (normalizedPath.indexOf('*') >= 0) {
            return "wildcard";
        }
        if (hasAny(normalizedPath, ':', '{', '}')) {
            return "router pattern";
        }
        if (normalizedPath.indexOf('?') >= 0) {
            return "query";
        }
        if (normalizedPath.indexOf('#') >= 0) {
            return "fragment";
        }
        if (normalizedPath.contains("//")) {
            return "repeated separator";
        }
        if (hasDotSegment(normalizedPath)) {
            return "dot segment";
        }
        if (hasEncodedSeparator(normalizedPath)) {
            return "encoded separator";
        }
        if (hasUnsupportedCharacter(normalizedPath)) {
            return "unsupported character";
        }
        return null;
    }

    // --- Internal helpers ---

    private static boolean hasAny(String v, char... chars) {
        for (char c : chars) {
            if (v.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** A {@code /}-separated segment equal to {@code .} or {@code ..}. */
    private static boolean hasDotSegment(String v) {
        for (String segment : v.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEncodedSeparator(String v) {
        return v.contains("%2F") || v.contains("%2f") || v.contains("%5C") || v.contains("%5c");
    }

    /** Any character other than {@code /} outside {@code A-Z a-z 0-9 . _ ~ -}. */
    private static boolean hasUnsupportedCharacter(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '/') {
                continue;
            }
            boolean unreserved = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || c == '-';
            if (!unreserved) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code @ApplicationPath} value from the nearest class in {@code application}'s
     * superclass chain, starting with {@code application} itself and stopping before
     * {@code java.lang.Object}. An interface is never read.
     */
    private static String rawValue(TypeElement application, CodegenContext ctx) {
        TypeElement current = application;
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            var mirror = AnnotationMirrors.findByFqn(current, APPLICATION_PATH_FQN);
            if (mirror.isPresent()) {
                return ctx.annotations()
                        .attribute(mirror.get(), "value", String.class)
                        .orElse("");
            }
            current = JaxRsHierarchy.superClass(ctx, current);
        }
        return null;
    }

    /** Step 1: an empty value becomes {@code "/"}; a value without a leading {@code /} gets one. */
    private static String normalizeStart(String v) {
        String s = v.isEmpty() ? "/" : v;
        return s.startsWith("/") ? s : "/" + s;
    }

    /**
     * Step 2: one terminal {@code /*} is removed, then every trailing {@code /}; an empty result
     * becomes {@code "/"}.
     */
    private static String normalizeEnd(String v) {
        String s = v;
        if (s.endsWith("/*")) {
            s = s.substring(0, s.length() - 2);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "/" : s;
    }
}
