// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.security.AccessPolicyAnnotationResolver;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Verifies the compile-time rules {@link ServiceContractProcessor} applies to typed access-policy
 * declarations on service contracts.
 *
 * <p>Every rejection case first compiles the same sources with a processor that validates nothing, so
 * a failure of the real processor cannot be caused by an invalid fixture. Rejections are asserted on
 * the failed compilation plus an error diagnostic that names the offending declaration (the policy
 * type or the policy annotation). Tests whose name starts with {@code characterization} pin behavior
 * that must not change.
 */
class ServiceContractProcessorPolicyTest {

    private static final String POLICY_ANNOTATION = "RequiresPolicy";
    private static final String ADMIN_POLICY = "AdminPolicy";
    private static final String OTHER_POLICY = "OtherPolicy";

    // --- Source fixtures ---

    private static JavaFileObject policy(String name, String annotations) {
        return SourceFiles.inline(
                "com.example." + name,
                "package com.example;\n"
                        + annotations
                        + "\npublic interface "
                        + name
                        + " extends dev.vertique.security.authz.AccessPolicy {}\n");
    }

    private static JavaFileObject adminPolicy() {
        return policy(ADMIN_POLICY, "@jakarta.annotation.security.RolesAllowed(\"admin\")");
    }

    private static JavaFileObject otherPolicy() {
        return policy(OTHER_POLICY, "@dev.vertique.security.authz.Authorized");
    }

