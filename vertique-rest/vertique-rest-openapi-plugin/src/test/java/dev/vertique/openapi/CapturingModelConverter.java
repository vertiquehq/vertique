// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;

/**
 * Fake {@link ModelConverter} that records the {@link AnnotatedType} it was called with and
 * returns a fixed marker {@link Schema}, so tests can assert what a converter under test delegated
 * downstream without depending on Mockito.
 */
class CapturingModelConverter implements ModelConverter {

    private final Schema<?> marker;

    /** The {@link AnnotatedType} passed to the most recent {@link #resolve} call, if any. */
    AnnotatedType capturedType;

    CapturingModelConverter(Schema<?> marker) {
        this.marker = marker;
    }

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        this.capturedType = type;
        return marker;
    }
}
