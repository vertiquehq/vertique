// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.ViolationDetail;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Default {@link BeanValidator} implementation backed by a Jakarta {@link Validator}
 * and {@link ViolationDetailMapper} for structured violation conversion.
 *
 * <p>Uses {@link jakarta.validation.executable.ExecutableValidator} for method parameter
 * validation, walking the constraint violation {@link Path} to extract the parameter index
 * and the property path relative to the parameter.
 */
@Singleton
public class DefaultBeanValidator implements BeanValidator {

    private final Validator validator;
    private final jakarta.validation.executable.ExecutableValidator executableValidator;
    private final ViolationDetailMapper detailMapper;

    /**
     * Creates a new default bean validator.
     *
     * @param validator    the Jakarta validator instance
     * @param detailMapper the mapper for converting constraint violations to violation details
     */
    @Inject
    DefaultBeanValidator(Validator validator, ViolationDetailMapper detailMapper) {
        this.validator = validator;
        this.executableValidator = validator.forExecutables();
        this.detailMapper = detailMapper;
    }

    /** {@inheritDoc} */
    @Override
    public <T> void validate(T object) {
        Objects.requireNonNull(object, "Validation target must not be null");
        Set<ConstraintViolation<T>> violations = validator.validate(object);
        if (!violations.isEmpty()) {
            throw new BeanValidationException("Validation failed", detailMapper.toDetails(violations));
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> void validate(T object, Class<?>... groups) {
        Objects.requireNonNull(object, "Validation target must not be null");
        Set<ConstraintViolation<T>> violations = validator.validate(object, groups);
        if (!violations.isEmpty()) {
            throw new BeanValidationException("Validation failed", detailMapper.toDetails(violations));
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> List<ViolationDetail> check(T object) {
        Objects.requireNonNull(object, "Validation target must not be null");
        return detailMapper.toDetails(validator.validate(object));
    }

    /** {@inheritDoc} */
    @Override
    public <T> List<ViolationDetail> check(T object, Class<?>... groups) {
        Objects.requireNonNull(object, "Validation target must not be null");
        return detailMapper.toDetails(validator.validate(object, groups));
    }

    /** {@inheritDoc} */
    @Override
    public List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups) {
        Objects.requireNonNull(instance, "Instance must not be null");
        Objects.requireNonNull(method, "Method must not be null");
        Objects.requireNonNull(args, "Args must not be null");
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<Object>> violations = (groups.length > 0)
                ? executableValidator.validateParameters(instance, method, args, groups)
                : executableValidator.validateParameters(instance, method, args);
        if (violations.isEmpty()) {
            return List.of();
        }
        return violations.stream().map(this::toParameterViolation).toList();
    }

    /** {@inheritDoc} */
    @Override
    public void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups) {
        List<ParameterViolation> violations = checkParameters(instance, method, args, groups);
        if (!violations.isEmpty()) {
            List<ViolationDetail> details =
                    violations.stream().map(ParameterViolation::detail).toList();
            throw new BeanValidationException("Validation failed", details);
        }
    }

    /**
     * Converts a single method parameter constraint violation to a {@link ParameterViolation}.
     *
     * <p>Converts the violation {@link Path} after its {@link ElementKind#PARAMETER} node into a
     * {@link PropertyPath} (runtime owner classes, list and array positions, opaque markers for map
     * entries and set members) and renders the detail path from it, for example {@code items[1].name}.
     * If the conversion fails, the violation carries no structured path and the detail path joins the
     * property names with dots. Delegates type and args resolution to {@link ViolationDetailMapper}.
     *
     * @param violation the constraint violation from executable validation
     * @return the parameter violation with index, structured path and cleaned detail
     */
    private ParameterViolation toParameterViolation(ConstraintViolation<?> violation) {
        PropertyPathConverter.Converted converted = PropertyPathConverter.convert(violation);
        int paramIndex = converted.parameterIndex();
        PropertyPath structuredPath = converted.path();
        String detailPath;
        if (structuredPath != null) {
            detailPath = structuredPath.toJavaPath();
        } else {
            StringBuilder propertyPath = new StringBuilder();
            try {
                paramIndex = -1;
                boolean pastParameter = false;
                for (Path.Node node : violation.getPropertyPath()) {
                    if (node.getKind() == ElementKind.PARAMETER) {
                        paramIndex = node.as(Path.ParameterNode.class).getParameterIndex();
                        pastParameter = true;
                    } else if (pastParameter && node.getKind() == ElementKind.PROPERTY) {
                        if (!propertyPath.isEmpty()) {
                            propertyPath.append('.');
                        }
                        propertyPath.append(node.getName());
                    }
                }
            } catch (RuntimeException | StackOverflowError e) {
                // The path cannot be read at all: report what was gathered, never fail validation.
            }
            detailPath = propertyPath.toString();
        }

        ViolationDetail rawDetail = detailMapper.toDetail(violation);
        ViolationDetail cleanedDetail =
                new ViolationDetail(detailPath, rawDetail.message(), rawDetail.type(), rawDetail.args());
        return new ParameterViolation(paramIndex, cleanedDetail, structuredPath);
    }
}
