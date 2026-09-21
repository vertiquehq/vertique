// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/**
 * Builds {@link jakarta.validation.Validator} instances for the {@link MetadataConstraintSource}
 * coverage tests, bootstrapped with {@link ParameterMessageInterpolator} so no expression-language
 * implementation is needed on the classpath — the same finding the design's bv-metadata probes made.
 */
final class MetadataTestValidators {

    private MetadataTestValidators() {}

    /** A plain validator over the annotated and Lombok-generated test fixtures. */
    static jakarta.validation.Validator plain() {
        return factory().getValidator();
    }

    private static jakarta.validation.ValidatorFactory factory() {
        return jakarta.validation.Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
    }

    /**
     * A validator whose constraints additionally come from an XML constraint mapping — a shape no
     * annotation walk can ever see.
     *
     * @param mappingXml the {@code constraint-mappings} XML document text
     * @return the built validator
     */
    static jakarta.validation.Validator withXmlMapping(String mappingXml) {
        InputStream stream = new ByteArrayInputStream(mappingXml.getBytes(StandardCharsets.UTF_8));
        return jakarta.validation.Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .addMapping(stream)
                .buildValidatorFactory()
                .getValidator();
    }
}