    /** A contract with one operation; the supplied text is placed on the type and on the method. */
    private static JavaFileObject contract(String typeAnnotations, String methodAnnotations) {
        return SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                %s
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    %s
                    @ServiceOperation("get-user")
                    Future<String> getUser(String userId);
                }
                """.formatted(typeAnnotations, methodAnnotations));
    }

    private static JavaFileObject directImpl(String typeAnnotations, String methodAnnotations) {
        return SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                %s
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    %s
                    @Override public Future<String> getUser(String userId) { return Future.succeededFuture(userId); }
                }
                """.formatted(typeAnnotations, methodAnnotations));
    }

    private static JavaFileObject handlerImpl(String typeAnnotations, String methodAnnotations) {
        return SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                %s
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    %s
                    public Future<String> getUser(String userId) { return Future.succeededFuture(userId); }
                }
                """.formatted(typeAnnotations, methodAnnotations));
    }

    private static JavaFileObject parentWithPolicies(String typeAnnotations, String methodAnnotations) {
        return SourceFiles.inline("com.example.UserServiceParent", """
                package com.example;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                %s
                public interface UserServiceParent {
                    %s
                    @ServiceOperation("get-user")
                    Future<String> getUser(String userId);
                }
                """.formatted(typeAnnotations, methodAnnotations));
    }

    private static JavaFileObject childOfParent(String typeAnnotations, String methodAnnotations) {
        return SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                %s
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService extends UserServiceParent {
                    %s
                    @Override
                    @ServiceOperation("get-user")
                    Future<String> getUser(String userId);
                }
                """.formatted(typeAnnotations, methodAnnotations));
    }

    private static JavaFileObject[] sources(JavaFileObject... domain) {
        JavaFileObject[] all = new JavaFileObject[FRAMEWORK_SOURCES.length + domain.length];
        System.arraycopy(FRAMEWORK_SOURCES, 0, all, 0, FRAMEWORK_SOURCES.length);
        System.arraycopy(domain, 0, all, FRAMEWORK_SOURCES.length, domain.length);
        return all;
    }

    // --- Assertion helpers ---

    /** A processor that validates and generates nothing; proves a fixture is valid Java on its own. */
    private static final class ValidatesNothing extends AbstractProcessor {
        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return false;
        }
    }

    /**
     * Asserts the sources are valid Java on their own, then that the service processor rejects them
     * with an error diagnostic naming at least one of the given fragments.
     */
    private static void assertRejected(JavaFileObject[] sources, String... namingAnyOf) {
        ProcessorTestHarness.run(new ValidatesNothing(), sources).assertSuccess();

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertFailed();
        List<String> errors = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .toList();
        assertTrue(
                errors.stream().anyMatch(message -> {
                    for (String fragment : namingAnyOf) {
                        if (message.contains(fragment)) {
                            return true;
                        }
                    }
                    return false;
                }),
                "an error diagnostic must name the offending declaration " + List.of(namingAnyOf) + " but was "
                        + errors);
    }

    private static ProcessorTestHarness.Result assertAccepted(JavaFileObject[] sources) {
        return ProcessorTestHarness.run(new ServiceContractProcessor(), sources).assertSuccess();
    }

    // --- Rejections: references ---

    @Nested
    @DisplayName("conflicting and mixed references")
    class ConflictingAndMixedReferences {

        @Test
        @DisplayName("distinct type-level references on a parent and a child contract are rejected")
        void distinctTypeReferences_parentAndChild_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            otherPolicy(),
                            parentWithPolicies("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", ""),
                            childOfParent("@dev.vertique.security.authz.RequiresPolicy(OtherPolicy.class)", ""),
                            directImpl("", "")),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("distinct method-level references on a parent and a child declaration are rejected")
        void distinctMethodReferences_parentAndChild_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            otherPolicy(),
                            parentWithPolicies("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)"),
                            childOfParent("", "@dev.vertique.security.authz.RequiresPolicy(OtherPolicy.class)"),
                            directImpl("", "")),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a policy reference mixed with an inline role declaration on one method is rejected")
        void policyMixedWithInlineRole_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            contract(
                                    "",
                                    "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class) "
                                            + "@jakarta.annotation.security.RolesAllowed(\"admin\")"),
                            directImpl("", "")),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a type-level policy reference mixed with an inline action on a method is rejected")
        void typePolicyMixedWithInlineAction_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            contract(
                                    "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)",
                                    "@dev.vertique.security.authz.RequiresAction(\"svc.exec.run\")"),
                            directImpl("", "")),
                    POLICY_ANNOTATION);
        }
    }

    // --- Rejections: malformed policies ---

    @Nested
    @DisplayName("malformed policies")
    class MalformedPolicies {

        private void assertPolicyRejected(JavaFileObject malformed, String policyName) {
            assertRejected(
                    sources(
                            malformed,
                            contract("", "@dev.vertique.security.authz.RequiresPolicy(" + policyName + ".class)"),
                            directImpl("", "")),
                    policyName,
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a policy that is not public is rejected")
        void nonPublicPolicy_rejected() {
            assertPolicyRejected(SourceFiles.inline("com.example.HiddenPolicy", """
                            package com.example;
                            @jakarta.annotation.security.RolesAllowed("admin")
                            interface HiddenPolicy extends dev.vertique.security.authz.AccessPolicy {}
                            """), "HiddenPolicy");
        }

        @Test
        @DisplayName("a policy that is a class rather than an interface is rejected")
        void classPolicy_rejected() {
            assertPolicyRejected(SourceFiles.inline("com.example.ClassPolicy", """
                            package com.example;
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public abstract class ClassPolicy implements dev.vertique.security.authz.AccessPolicy {}
                            """), "ClassPolicy");
        }

        @Test
        @DisplayName("a policy that extends another type besides the marker is rejected")
        void policyExtendingOtherTypes_rejected() {
            assertPolicyRejected(SourceFiles.inline("com.example.WideningPolicy", """
                            package com.example;
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface WideningPolicy
                                    extends dev.vertique.security.authz.AccessPolicy, java.io.Serializable {}
                            """), "WideningPolicy");
        }

        @Test
        @DisplayName("a policy with an empty role list is rejected")
        void emptyRoles_rejected() {
            assertPolicyRejected(
                    policy("EmptyRolesPolicy", "@jakarta.annotation.security.RolesAllowed({})"), "EmptyRolesPolicy");
        }

        @Test
        @DisplayName("a policy with a blank role is rejected")
        void blankRole_rejected() {
            assertPolicyRejected(
                    policy("BlankRolePolicy", "@jakarta.annotation.security.RolesAllowed(\" \")"), "BlankRolePolicy");
        }

        @Test
        @DisplayName("a policy with a blank scope is rejected")
        void blankScope_rejected() {
            assertPolicyRejected(
                    policy("BlankScopePolicy", "@dev.vertique.security.authz.Authorized(scopes = {\" \"})"),
                    "BlankScopePolicy");
        }

        @Test
        @DisplayName("a policy with a malformed action is rejected")
        void malformedAction_rejected() {
            assertPolicyRejected(
                    policy("BadActionPolicy", "@dev.vertique.security.authz.RequiresAction(\"NOT_AN_ACTION\")"),
                    "BadActionPolicy");
        }

        @Test
        @DisplayName("a policy that declares no requirement is rejected")
        void emptyPolicy_rejected() {
            assertPolicyRejected(policy("EmptyPolicy", ""), "EmptyPolicy");
        }
    }

    // --- Rejections: placement ---

    @Nested
    @DisplayName("placement that would be silently ignored")
    class IgnoredPlacement {

        @Test
        @DisplayName("a policy reference on a direct implementation class is rejected")
        void policyOnDirectImplementationClass_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            contract("", ""),
                            directImpl("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", "")),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a policy reference on a direct implementation method is rejected")
        void policyOnDirectImplementationMethod_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            contract("", ""),
                            directImpl("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)")),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a policy reference on a handler class is rejected")
        void policyOnHandlerClass_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            contract("", ""),
                            handlerImpl("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", "")),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a policy reference on a handler method is rejected")
        void policyOnHandlerMethod_rejected() {
            assertRejected(
                    sources(
                            adminPolicy(),
                            contract("", ""),
                            handlerImpl("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)")),
                    POLICY_ANNOTATION);
        }
    }

    // --- Acceptance ---

    @Nested
    @DisplayName("accepted declarations")
    class AcceptedDeclarations {

        @Test
        @DisplayName("a typed method policy compiles and the contributor collects from the actual contract")
        void typedMethodPolicy_compilesAndCollectsFromContract() {
            ProcessorTestHarness.Result result = assertAccepted(sources(
                    adminPolicy(),
                    contract("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)"),
                    directImpl("", "")));

            result.assertGeneratedSourceContains(
                    "com.example.UserService_ContractContributor", "collectMethodAnnotations");
        }

        @Test
        @DisplayName("a typed policy on a handler-pattern contract compiles and collects from the contract")
        void typedPolicy_handlerPattern_compilesAndCollectsFromContract() {
            ProcessorTestHarness.Result result = assertAccepted(sources(
                    adminPolicy(),
                    contract("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", ""),
                    handlerImpl("", "")));

            result.assertGeneratedSourceContains(
                    "com.example.UserService_ContractContributor", "collectMethodAnnotations");
        }

        @Test
        @DisplayName("a method policy replaces a different type policy")
        void methodPolicy_replacesTypePolicy() {
            assertAccepted(sources(
                    adminPolicy(),
                    otherPolicy(),
                    contract(
                            "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)",
                            "@dev.vertique.security.authz.RequiresPolicy(OtherPolicy.class)"),
                    directImpl("", "")));
        }

        @Test
        @DisplayName("identical references on a parent and a child coalesce")
        void identicalReferences_parentAndChild_coalesce() {
            assertAll(
                    "identical type and method references",
                    () -> assertAccepted(sources(
                            adminPolicy(),
                            parentWithPolicies("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", ""),
                            childOfParent("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", ""),
                            directImpl("", ""))),
                    () -> assertAccepted(sources(
                            adminPolicy(),
                            parentWithPolicies("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)"),
                            childOfParent("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)"),
                            directImpl("", ""))));
        }

        @Test
        @DisplayName("every supported direct requirement is accepted in a policy")
        void everySupportedRequirement_isAcceptedInAPolicy() {
            List<Executable> checks = new ArrayList<>();
            Map<String, String> policies = Map.of(
                    "RolesPolicy", "@jakarta.annotation.security.RolesAllowed({\"admin\", \"ops\"})",
                    "ScopesPolicy", "@dev.vertique.security.authz.Authorized(scopes = {\"a.read\"}, matchAll = false)",
                    "AuthenticatedPolicy", "@dev.vertique.security.authz.Authorized",
                    "PublicPolicy", "@jakarta.annotation.security.PermitAll",
                    "DenyPolicy", "@jakarta.annotation.security.DenyAll",
                    "ActionPolicy", "@dev.vertique.security.authz.RequiresAction(\"svc.exec.run\")",
                    "CombinedPolicy",
                            "@jakarta.annotation.security.RolesAllowed(\"admin\") "
                                    + "@dev.vertique.security.authz.RequiresAction(\"svc.exec.run\")");
            policies.forEach((name, annotations) -> checks.add(() -> assertAccepted(sources(
                    policy(name, annotations),
                    contract("", "@dev.vertique.security.authz.RequiresPolicy(" + name + ".class)"),
                    directImpl("", "")))));
            assertAll("supported policy shapes", checks);
        }
    }

    // --- Characterization ---

    @Nested
    @DisplayName("characterization of inline-only contracts")
    class InlineOnlyCharacterization {

        @Test
        @DisplayName(
                "characterization: inline security annotations on a contract compile and keep the legacy collection")
        void characterization_inlineOnlyDeclarations_compileWithLegacyCollection() {
            ProcessorTestHarness.Result result = assertAccepted(sources(
                    contract(
                            "@jakarta.annotation.security.RolesAllowed(\"admin\")",
                            "@dev.vertique.security.authz.RequiresAction(\"svc.exec.run\")"),
                    directImpl("", "")));

            result.assertGeneratedSourceContains(
                    "com.example.UserService_ContractContributor", "resolveMethodAnnotations");
        }
    }

    // --- Implementations that are also REST resources ---

    private static final JavaFileObject PATH_SOURCE = SourceFiles.inline("jakarta.ws.rs.Path", """
            package jakarta.ws.rs;
            import java.lang.annotation.*;
            @Target({ElementType.TYPE, ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME)
            public @interface Path { String value(); }
            """);

    private static final JavaFileObject GET_SOURCE = SourceFiles.inline("jakarta.ws.rs.GET", """
            package jakarta.ws.rs;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
            public @interface GET {}
            """);

    private static final String ADMIN_REFERENCE = "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)";

    /** A direct implementation with the given class annotations and extra members. */
    private static JavaFileObject directImplWithMembers(String typeAnnotations, String members) {
        return SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                %s
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public Future<String> getUser(String userId) { return Future.succeededFuture(userId); }
                    %s
                }
                """.formatted(typeAnnotations, members));
    }

    /** A handler with the given class annotations and extra members. */
    private static JavaFileObject handlerImplWithMembers(String typeAnnotations, String members) {
        return SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                %s
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    public Future<String> getUser(String userId) { return Future.succeededFuture(userId); }
                    %s
                }
                """.formatted(typeAnnotations, members));
    }

    private static final String REST_ROUTE_WITH_POLICY = "@jakarta.ws.rs.GET @jakarta.ws.rs.Path(\"/route\") "
            + ADMIN_REFERENCE + " public String route() { return \"\"; }";

    @Nested
    @DisplayName("implementations that are also REST resources")
    class RestResourceImplementations {

        @Test
        @DisplayName("a class-level policy on a direct implementation annotated with a path is accepted")
        void classPolicyOnPathAnnotatedDirectImplementation_accepted() {
            assertAccepted(sources(
                    PATH_SOURCE,
                    GET_SOURCE,
                    adminPolicy(),
                    contract("", ""),
                    directImplWithMembers("@jakarta.ws.rs.Path(\"/users\") " + ADMIN_REFERENCE, "")));
        }

        @Test
        @DisplayName("a class-level policy on a handler annotated with a path is accepted")
        void classPolicyOnPathAnnotatedHandler_accepted() {
            assertAccepted(sources(
                    PATH_SOURCE,
                    GET_SOURCE,
                    adminPolicy(),
                    contract("", ""),
                    handlerImplWithMembers("@jakarta.ws.rs.Path(\"/users\") " + ADMIN_REFERENCE, "")));
        }

        @Test
        @DisplayName("a policy on a route method that is not a contract operation is accepted")
        void policyOnRouteMethodThatIsNotAnOperation_accepted() {
            assertAll(
                    "direct implementation and handler",
                    () -> assertAccepted(sources(
                            PATH_SOURCE,
                            GET_SOURCE,
                            adminPolicy(),
                            contract("", ""),
                            directImplWithMembers("@jakarta.ws.rs.Path(\"/users\")", REST_ROUTE_WITH_POLICY))),
                    () -> assertAccepted(sources(
                            PATH_SOURCE,
                            GET_SOURCE,
                            adminPolicy(),
                            contract("", ""),
                            handlerImplWithMembers("@jakarta.ws.rs.Path(\"/users\")", REST_ROUTE_WITH_POLICY))));
        }

        @Test
        @DisplayName("a policy on the method that implements a contract operation is still rejected")
        void policyOnOperationMethodOfPathAnnotatedImplementation_rejected() {
            assertRejected(
                    sources(
                            PATH_SOURCE,
                            GET_SOURCE,
                            adminPolicy(),
                            contract("", ""),
                            directImpl("@jakarta.ws.rs.Path(\"/users\")", ADMIN_REFERENCE)),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a policy on a handler method that implements a contract operation is still rejected")
        void policyOnHandlerMethodOfPathAnnotatedHandler_rejected() {
            assertRejected(
                    sources(
                            PATH_SOURCE,
                            GET_SOURCE,
                            adminPolicy(),
                            contract("", ""),
                            handlerImpl("@jakarta.ws.rs.Path(\"/users\")", ADMIN_REFERENCE)),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a class-level policy on an implementation without a path is still rejected")
        void classPolicyWithoutPath_stillRejected() {
            assertRejected(
                    sources(
                            PATH_SOURCE,
                            GET_SOURCE,
                            adminPolicy(),
                            contract("", ""),
                            directImplWithMembers(ADMIN_REFERENCE, REST_ROUTE_WITH_POLICY)),
                    POLICY_ANNOTATION);
        }
    }

    // --- Handler base classes ---

    @Nested
    @DisplayName("handlers that extend a base class")
    class BaseHandlers {

        private JavaFileObject baseHandler(String methodAnnotations) {
            return SourceFiles.inline("com.example.BaseUserHandler", """
                    package com.example;
                    import io.vertx.core.Future;
                    public abstract class BaseUserHandler {
                        %s
                        public abstract Future<String> getUser(String userId);
                    }
                    """.formatted(methodAnnotations));
        }

        private JavaFileObject derivedHandler() {
            return SourceFiles.inline("com.example.UserServiceHandler", """
                    package com.example;
                    import dev.vertique.services.ServiceHandler;
                    import io.vertx.core.Future;
                    import jakarta.inject.Inject;
                    public class UserServiceHandler extends BaseUserHandler implements ServiceHandler<UserService> {
                        @Inject UserServiceHandler() {}
                        @Override public Future<String> getUser(String userId) { return Future.succeededFuture(userId); }
                    }
                    """);
        }

        @Test
        @DisplayName("a policy on the overridden method of an abstract base handler is rejected")
        void policyOnBaseHandlerMethod_rejected() {
            assertRejected(
                    sources(adminPolicy(), contract("", ""), baseHandler(ADMIN_REFERENCE), derivedHandler()),
                    POLICY_ANNOTATION);
        }

        @Test
        @DisplayName("a base handler without a policy is accepted")
        void baseHandlerWithoutPolicy_accepted() {
            assertAccepted(sources(adminPolicy(), contract("", ""), baseHandler(""), derivedHandler()));
        }
    }

    // --- Agreement between the compile-time check and the runtime-equivalent collection ---

    /**
     * Runs the compiler-mirror collection the generated contributor performs at runtime over every
     * operation of the fixture contract and remembers each rejection it raises.
     */
    private static final class CollectionProbe extends AbstractProcessor {
        private final List<String> rejections = new ArrayList<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            TypeElement contract = processingEnv.getElementUtils().getTypeElement("com.example.UserService");
            if (contract == null || roundEnv.processingOver()) {
                return false;
            }
            AccessPolicyAnnotationResolver resolver =
                    new AccessPolicyAnnotationResolver(processingEnv.getTypeUtils(), processingEnv.getElementUtils());
            for (ExecutableElement method : ElementFilter.methodsIn(contract.getEnclosedElements())) {
                try {
                    resolver.collectMethodAnnotations(contract, method, List.of());
                } catch (IllegalArgumentException e) {
                    rejections.add(e.getMessage());
                }
            }
            return false;
        }
    }

    @Nested
    @DisplayName("agreement with the generated collection")
    class ValidatorAndCollectionAgree {

        private void assertAlsoRejectedByCollection(JavaFileObject[] sources) {
            CollectionProbe probe = new CollectionProbe();
            ProcessorTestHarness.run(probe, sources).assertSuccess();
            assertTrue(
                    !probe.rejections.isEmpty(),
                    "a contract the validator rejects must also be rejected by the collection the contributor runs");
        }

        @Test
        @DisplayName("every contract the validator rejects is also rejected by collectMethodAnnotations")
        void everyRejectedContract_isAlsoRejectedByCollection() {
            Map<String, JavaFileObject[]> rejected = new java.util.LinkedHashMap<>();
            rejected.put(
                    "distinct type references",
                    sources(
                            adminPolicy(),
                            otherPolicy(),
                            parentWithPolicies("@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)", ""),
                            childOfParent("@dev.vertique.security.authz.RequiresPolicy(OtherPolicy.class)", ""),
                            directImpl("", "")));
            rejected.put(
                    "distinct method references",
                    sources(
                            adminPolicy(),
                            otherPolicy(),
                            parentWithPolicies("", "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)"),
                            childOfParent("", "@dev.vertique.security.authz.RequiresPolicy(OtherPolicy.class)"),
                            directImpl("", "")));
            rejected.put(
                    "policy mixed with an inline role",
                    sources(
                            adminPolicy(),
                            contract(
                                    "",
                                    "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class) "
                                            + "@jakarta.annotation.security.RolesAllowed(\"admin\")"),
                            directImpl("", "")));
            rejected.put(
                    "type policy mixed with an inline action",
                    sources(
                            adminPolicy(),
                            contract(
                                    "@dev.vertique.security.authz.RequiresPolicy(AdminPolicy.class)",
                                    "@dev.vertique.security.authz.RequiresAction(\"svc.exec.run\")"),
                            directImpl("", "")));
            Map<String, String> malformed = Map.of(
                    "EmptyRolesPolicy", "@jakarta.annotation.security.RolesAllowed({})",
                    "BlankRolePolicy", "@jakarta.annotation.security.RolesAllowed(\" \")",
                    "BlankScopePolicy", "@dev.vertique.security.authz.Authorized(scopes = {\" \"})",
                    "BadActionPolicy", "@dev.vertique.security.authz.RequiresAction(\"NOT_AN_ACTION\")",
                    "EmptyPolicy", "");
            malformed.forEach((name, annotations) -> rejected.put(
                    name,
                    sources(
                            policy(name, annotations),
                            contract("", "@dev.vertique.security.authz.RequiresPolicy(" + name + ".class)"),
                            directImpl("", ""))));

            List<Executable> checks = new ArrayList<>();
            rejected.forEach((name, sources) -> checks.add(() -> {
                assertRejected(sources, POLICY_ANNOTATION, name);
                assertAlsoRejectedByCollection(sources);
            }));
            assertAll("rejected contracts", checks);
        }
    }
}
