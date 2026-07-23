// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable value type that captures everything needed to resolve and format a localized message.
 *
 * <p>A {@code MessageResolvable} carries an ordered list of fallback codes, optional format
 * arguments, and an optional default message. {@link MessageSource} implementations try each code
 * in order and return the first match; if none is found and a {@link #defaultMessage()} is
 * present, it is returned instead. If no default is present and no code resolves,
 * {@link NoSuchMessageException} is thrown.
 *
 * <h2>Constructor invariants (FR-LOC-022..024)</h2>
 * <ul>
 *   <li>{@link #codes} must not be {@code null} (NPE) and must not be empty (IAE).</li>
 *   <li>Null elements inside {@code codes} throw {@link NullPointerException} (via
 *       {@link List#copyOf}).</li>
 *   <li>Blank elements inside {@code codes} throw {@link IllegalArgumentException}.</li>
 *   <li>{@link #args} may be {@code null}; it is normalized to {@link List#of()} for caller
 *       convenience. {@code null} elements are preserved because {@link java.text.MessageFormat}
 *       accepts them.</li>
 *   <li>{@link #defaultMessage} is stored verbatim, including {@code null}.</li>
 * </ul>
 *
 * @param codes          ordered list of message codes tried left-to-right during resolution;
 *                       never {@code null} or empty; elements must not be {@code null} or blank
 * @param args           format arguments passed to {@link java.text.MessageFormat} when the
 *                       resolved pattern contains placeholders; may be {@code null} (normalized to
 *                       empty); {@code null} elements within the list are allowed
 * @param defaultMessage fallback string returned when no code resolves; {@code null} means
 *                       "no default — throw {@link NoSuchMessageException}"
 */
public record MessageResolvable(List<String> codes, List<Object> args, String defaultMessage) {

    /**
     * Compact constructor that validates and defensively copies the mutable inputs.
     */
    public MessageResolvable {
        // codes: null → NPE, empty → IAE, null element → NPE (via List.copyOf), blank → IAE
        if (codes == null) {
            throw new NullPointerException("codes must not be null");
        }
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("codes must not be empty");
        }
        codes = List.copyOf(codes); // rejects null elements with NPE
        for (String code : codes) {
            if (code.isBlank()) {
                throw new IllegalArgumentException("codes must not contain blank entry");
            }
        }

        // args: null → normalize to empty; preserve null elements for MessageFormat compatibility
        if (args == null) {
            args = List.of();
        } else {
            args = Collections.unmodifiableList(new ArrayList<>(args));
        }

        // defaultMessage: stored verbatim
    }
}
