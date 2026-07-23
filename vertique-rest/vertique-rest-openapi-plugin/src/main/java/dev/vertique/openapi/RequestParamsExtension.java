// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.PrimitiveType;
import io.swagger.v3.jaxrs2.ResolvedParameter;
import io.swagger.v3.jaxrs2.ext.AbstractOpenAPIExtension;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtension;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.CookieParameter;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Swagger Core {@link OpenAPIExtension} that expands method parameters whose type is annotated
 * with {@code @RequestParams} into individual query/path/header/cookie parameters in the
 * generated OpenAPI spec.
 *
 * <p>This mirrors the behaviour of Swagger's built-in {@code @BeanParam} expansion, but works
 * with the framework's own {@code @RequestParams} class-level marker. Records are supported:
 * record components are inspected via {@link Class#getRecordComponents()}; regular POJOs use
 * {@link Class#getDeclaredFields()}.
 *
 * <p>Registered automatically via {@code ServiceLoader} — no {@code pom.xml} changes are
 * needed in consumer modules beyond including this artifact on the swagger-maven-plugin classpath.
 *
 * <p><strong>Limitation:</strong> {@code @FormParam} fields are not included in the generated spec
 * because form parameters are part of a request body, not individual OpenAPI parameters. The
 * runtime correctly populates {@code @FormParam} record components; only the spec generation omits
 * them.
 */
public class RequestParamsExtension extends AbstractOpenAPIExtension {

    /**
     * Canonical class name of the {@code @RequestParams} annotation. Using the name string avoids
     * a compile-time dependency on {@code rest-core}.
     */
    private static final String REQUEST_PARAMS_CLASS = "dev.vertique.rest.core.request.RequestParams";

    /**
     * Canonical class name of Jakarta {@code @Nullable} — marks parameters as not required and
     * sets {@code nullable: true} on the schema.
     */
    private static final String NULLABLE_CLASS = "jakarta.annotation.Nullable";

    /**
     * {@inheritDoc}
     *
     * <p>If the parameter type carries {@code @RequestParams}, expands its fields or record
     * components into individual OpenAPI {@link Parameter} instances. Otherwise delegates to the
     * next extension in the chain.
     *
     * @param annotations        annotations declared on the method parameter
     * @param type               the Java type of the parameter
     * @param typesToSkip        types already being resolved (cycle guard)
     * @param components         the OpenAPI {@link Components} object for schema registration
     * @param classConsumes      class-level {@link Consumes} annotation, or {@code null}
     * @param methodConsumes     method-level {@link Consumes} annotation, or {@code null}
     * @param includeRequestBody whether to include a request body in the result
     * @param jsonViewAnnotation optional {@link JsonView} annotation
     * @param chain              the remaining extension chain to delegate to when not handled
     * @return the resolved parameters (expanded or delegated)
     */
    @Override
    public ResolvedParameter extractParameters(
            List<Annotation> annotations,
            Type type,
            Set<Type> typesToSkip,
            Components components,
            Consumes classConsumes,
            Consumes methodConsumes,
            boolean includeRequestBody,
            JsonView jsonViewAnnotation,
            Iterator<OpenAPIExtension> chain) {
        JavaType jType = Json.mapper().constructType(type);
        if (jType == null) {
            return super.extractParameters(
                    annotations,
                    type,
                    typesToSkip,
                    components,
                    classConsumes,
                    methodConsumes,
                    includeRequestBody,
                    jsonViewAnnotation,
                    chain);
        }
        Class<?> rawClass = jType.getRawClass();
        if (!hasRequestParamsAnnotation(rawClass)) {
            return super.extractParameters(
                    annotations,
                    type,
                    typesToSkip,
                    components,
                    classConsumes,
                    methodConsumes,
                    includeRequestBody,
                    jsonViewAnnotation,
                    chain);
        }

        ResolvedParameter result = new ResolvedParameter();
        result.parameters = new ArrayList<>();

        if (rawClass.isRecord()) {
            for (RecordComponent component : rawClass.getRecordComponents()) {
                // JAX-RS annotations declare @Target(FIELD, METHOD, PARAMETER), not RECORD_COMPONENT,
                // so the compiler propagates them to the generated accessor method. Pass the accessor
                // as the annotation source so lookups find the correct annotations.
                buildParameter(component.getAccessor(), component.getType(), components)
                        .ifPresent(result.parameters::add);
            }
        } else {
            for (Field field : rawClass.getDeclaredFields()) {
                buildParameter(field, field.getType(), components).ifPresent(result.parameters::add);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the given class carries the {@code @RequestParams} annotation.
     * Checks by annotation type name to avoid a compile dependency on {@code rest-core}.
     *
     * @param cls the class to inspect
     * @return {@code true} if {@code @RequestParams} is present
     */
    private boolean hasRequestParamsAnnotation(Class<?> cls) {
        return Arrays.stream(cls.getAnnotations())
                .anyMatch(a -> REQUEST_PARAMS_CLASS.equals(a.annotationType().getName()));
    }

    /**
     * Attempts to build a Swagger {@link Parameter} from the JAX-RS annotations on the given
     * annotated element (record component or field).
     *
     * @param element    the annotated element to inspect
     * @param memberType the Java type of the element
     * @param components the OpenAPI {@link Components} object for schema registration
     * @return an {@link Optional} containing the parameter, or empty if no JAX-RS param annotation
     *     is present
     */
    private Optional<Parameter> buildParameter(AnnotatedElement element, Class<?> memberType, Components components) {
        Parameter param = null;

        QueryParam qp = element.getAnnotation(QueryParam.class);
        if (qp != null) {
            param = new QueryParameter().name(qp.value());
        }
        if (param == null) {
            PathParam pp = element.getAnnotation(PathParam.class);
            if (pp != null) {
                param = new PathParameter().name(pp.value());
                param.setRequired(true);
            }
        }
        if (param == null) {
            HeaderParam hp = element.getAnnotation(HeaderParam.class);
            if (hp != null) {
                param = new HeaderParameter().name(hp.value());
            }
        }
        if (param == null) {
            CookieParam cp = element.getAnnotation(CookieParam.class);
            if (cp != null) {
                param = new CookieParameter().name(cp.value());
            }
        }

        if (param == null) {
            return Optional.empty();
        }

        boolean nullable = Arrays.stream(element.getAnnotations())
                .anyMatch(a -> NULLABLE_CLASS.equals(a.annotationType().getName()));

        Schema<?> schema = resolveSchema(memberType, components);
        if (nullable) {
            schema.setNullable(true);
            param.setRequired(false);
        } else if (param.getRequired() == null) {
            param.setRequired(true);
        }

        DefaultValue dv = element.getAnnotation(DefaultValue.class);
        if (dv != null) {
            schema.setDefault(dv.value());
            param.setRequired(false);
        }

        param.setSchema(schema);
        return Optional.of(param);
    }

    /**
     * Resolves a Swagger {@link Schema} for the given Java type. Uses {@link PrimitiveType} for
     * primitive/well-known types; for complex types, registers the schema in
     * {@code components/schemas} and returns a {@code $ref} to avoid inlining duplicates.
     *
     * @param type       the Java type to resolve
     * @param components the OpenAPI {@link Components} object for schema registration
     * @return a schema for the type (inlined for primitives, {@code $ref} for complex types)
     */
    private Schema<?> resolveSchema(Class<?> type, Components components) {
        PrimitiveType primitiveType = PrimitiveType.fromType(type);
        if (primitiveType != null) {
            return primitiveType.createProperty();
        }
        // For complex types, register in components/schemas and return a $ref to avoid duplication.
        var resolved = ModelConverters.getInstance().readAllAsResolvedSchema(type);
        if (resolved == null || resolved.schema == null) {
            return new Schema<>();
        }
        if (resolved.referencedSchemas != null && !resolved.referencedSchemas.isEmpty()) {
            // Register all referenced schemas (the type itself and any nested complex types).
            resolved.referencedSchemas.forEach((name, schema) -> {
                var existingSchemas = components.getSchemas();
                if (existingSchemas == null || !existingSchemas.containsKey(name)) {
                    components.addSchemas(name, schema);
                }
            });
            // Swagger Core uses the simple class name as the schema name by default.
            // @Schema(name=...) can override this, but @RequestParams member types are
            // unlikely to use that annotation. The simple name is the correct $ref target.
            return new Schema<>().$ref("#/components/schemas/" + type.getSimpleName());
        }
        return resolved.schema;
    }
}
