// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.sources;

import dev.vertique.rest.validation.AnnotationSchemaSource;
import jakarta.validation.Validator;
import java.util.Optional;

/**
 * A subclass of the canonical {@link AnnotationSchemaSource} that overrides nothing: it produces
 * exactly the canonical schemas and manifests, but its runtime class is not the canonical class.
 */
public final class CanonicalSubclassSchemaSource extends AnnotationSchemaSource {

    /** Creates a source that consults no Bean Validation. */
    public CanonicalSubclassSchemaSource() {
        super();
    }

    /**
     * Creates a source consulting the given Bean Validation {@link Validator} when present.
     *
     * @param validator the optional validator
     */
    public CanonicalSubclassSchemaSource(Optional<Validator> validator) {
        super(validator);
    }
}
