// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

/**
 * Symmetric mount-path conflict comparison shared by {@link JaxRsApplicationComposer}'s conflict
 * rejection and the rest-jaxrs {@code MountCompositionValidator}.
 *
 * <p>Two mount paths conflict when their prefixes — each path without its terminal {@code *} — are
 * equal, or one prefix starts with the other. {@code /*}'s prefix is {@code /}, which conflicts
 * with every other mount path; {@code /api/public/*} and {@code /api/publicity/*} do not conflict,
 * because neither prefix starts with the other.
 */
final class JaxRsMountPaths {

    private JaxRsMountPaths() {}

    /**
     * Returns whether {@code mountPathA} and {@code mountPathB} conflict: their prefixes (each
     * mount path without its terminal {@code *}) are equal, or one prefix starts with the other.
     * Symmetric in its two arguments.
     *
     * @param mountPathA one mount path
     * @param mountPathB the other mount path
     * @return {@code true} when the two mount paths conflict
     */
    static boolean conflict(String mountPathA, String mountPathB) {
        String prefixA = prefix(mountPathA);
        String prefixB = prefix(mountPathB);
        return prefixA.equals(prefixB) || prefixA.startsWith(prefixB) || prefixB.startsWith(prefixA);
    }

    /**
     * Returns whether {@code mountPath} is a router-pattern path: its prefix (the mount path
     * without its terminal {@code *}) contains a colon, an opening brace, or a closing brace. A
     * non-application JAX-RS mount with a router-pattern path conflicts with every application
     * mount, because the segment it matches is not known until request time.
     *
     * @param mountPath a mount path
     * @return {@code true} when {@code mountPath}'s prefix is a router pattern
     */
    static boolean isRouterPattern(String mountPath) {
        String prefix = prefix(mountPath);
        return prefix.contains(":") || prefix.contains("{") || prefix.contains("}");
    }

    /**
     * Returns {@code mountPath} without its terminal {@code *}, or {@code mountPath} unchanged when
     * it does not end with {@code *}.
     *
     * @param mountPath a mount path
     * @return the mount path's prefix
     */
    private static String prefix(String mountPath) {
        return mountPath.endsWith("*") ? mountPath.substring(0, mountPath.length() - 1) : mountPath;
    }
}
