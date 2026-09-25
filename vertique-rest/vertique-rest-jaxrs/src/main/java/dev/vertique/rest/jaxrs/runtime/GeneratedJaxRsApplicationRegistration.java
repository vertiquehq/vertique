// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.inject.Provider;
import jakarta.ws.rs.core.Application;
import java.util.Objects;

/**
 * INTERNAL framework seam — generated-code contract; not for hand-written use and outside the
 * maturity promise. Emitted by {@code vertique-codegen-jaxrs}'s application registration emitter
 * and consumed by the package-private {@code JaxRsApplicationComposer}.
 *
 * <p>Describes one JAX-RS {@link Application} registration: its declared type, its normalized
 * application path, whether its conditional-activation annotation currently matches, and a
 * factory that constructs the application instance on demand.
 *
 * <p>This type evolves additively: a new factory overload may be added, an existing factory
 * signature stays for at least one further minor release line, and the registration factory's
 * normalized-path check only ever becomes looser, never stricter, so a registration produced by an
 * earlier annotation processor keeps starting against a newer runtime.
 */
public final class GeneratedJaxRsApplicationRegistration {

    private final Class<? extends Application> type;
    private final String path;
    private final boolean active;
    private final Provider<? extends Application> factory;

    private GeneratedJaxRsApplicationRegistration(
            Class<? extends Application> type, String path, boolean active, Provider<? extends Application> factory) {
        this.type = type;
        this.path = path;
        this.active = active;
        this.factory = factory;
    }

    /**
     * Creates a new application registration.
     *
     * @param type    the application's declared type
     * @param path    the application's registration path; must be in the application path
     *                grammar's normalized form: {@code "/"}, or one or more {@code "/segment"}
     *                parts, each matching {@code [A-Za-z0-9._~-]+} and neither {@code "."} nor
     *                {@code ".."}
     * @param active  whether the application's {@code @ConditionalOnProperty} conditions
     *                currently match, as evaluated by the generated registration method
     * @param factory constructs the application instance; never invoked by this factory method
     * @return the new registration
     * @throws NullPointerException     if {@code type}, {@code path}, or {@code factory} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code path} is not in the normalized form; the message
     *                                  names {@code type} and the rejected {@code path}. This is a
     *                                  fail-closed runtime backstop for a registration the
     *                                  annotation processor did not produce; it only ever becomes
     *                                  looser across releases, never stricter
     */
    public static GeneratedJaxRsApplicationRegistration of(
            Class<? extends Application> type, String path, boolean active, Provider<? extends Application> factory) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        if (!isNormalizedPath(path)) {
            throw new IllegalArgumentException("Application " + type.getName() + "'s registration path '" + path
                    + "' is not in the normalized form the JAX-RS application path grammar requires: '/', or one"
                    + " or more '/segment' parts, each matching [A-Za-z0-9._~-]+ and neither '.' nor '..'");
        }
        return new GeneratedJaxRsApplicationRegistration(type, path, active, factory);
    }

    /**
     * Checks whether {@code path} is in the application path grammar's normalized form: {@code "/"},
     * or one or more {@code "/segment"} parts, each matching {@code [A-Za-z0-9._~-]+} and neither
     * {@code "."} nor {@code ".."}. Implemented as a single left-to-right character scan (no
     * backtracking regular expression) so its cost is linear in {@code path}'s length.
     *
     * @param path the candidate path
     * @return {@code true} when {@code path} is normalized
     */
    private static boolean isNormalizedPath(String path) {
        if (path.equals("/")) {
            return true;
        }
        if (path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        int length = path.length();
        int segmentStart = 1;
        for (int i = 1; i <= length; i++) {
            if (i != length && path.charAt(i) != '/') {
                continue;
            }
            if (i == segmentStart) {
                // An empty segment: a trailing '/', or two consecutive '/' characters.
                return false;
            }
            String segment = path.substring(segmentStart, i);
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
            for (int j = segmentStart; j < i; j++) {
                if (!isNormalizedPathChar(path.charAt(j))) {
                    return false;
                }
            }
            segmentStart = i + 1;
        }
        return true;
    }

    /**
     * Checks whether {@code c} is a valid character within one path segment.
     *
     * @param c the character to check
     * @return {@code true} when {@code c} is in {@code [A-Za-z0-9._~-]}
     */
    private static boolean isNormalizedPathChar(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.'
                || c == '_'
                || c == '~'
                || c == '-';
    }

    /**
     * Returns the application's declared type.
     *
     * @return the declared type
     */
    public Class<? extends Application> type() {
        return type;
    }

    /**
     * Returns the application's mount path.
     *
     * @return the mount path
     */
    public String path() {
        return path;
    }

    /**
     * Returns whether the application's conditions currently match.
     *
     * @return {@code true} when the application is active
     */
    public boolean active() {
        return active;
    }

    /**
     * Constructs the application instance by delegating to the underlying factory.
     *
     * @return the constructed application instance
     */
    public Application create() {
        return factory.get();
    }
}
