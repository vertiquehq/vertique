// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import java.util.List;
import java.util.Objects;

/**
 * INTERNAL generated-code contract — not for hand-written use and outside the maturity promise.
 * Emitted by {@code vertique-codegen-jaxrs}'s {@code @RestApplication} declaration scanner and
 * registration emitter; never constructed by hand.
 *
 * <p>Describes one {@code @RestApplication} declaration's registration: its declaring interface,
 * name, normalized path, listed resources (or an empty list when discovered instead), whether its
 * resources are discovered at startup, its OpenAPI contract location, and whether its evaluated
 * conditional activation currently matches. The declaring interface is never constructed.
 *
 * <p>This type evolves additively: a new attribute arrives as a new {@link #of} overload; an
 * existing factory signature stays for at least one further minor release line, so a registration
 * emitted by an older processor keeps starting against a newer runtime; the factory's re-checks
 * (name grammar, reserved names, path form, membership form) may only loosen, never tighten,
 * across releases.
 */
public final class GeneratedRestApplicationRegistration {

    /** The application name grammar; also enforced by the annotation processor at compile time. */
    private static final String NAME_GRAMMAR = "[a-z0-9][a-z0-9_-]{0,63}";

    private static final int NAME_MAX_LENGTH = 64;

    private final Class<?> declaringType;
    private final String name;
    private final String path;
    private final List<Class<?>> resources;
    private final boolean discover;
    private final String openapiPath;
    private final boolean active;

    private GeneratedRestApplicationRegistration(
            Class<?> declaringType,
            String name,
            String path,
            List<Class<?>> resources,
            boolean discover,
            String openapiPath,
            boolean active) {
        this.declaringType = declaringType;
        this.name = name;
        this.path = path;
        this.resources = resources;
        this.discover = discover;
        this.openapiPath = openapiPath;
        this.active = active;
    }

