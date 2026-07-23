// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Static utility for RFC 9110 Accept header negotiation.
 *
 * <p>Given a client {@code Accept} header and a list of media types the server is willing
 * to produce, this class selects the most preferred server type acceptable to the client.
 *
 * <p>The selection algorithm follows RFC 9110 §12.5.1:
 * <ol>
 *   <li>Parse the {@code Accept} header into a list of {@link MediaType} entries.</li>
 *   <li>For each server type, compute the <em>effective quality</em> by finding the most
 *       specific compatible client entry (highest specificity wins; more specific entries
 *       override wildcard entries).</li>
 *   <li>Return the server type with the highest effective quality. Ties are broken by
 *       server declaration order (first declared wins).</li>
 * </ol>
 *
 * <p>This class cannot be instantiated; all methods are static.
 */
public final class AcceptNegotiator {

    /** Prevents instantiation of this utility class. */
    private AcceptNegotiator() {}

    // --- Public API ---

    /**
     * Selects the best server-side media type that satisfies the client's {@code Accept} header.
     *
     * <p>Edge-case behaviour:
     * <ul>
     *   <li>If {@code acceptHeader} is {@code null}, empty, or blank — returns the first server type.</li>
     *   <li>If the {@code Accept} header is {@code *}{@code /*} — returns the first server type.</li>
     *   <li>If {@code serverTypes} is empty — returns {@code null}.</li>
     *   <li>If no server type is compatible with any client-acceptable type — returns {@code null}
     *       (the caller should respond with {@code 406 Not Acceptable}).</li>
     *   <li>Malformed entries in the {@code Accept} header (e.g. tokens without a {@code /}) are
     *       silently skipped.</li>
     * </ul>
     *
     * @param acceptHeader the value of the HTTP {@code Accept} header, may be {@code null}
     * @param serverTypes  the ordered list of media types the server can produce; must not be {@code null}
     * @return the best matching server media type as a {@code type/subtype} string (lowercase),
     *         or {@code null} if no compatible type was found
     */
    public static String negotiate(String acceptHeader, List<String> serverTypes) {
        if (serverTypes == null || serverTypes.isEmpty()) {
            return null;
        }
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return serverTypes.get(0);
        }

        List<MediaType> clientTypes = parseAcceptHeader(acceptHeader);
        if (clientTypes.isEmpty()) {
            return serverTypes.get(0);
        }

        // Pre-parse server types once
        List<MediaType> serverMediaTypes = serverTypes.stream()
                .map(MediaType::parse)
                .filter(java.util.Objects::nonNull)
                .toList();

        // RFC 9110 §12.5.1: compute effective quality per server type using the most
        // specific compatible client entry. More specific entries override wildcards.
        String bestServer = null;
        double bestQuality = -1.0;
        int bestSpecificity = -1;

        for (MediaType serverType : serverMediaTypes) {
            double effectiveQ = -1.0;
            int matchSpecificity = -1;

            for (MediaType clientType : clientTypes) {
                if (clientType.isCompatible(serverType) && clientType.specificity() > matchSpecificity) {
                    matchSpecificity = clientType.specificity();
                    effectiveQ = clientType.qualityFactor();
                }
            }

            if (effectiveQ > bestQuality || (effectiveQ == bestQuality && matchSpecificity > bestSpecificity)) {
                bestQuality = effectiveQ;
                bestSpecificity = matchSpecificity;
                bestServer = serverType.withoutParameters();
            }
        }

        return bestQuality > 0.0 ? bestServer : null;
    }

    /**
     * Parses a comma-separated {@code Accept} header value into a sorted list of
     * {@link MediaType} entries.
     *
     * <p>The list is sorted by quality factor descending, with specificity descending
     * as a tiebreaker (more specific types win ties). Malformed tokens (those lacking a
     * {@code /} separator) are silently excluded.
     *
     * @param accept the raw {@code Accept} header value; must not be {@code null}
     * @return a sorted, unmodifiable list of parsed media types; never {@code null}
     */
    public static List<MediaType> parseAcceptHeader(String accept) {
        String[] tokens = accept.split(",");
        // Cap at 50 entries to prevent abuse via excessively complex Accept headers.
        // Vert.x already limits total header size (default 8192 bytes) at the HTTP layer.
        int limit = Math.min(tokens.length, 50);
        List<MediaType> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String token = tokens[i];
            MediaType mt = MediaType.parse(token.trim());
            if (mt != null) {
                result.add(mt);
            }
        }
        result.sort(Comparator.comparingDouble(MediaType::qualityFactor)
                .thenComparingInt(MediaType::specificity)
                .reversed());
        return List.copyOf(result);
    }
}
