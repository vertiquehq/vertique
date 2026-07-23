// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import java.util.Locale;
import java.util.Optional;

/**
 * SPI for resolving and formatting localized messages.
 *
 * <p>Implementations look up a message pattern for the given {@code code} in the configured
 * resource bundle(s), format it with the supplied {@code args} via
 * {@link java.text.MessageFormat}, and return the final string.
 *
 * <h2>Null-safety contract</h2>
 * <ul>
 *   <li>A {@code null} {@code code} argument causes {@link NullPointerException}.</li>
 *   <li>A blank {@code code} argument causes {@link NoSuchMessageException}.</li>
 *   <li>A {@code null} {@code locale} argument causes {@link NullPointerException}.</li>
 *   <li>A {@code null} {@code args} array is treated identically to an empty args array
 *       (FR-LOC-083); null elements inside {@code args} are forwarded to {@code MessageFormat}
 *       unchanged.</li>
 * </ul>
 *
 * <h2>Formatting contract (FR-LOC-080..089)</h2>
 * <p>When {@code args} is empty or absent, message patterns without placeholders are returned
 * as-is. Implementations may optionally skip the {@code MessageFormat} pass in this case unless
 * {@link dev.vertique.localization.config.LocalizationConfig#alwaysUseMessageFormat()} is
 * {@code true}. Full {@code MessageFormat} implementation is delivered in Slice 3.
 *
 * <h2>Binding note (FR-LOC-011)</h2>
 * <p>{@code MessageSource} instances are application-scoped and are <em>not</em> provided by the
 * framework's Dagger module. Each application creates its own instance(s) via
 * {@link MessageSourceFactory}.
 *
 * @see MessageSourceFactory
 * @see MessageResolvable
 * @see NoSuchMessageException
 */
public interface MessageSource {

    /**
     * Resolves the message for the given code and locale, then formats it with the supplied
     * arguments.
     *
     * @param code   the message code to resolve; must not be {@code null}; a blank value triggers
     *               {@link NoSuchMessageException}
     * @param locale the target locale; must not be {@code null}
     * @param args   optional format arguments passed to {@link java.text.MessageFormat}
     * @return the resolved and formatted message string
     * @throws NoSuchMessageException if the code is blank or cannot be resolved and no default is
     *                                available
     * @throws NullPointerException   if {@code code} or {@code locale} is {@code null}
     */
    String getMessage(String code, Locale locale, Object... args);

    /**
     * Resolves the message for the given code and locale, returning the supplied
     * {@code defaultMessage} if the code cannot be resolved.
     *
     * @param code           the message code to resolve; must not be {@code null}; a blank value
     *                       triggers {@link NoSuchMessageException}
     * @param defaultMessage the fallback string to return when the code is not found; may be
     *                       {@code null}, in which case {@link NoSuchMessageException} is thrown
     *                       if the code is also missing
     * @param locale         the target locale; must not be {@code null}
     * @param args           optional format arguments passed to {@link java.text.MessageFormat}
     * @return the resolved and formatted message string, or {@code defaultMessage}
     * @throws NoSuchMessageException if the code is blank, or cannot be resolved and
     *                                {@code defaultMessage} is {@code null}
     * @throws NullPointerException   if {@code code} or {@code locale} is {@code null}
     */
    String getMessage(String code, String defaultMessage, Locale locale, Object... args);

    /**
     * Attempts to resolve the message for the given code and locale without throwing for missing
     * codes. Input validation still throws — only a successful lookup miss yields
     * {@link Optional#empty()}.
     *
     * @param code   the message code to resolve; must not be {@code null}; a blank value triggers
     *               {@link NoSuchMessageException}
     * @param locale the target locale; must not be {@code null}
     * @param args   optional format arguments passed to {@link java.text.MessageFormat}
     * @return an {@link Optional} containing the resolved and formatted message, or
     *         {@link Optional#empty()} if the code is not found
     * @throws NoSuchMessageException if {@code code} is blank
     * @throws NullPointerException   if {@code code} or {@code locale} is {@code null}
     */
    Optional<String> findMessage(String code, Locale locale, Object... args);

    /**
     * Resolves and formats the message described by the {@link MessageResolvable}, trying each
     * code in order and falling back to {@link MessageResolvable#defaultMessage()} if none resolve.
     *
     * @param resolvable a non-{@code null} descriptor carrying codes, args, and an optional
     *                   default; the first code that resolves wins
     * @param locale     the target locale; must not be {@code null}
     * @return the resolved and formatted message string
     * @throws NoSuchMessageException if no code in the resolvable resolves and
     *                                {@link MessageResolvable#defaultMessage()} is {@code null}
     * @throws NullPointerException   if {@code resolvable} or {@code locale} is {@code null}
     */
    String getMessage(MessageResolvable resolvable, Locale locale);
}
