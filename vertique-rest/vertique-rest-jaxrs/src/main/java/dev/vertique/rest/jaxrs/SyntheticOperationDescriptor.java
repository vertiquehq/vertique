// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.publication.SyntheticOperation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link RestOperationDescriptor} {@link SyntheticOperationInstaller} hands to validators and
 * contributors for one installed synthetic operation.
 *
 * <p>It shows a contributor what an equally annotated resource method shows: {@link
 * #methodAnnotations()} and {@link #findAnnotation} report the synthetic {@code @SecurityRequirement}
 * and {@code @RolesAllowed} (or {@code @Authorized}) instances built by {@link
 * #securityAnnotations}, the very instances the installer resolves the security policy and
 * requirement sets from. It has no class annotations and no media types, and its route template is
 * the literal path the operation was installed at.
 *
 * <p>{@link #applicationName()} reports the name of the application whose document the operation
 * serves; the installer takes it from the synthetic operation.
 */
final class SyntheticOperationDescriptor implements RestOperationDescriptor {

    private final String operationId;
    private final String httpMethod;
    private final String routeTemplate;
    private final List<Annotation> methodAnnotations;
    private final SecurityPolicy securityPolicy;
    private final List<SecurityRequirementSet> securityRequirementSets;
    private final String applicationName;

    /**
     * Creates the descriptor for one installed synthetic operation.
     *
     * @param operationId             the synthetic operation id
     * @param httpMethod              the operation's primary HTTP method
     * @param routeTemplate           the literal path given to {@code install}
     * @param methodAnnotations       the synthetic security annotations, from {@link
     *                                #securityAnnotations}
     * @param securityPolicy          the policy resolved from {@code methodAnnotations}
     * @param securityRequirementSets the requirement sets resolved from {@code methodAnnotations}
     * @param applicationName         the documented application's name
     */
    SyntheticOperationDescriptor(
            String operationId,
            String httpMethod,
            String routeTemplate,
            List<Annotation> methodAnnotations,
            SecurityPolicy securityPolicy,
            List<SecurityRequirementSet> securityRequirementSets,
            String applicationName) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.httpMethod = Objects.requireNonNull(httpMethod, "httpMethod");
        this.routeTemplate = Objects.requireNonNull(routeTemplate, "routeTemplate");
        this.methodAnnotations = List.copyOf(methodAnnotations);
        this.securityPolicy = Objects.requireNonNull(securityPolicy, "securityPolicy");
        this.securityRequirementSets = List.copyOf(securityRequirementSets);
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
    }

    /**
     * Builds the method annotations a resource method equally secured as {@code operation} declares:
     * {@code @SecurityRequirement(name = schemeName)}, then {@code @RolesAllowed(rolesAllowed)} for a
     * role-restricted operation or {@code @Authorized} (its defaults: no scopes, match all) for an
     * authenticated-only one.
     *
     * @param operation the synthetic operation
     * @return the two synthetic annotation instances, in that order
     */
    static List<Annotation> securityAnnotations(SyntheticOperation operation) {
        Annotation requirement = new SecurityRequirementLiteral(operation.schemeName());
        Annotation access = operation
                .rolesAllowed()
                .<Annotation>map(roles -> new RolesAllowedLiteral(roles.toArray(String[]::new)))
                .orElseGet(AuthorizedLiteral::new);
        return List.of(requirement, access);
    }

    @Override
    public String operationId() {
        return operationId;
    }

    @Override
    public String applicationName() {
        return applicationName;
    }

    @Override
    public String httpMethod() {
        return httpMethod;
    }

    @Override
    public String routeTemplate() {
        return routeTemplate;
    }

    @Override
    public List<String> consumes() {
        return List.of();
    }

    @Override
    public List<String> produces() {
        return List.of();
    }

    @Override
    public SecurityPolicy securityPolicy() {
        return securityPolicy;
    }

    @Override
    public List<SecurityRequirementSet> securityRequirementSets() {
        return securityRequirementSets;
    }

    @Override
    public List<Annotation> methodAnnotations() {
        return methodAnnotations;
    }

    @Override
    public List<Annotation> classAnnotations() {
        return List.of();
    }

    @Override
    public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
        for (Annotation annotation : methodAnnotations) {
            if (type.isInstance(annotation)) {
                return Optional.of(type.cast(annotation));
            }
        }
        return Optional.empty();
    }

    /**
     * Hash contribution of one annotation member, as {@link Annotation#hashCode()} specifies.
     *
     * @param memberName  the member's name
     * @param memberValue the member's hash code, computed for its type as {@link Annotation#hashCode()}
     *                    specifies
     * @return the member's contribution to the annotation's hash code
     */
    private static int memberHash(String memberName, int memberValue) {
        return (127 * memberName.hashCode()) ^ memberValue;
    }

    /**
     * Renders a string array member the way the JDK renders an annotation's array member.
     *
     * @param values the member's values
     * @return the rendered member value
     */
    private static String render(String[] values) {
        StringBuilder rendered = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            rendered.append('"').append(values[i]).append('"');
        }
        return rendered.append('}').toString();
    }

    /**
     * A runtime {@code @SecurityRequirement(name = schemeName)} instance: no scopes and no {@code
     * combine} group, as a resource method declaring it shows. Honors the {@link Annotation}
     * contract, so it equals an equally valued compiler-emitted instance in both directions.
     */
    private static final class SecurityRequirementLiteral implements SecurityRequirement {

        private final String name;

        SecurityRequirementLiteral(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String[] scopes() {
            return new String[0];
        }

        @Override
        public SecurityRequirementEntry[] combine() {
            return new SecurityRequirementEntry[0];
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return SecurityRequirement.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SecurityRequirement that
                    && name.equals(that.name())
                    && Arrays.equals(scopes(), that.scopes())
                    && Arrays.equals(combine(), that.combine());
        }

        @Override
        public int hashCode() {
            return memberHash("name", name.hashCode())
                    + memberHash("scopes", Arrays.hashCode(scopes()))
                    + memberHash("combine", Arrays.hashCode(combine()));
        }

        @Override
        public String toString() {
            return "@" + SecurityRequirement.class.getName() + "(name=\"" + name + "\", scopes={}, combine={})";
        }
    }

    /**
     * A runtime {@code @RolesAllowed(roles)} instance. Honors the {@link Annotation} contract, so it
     * equals an equally valued compiler-emitted instance in both directions.
     */
    private static final class RolesAllowedLiteral implements RolesAllowed {

        private final String[] roles;

        RolesAllowedLiteral(String[] roles) {
            this.roles = roles.clone();
        }

        @Override
        public String[] value() {
            return roles.clone();
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return RolesAllowed.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RolesAllowed that && Arrays.equals(roles, that.value());
        }

        @Override
        public int hashCode() {
            return memberHash("value", Arrays.hashCode(roles));
        }

        @Override
        public String toString() {
            return "@" + RolesAllowed.class.getName() + "(" + render(roles) + ")";
        }
    }

    /**
     * A runtime {@code @Authorized} instance with its defaults: no scopes, match all. Honors the
     * {@link Annotation} contract, so it equals a compiler-emitted {@code @Authorized} in both
     * directions.
     */
    private static final class AuthorizedLiteral implements Authorized {

        @Override
        public String[] scopes() {
            return new String[0];
        }

        @Override
        public boolean matchAll() {
            return true;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Authorized.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Authorized that
                    && Arrays.equals(scopes(), that.scopes())
                    && matchAll() == that.matchAll();
        }

        @Override
        public int hashCode() {
            return memberHash("scopes", Arrays.hashCode(scopes())) + memberHash("matchAll", Boolean.hashCode(true));
        }

        @Override
        public String toString() {
            return "@" + Authorized.class.getName() + "(scopes={}, matchAll=true)";
        }
    }
}