    /**
     * Creates a new registration. This is the fail-closed backstop for a registration the
     * annotation processor did not produce: it re-checks the name grammar and reserved names, the
     * normalized path form, and the exactly-one-of membership form.
     *
     * @param declaringType the declaring interface
     * @param name          the application name; must match {@code [a-z0-9][a-z0-9_-]{0,63}} and
     *                      must not be the reserved {@code none} or {@code null}
     * @param path          the normalized application path
     * @param resources     the listed resource classes, in the order written; empty when
     *                      {@code discover} is used instead. Copied defensively; the returned
     *                      registration's {@link #resources()} is unmodifiable
     * @param discover      whether resources are discovered at startup instead of listed
     * @param openapiPath   the OpenAPI contract location, as written; {@code ""} means the global
     *                      {@code jaxrs.openapiPath}
     * @param active        the evaluated conditional activation
     * @return the new registration
     * @throws NullPointerException     if {@code declaringType}, {@code name}, {@code path},
     *                                  {@code resources}, or {@code openapiPath} is {@code null};
     *                                  the message names the null argument
     * @throws IllegalArgumentException if {@code name} is outside the grammar or reserved, if
     *                                  {@code path} is not in normalized form, or if
     *                                  {@code resources} and {@code discover} are not exactly one
     *                                  of a non-empty list and {@code true}; the message names
     *                                  {@code declaringType}'s binary name and the violated rule
     */
    public static GeneratedRestApplicationRegistration of(
            Class<?> declaringType,
            String name,
            String path,
            List<Class<?>> resources,
            boolean discover,
            String openapiPath,
            boolean active) {
        Objects.requireNonNull(declaringType, "declaringType must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(resources, "resources must not be null");
        Objects.requireNonNull(openapiPath, "openapiPath must not be null");
        requireValidName(declaringType, name);
        requireNormalizedPath(declaringType, path);
        List<Class<?>> copiedResources = List.copyOf(resources);
        requireExactlyOneMembershipForm(declaringType, copiedResources, discover);
        return new GeneratedRestApplicationRegistration(
                declaringType, name, path, copiedResources, discover, openapiPath, active);
    }

    /**
     * Checks {@code name} against the application name grammar and the reserved names.
     *
     * @param declaringType the declaring interface, named in a thrown message
     * @param name          the candidate name
     * @throws IllegalArgumentException if {@code name} is outside the grammar or reserved
     */
    private static void requireValidName(Class<?> declaringType, String name) {
        if (!matchesNameGrammar(name)) {
            throw new IllegalArgumentException("Application declared by " + declaringType.getName() + " has name '"
                    + name + "' that does not match the application name grammar " + NAME_GRAMMAR);
        }
        if (name.equals("none") || name.equals("null")) {
            throw new IllegalArgumentException(
                    "Application declared by " + declaringType.getName() + " has name '" + name + "' that is reserved");
        }
    }

    /**
     * Checks whether {@code name} matches {@code [a-z0-9][a-z0-9_-]{0,63}}. Implemented as a
     * single left-to-right character scan (no backtracking regular expression) so its cost is
     * linear in {@code name}'s length.
     *
     * @param name the candidate name
     * @return {@code true} when {@code name} matches the grammar
     */
    private static boolean matchesNameGrammar(String name) {
        int length = name.length();
        if (length == 0 || length > NAME_MAX_LENGTH) {
            return false;
        }
        if (!isNameLetterOrDigit(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < length; i++) {
            char c = name.charAt(i);
            if (!isNameLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether {@code c} is one of the letters or digits an application name may contain.
     *
     * @param c the character to check
     * @return {@code true} when {@code c} is in {@code [a-z0-9]}
     */
    private static boolean isNameLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * Checks {@code path} against the application path grammar's normalized form.
     *
     * @param declaringType the declaring interface, named in a thrown message
     * @param path          the candidate path
     * @throws IllegalArgumentException if {@code path} is not normalized
     */
    private static void requireNormalizedPath(Class<?> declaringType, String path) {
        if (!isNormalizedPath(path)) {
            throw new IllegalArgumentException("Application declared by " + declaringType.getName() + " has path '"
                    + path + "' that is not in the normalized form the application path grammar requires: '/',"
                    + " or one or more '/segment' parts, each matching [A-Za-z0-9._~-]+ and neither '.' nor '..'");
        }
    }

    /**
     * Checks whether {@code path} is in the application path grammar's normalized form: {@code
     * "/"}, or one or more {@code "/segment"} parts, each matching {@code [A-Za-z0-9._~-]+} and
     * neither {@code "."} nor {@code ".."}. Implemented as a single left-to-right character scan
     * (no backtracking regular expression) so its cost is linear in {@code path}'s length.
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
     * Checks that exactly one of a non-empty {@code resources} and {@code discover} is set.
     *
     * @param declaringType the declaring interface, named in a thrown message
     * @param resources     the already-copied listed resource classes
     * @param discover      whether resources are discovered instead of listed
     * @throws IllegalArgumentException if both, or neither, form is set
     */
    private static void requireExactlyOneMembershipForm(
            Class<?> declaringType, List<Class<?>> resources, boolean discover) {
        boolean hasResources = !resources.isEmpty();
        if (hasResources == discover) {
            throw new IllegalArgumentException("Application declared by " + declaringType.getName()
                    + " must set exactly one of a non-empty resources list and discover = true");
        }
    }

    /**
     * Returns the declaring interface.
     *
     * @return the declaring interface
     */
    public Class<?> declaringType() {
        return declaringType;
    }

    /**
     * Returns the application name.
     *
     * @return the application name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the normalized application path.
     *
     * @return the normalized application path
     */
    public String path() {
        return path;
    }

    /**
     * Returns the listed resource classes, in the order written.
     *
     * @return the unmodifiable listed resource classes; empty when {@link #discover()} is
     *     {@code true}
     */
    public List<Class<?>> resources() {
        return resources;
    }

    /**
     * Returns whether resources are discovered at startup instead of listed.
     *
     * @return {@code true} when resources are discovered instead of listed
     */
    public boolean discover() {
        return discover;
    }

    /**
     * Returns the OpenAPI contract location, as written.
     *
     * @return the OpenAPI contract location, or {@code ""} for the global {@code jaxrs.openapiPath}
     */
    public String openapiPath() {
        return openapiPath;
    }

    /**
     * Returns the evaluated conditional activation.
     *
     * @return {@code true} when this registration is active
     */
    public boolean active() {
        return active;
    }
}
