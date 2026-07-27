// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;

/**
 * Shared chain-tail helper for this package's {@link ModelConverter} implementations.
 *
 * <p>Every {@link ModelConverter} must check {@link Iterator#hasNext()} before calling {@link
 * Iterator#next()} on the resolution chain — a converter has no way to know whether it is last in
 * the configured chain. This helper centralizes that contract so each converter's {@code resolve}
 * method ends with a single delegating call.
 */
final class ConverterChain {

    private ConverterChain() {}

    /**
     * Delegates resolution to the next converter in {@code chain}, or returns {@code null} when the
     * chain is exhausted.
     *
     * @param type the annotated type being resolved
     * @param context the current model converter context
     * @param chain the remaining converters in the resolution chain
     * @return the schema produced by the next converter in {@code chain}, or {@code null} if the
     *     chain is exhausted
     */
    static Schema<?> delegate(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }
}
