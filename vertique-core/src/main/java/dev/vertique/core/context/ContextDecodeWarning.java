// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * A warning produced by a context decoder when decoding a value fails or is ambiguous.
 *
 * <p>Decoders MUST return warnings rather than logging directly. The propagation orchestrator
 * logs warnings at the throttled WARN level on behalf of the decoder.
 *
 * @param key    the metadata key that triggered the warning
 * @param value  the raw value string at that key (for diagnostic context), or {@code null}
 * @param reason a human-readable description of why the warning was produced
 */
public record ContextDecodeWarning(String key, String value, String reason) {}
