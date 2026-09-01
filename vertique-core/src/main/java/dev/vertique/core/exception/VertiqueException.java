// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Base exception for all framework-specific exceptions.
 *
 * <p>Provides a common ancestor for semantic categorization:
 * <ul>
 *   <li>{@link ValidationException} — input validation failures (→ 400)
 *     <ul>
 *       <li>{@link BusinessRuleException} — business-rule / domain-rule violations (→ 400)
 *         <ul>
 *           <li>{@link TooManyRequestsException} — rate/quota limit exceeded (→ 429)</li>
 *         </ul>
 *       </li>
 *     </ul>
 *   </li>
 *   <li>{@link ConflictException} — state conflicts (→ 409)</li>
 *   <li>{@link NotFoundException} — missing resources (→ 404)</li>
 *   <li>{@link VertiqueSecurityException} — security grouping root (no default mapping)
 *     <ul>
 *       <li>{@link UnauthorizedException} — missing or invalid authentication (→ 401)</li>
 *       <li>{@link ForbiddenException} — authenticated principal lacks permission (→ 403)</li>
 *     </ul>
 *   </li>
 *   <li>{@link ConfigurationException} — startup / configuration / contract failures</li>
 *   <li>{@link TechnicalException} — runtime technical failures (→ 500)
 *     <ul>
 *       <li>{@link UnavailableException} — required dependency/service unavailable (→ 503)</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public class VertiqueException extends RuntimeException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public VertiqueException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public VertiqueException(String message, Throwable cause) {
        super(message, cause);
    }
}
