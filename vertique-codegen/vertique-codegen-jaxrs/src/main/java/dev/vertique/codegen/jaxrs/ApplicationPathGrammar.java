// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import javax.lang.model.element.TypeElement;

/**
 * The single compile-time authority for the {@code @ApplicationPath} normalization grammar.
 *
 * <p>This class implements steps 0 to 2 only:
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
 * </ol>
 *
 * <p>Steps 3 (rejection) and 4 (reporting) for malformed values are added later, on this same
 * class, so that the grammar stays the single place this logic lives. Step 5 (mount-path
 * derivation) belongs to the runtime composer, not the processor.
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

    // --- Internal helpers ---

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
