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
 *   <li>Parse the {@code Accept} header, quote-aware, into a list of {@link MediaType} entries.</li>
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
     *   <li>Entries without a {@code /} are silently skipped. If the header holds only such
     *       entries, the first server type is returned.</li>
     *   <li>Entries that are malformed (an unterminated quoted string, an invalid, quoted,
     *       out-of-range or repeated {@code q}, too many parameters, or a {@code /} with an empty
     *       type or subtype such as {@code text/} or {@code /json}) are dropped and never make
     *       a type acceptable. So are the entries past the first
     *       {@value HeaderElement#MAX_ELEMENTS} non-empty ones: they are never read, and their
     *       existence counts as a malformed entry. When no usable entry remains and at least one
     *       entry was dropped as malformed or lies past that cap, the result is {@code null} (406),
     *       not the first server type: a header the client sent but the server cannot read does
     *       not mean "anything".</li>
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

        ParsedAccept accept = parseAccept(acceptHeader);
        List<MediaType> clientTypes = accept.types();
        if (clientTypes.isEmpty()) {
            return accept.droppedMalformed() ? null : serverTypes.get(0);
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
            Match match = bestMatch(clientTypes, serverType);
            double effectiveQ = match.quality();
            int matchSpecificity = match.specificity();

            if (effectiveQ > bestQuality || (effectiveQ == bestQuality && matchSpecificity > bestSpecificity)) {
                bestQuality = effectiveQ;
                bestSpecificity = matchSpecificity;
                bestServer = serverType.withoutParameters();
            }
        }

        return bestQuality > 0.0 ? bestServer : null;
    }

    /**
     * Returns the quality with which the given media type is acceptable to the client, using the
     * most specific compatible entry of the {@code Accept} header (RFC 9110 §12.5.1).
     *
     * <p>Unlike {@link #negotiate(String, List)}, this primitive never treats a missing or
     * unusable header as "anything goes": a {@code null}, blank or entirely malformed header, an
     * unparsable {@code mediaType}, and a media type that no entry is compatible with all yield
     * {@code 0.0}, meaning not acceptable. The header is parsed quote-aware with
     * {@link #parseAcceptHeader(String)}, so a malformed entry is dropped and can never make a
     * type acceptable.
     *
     * @param acceptHeader the raw {@code Accept} header value, may be {@code null}
     * @param mediaType    the media type to test, such as {@code application/json}; parameters are ignored
     * @return the quality in the range [0.0, 1.0]; {@code 0.0} when the type is not acceptable
     */
    public static double effectiveQuality(String acceptHeader, String mediaType) {
        if (acceptHeader == null) {
            return 0.0;
        }
        MediaType server = MediaType.parse(mediaType);
        if (server == null) {
            return 0.0;
        }
        return effectiveQuality(parseAcceptHeader(acceptHeader), server);
    }

    /**
     * Returns the quality with which {@code serverType} is acceptable given already parsed client
     * entries, using the most specific compatible entry (RFC 9110 §12.5.1). When several entries
     * are equally specific, the one with the highest quality wins if {@code clientTypes} is sorted
     * as {@link #parseAcceptHeader(String)} returns it.
     *
     * @param clientTypes the parsed {@code Accept} entries; must not be {@code null}
     * @param serverType  the media type to test; must not be {@code null}
     * @return the quality in the range [0.0, 1.0]; {@code 0.0} when no entry is compatible
     */
    public static double effectiveQuality(List<MediaType> clientTypes, MediaType serverType) {
        return Math.max(0.0, bestMatch(clientTypes, serverType).quality());
    }

    /** The quality and specificity of the most specific compatible client entry, or {@code -1} for both. */
    private record Match(double quality, int specificity) {}

    private static Match bestMatch(List<MediaType> clientTypes, MediaType serverType) {
        double effectiveQ = -1.0;
        int matchSpecificity = -1;
        for (MediaType clientType : clientTypes) {
            if (clientType.isCompatible(serverType) && clientType.specificity() > matchSpecificity) {
                matchSpecificity = clientType.specificity();
                effectiveQ = clientType.qualityFactor();
            }
        }
        return new Match(effectiveQ, matchSpecificity);
    }

    /**
     * Parses a comma-separated {@code Accept} header value into a sorted list of
     * {@link MediaType} entries.
     *
     * <p>The list is sorted by quality factor descending, with specificity descending
     * as a tiebreaker (more specific types win ties). The header is split into entries and
     * parameters only outside quoted strings, so a quoted comma or semicolon does not change how
     * the entry is read. Malformed entries are silently excluded: those lacking a {@code /}
     * separator, having an empty type or subtype, containing an unterminated quoted string,
     * carrying an invalid or repeated {@code q}, or having too many parameters (see
     * {@link HeaderElement}). At most the first {@value HeaderElement#MAX_ELEMENTS} non-empty
     * entries are considered; this method does not report that more followed, while
     * {@link #negotiate(String, List)} treats them as a malformed entry.
     *
     * @param accept the raw {@code Accept} header value; must not be {@code null}
     * @return a sorted, unmodifiable list of parsed media types; never {@code null}
     */
    public static List<MediaType> parseAcceptHeader(String accept) {
        return parseAccept(accept).types();
    }

    /**
     * The sorted usable entries of an {@code Accept} header and whether an entry was dropped as
     * malformed or lay past the element cap.
     */
    private record ParsedAccept(List<MediaType> types, boolean droppedMalformed) {}

    private static ParsedAccept parseAccept(String accept) {
        // HeaderElement caps the element count to prevent abuse via excessively complex Accept
        // headers. Vert.x already limits total header size (default 8192 bytes) at the HTTP layer.
        HeaderElement.ParsedList parsed = HeaderElement.parseListChecked(accept);
        List<MediaType> result = new ArrayList<>(parsed.elements().size());
        boolean droppedMalformed = parsed.malformed() > 0 || parsed.truncated();
        for (HeaderElement element : parsed.elements()) {
            MediaType mt = MediaType.fromElement(element);
            if (mt != null) {
                result.add(mt);
            } else if (element.value().indexOf('/') >= 0) {
                // a slash with an empty type or subtype is unreadable, not merely "not a media type"
                droppedMalformed = true;
            }
        }
        result.sort(Comparator.comparingDouble(MediaType::qualityFactor)
                .thenComparingInt(MediaType::specificity)
                .reversed());
        return new ParsedAccept(List.copyOf(result), droppedMalformed);
    }
}
