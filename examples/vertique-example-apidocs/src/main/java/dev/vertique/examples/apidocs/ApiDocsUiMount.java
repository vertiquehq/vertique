// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

/**
 * The pinned Redoc script and the content security policy of the example's documentation page.
 *
 * <p>The page loads Redoc's standalone bundle, version 2.5.4, from jsDelivr by its exact version and
 * checks it with Subresource Integrity. The SHA-384 digest was computed on 2026-10-02 from two
 * separate downloads of {@link #REDOC_SCRIPT_URL} that agreed.
 *
 * <p>The policy allows scripts only from this origin and from exactly the pinned script's full URL,
 * never from the whole CDN origin.
 */
final class ApiDocsUiMount {

    /** The full URL of the pinned Redoc standalone bundle. */
    static final String REDOC_SCRIPT_URL = "https://cdn.jsdelivr.net/npm/redoc@2.5.4/bundles/redoc.standalone.js";

    /** The Subresource Integrity value of {@link #REDOC_SCRIPT_URL}. */
    static final String REDOC_SCRIPT_INTEGRITY =
            "sha384-w447zOpYfw/1Tv/5AK9NfHTlQIqE3RVR6KY62jCyy9zNDgO64cMwGGP1Fj0zJVf5";

    /**
     * The page's content security policy: a restrictive base policy whose {@code script-src} names
     * this origin and exactly {@link #REDOC_SCRIPT_URL}.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self' " + REDOC_SCRIPT_URL
            + "; style-src 'self'; connect-src 'self'; frame-ancestors 'none'";

    private ApiDocsUiMount() {}
}
