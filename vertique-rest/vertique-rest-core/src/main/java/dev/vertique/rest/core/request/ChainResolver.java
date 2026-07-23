// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.List;

/**
 * Applies an ordered canonicalizer + sanitizer chain to a single string value, sourcing
 * processor instances from the configured factories.
 *
 * <p>This SPI is constructed once by {@link DefaultInputObjectProcessor} from the
 * canonicalizer/sanitizer resolver functions passed to its constructor, and threaded through
 * generated {@link GeneratedInputProcessor} calls. Generated code never sees the raw
 * {@code Function<Class, Canonicalizer>} factories — it goes through this contract so the chain
 * application logic stays centralized.
 */
@FunctionalInterface
public interface ChainResolver {

    /**
     * Applies a canonicalizer chain followed by a sanitizer chain to the given value.
     *
     * <p>Canonicalizers run in declaration order, each receiving the output of the previous.
     * Sanitizers run after all canonicalizers, also in declaration order.
     *
     * @param value          the input string; must not be {@code null}
     * @param canonicalizers the ordered canonicalizer chain; must not be {@code null}, may be empty
     * @param sanitizers     the ordered sanitizer chain; must not be {@code null}, may be empty
     * @param valueContext   contextual metadata about the value being processed
     * @return the processed string
     */
    String apply(
            String value,
            List<Class<? extends Canonicalizer>> canonicalizers,
            List<Class<? extends Sanitizer>> sanitizers,
            InputValueContext valueContext);
}
