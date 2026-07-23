// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceRegistrationException;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runtime selection tests for the contract-group contributor emitter (CG-011 W4).
 *
 * <p>Compiles a contract + impls fixture against the real framework classpath, loads the generated
 * {@code _ContractContributor} via the harness classloader, instantiates it with counting
 * {@link jakarta.inject.Provider} wrappers, and asserts the correct selection logic per scenario.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Single-impl conditional group — condition does NOT match → empty list (FR-CG011-011).</li>
 *   <li>Multi-impl group — exactly one conditional matches → that impl's entry returned.</li>
 *   <li>Multi-impl group — two conditionals match simultaneously → {@link ServiceRegistrationException}.</li>
 *   <li>Multi-impl group — no conditional matches, no default → {@link ServiceRegistrationException}.</li>
 *   <li>Lazy instantiation — only selected impl's Provider.get() is called.</li>
 * </ul>
 */
class ServiceContractProcessorMultiImplRuntimeTest {

    // --- Annotation stubs (SOURCE-retention; must be in-memory for the test compiler) ---

    private static final JavaFileObject CONDITIONAL_ON_PROPERTY_SOURCE =
            SourceFiles.inline("dev.vertique.codegen.ConditionalOnProperty", """
                    package dev.vertique.codegen;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE)
                    @Repeatable(ConditionalOnProperties.class) @Documented
                    public @interface ConditionalOnProperty {
                        String name();
                        String havingValue() default "true";
                        boolean matchIfMissing() default false;
                    }
                    """);

    private static final JavaFileObject CONDITIONAL_ON_PROPERTIES_SOURCE =
            SourceFiles.inline("dev.vertique.codegen.ConditionalOnProperties", """
                    package dev.vertique.codegen;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE) @Documented
                    public @interface ConditionalOnProperties {
                        ConditionalOnProperty[] value();
                    }
                    """);

    // --- Shared audit tier annotation used in all fixture contracts ---

    private static final JavaFileObject AUDIT_TIER_ANNOTATION =
            SourceFiles.inline("com.example.annotation.AuditTier", """
                    package com.example.annotation;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface AuditTier {
                        String value();
                    }
                    """);

    // --- Scenario A: single-impl conditional, condition does NOT match → empty list ---

    private static final JavaFileObject SINGLE_CONDITIONAL_CONTRACT =
            SourceFiles.inline("com.example.GreetingService", """
                    package com.example;
                    import dev.vertique.services.ServiceContract;
                    import dev.vertique.services.ServiceOperation;
                    import io.vertx.core.Future;
                    @ServiceContract(value = "greeting-service")
                    public interface GreetingService {
                        @ServiceOperation("greet")
                        Future<String> greet(String name);
                    }
                    """);

    /**
     * Conditional impl — only active when {@code featureX=true}.
     * Because no default exists in this single-impl group, a non-match must return empty.
     */
    private static final JavaFileObject SINGLE_CONDITIONAL_IMPL =
            SourceFiles.inline("com.example.GreetingServiceImpl", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "featureX")
                    public class GreetingServiceImpl implements GreetingService {
                        @Inject public GreetingServiceImpl() {}
                        @Override public Future<String> greet(String name) {
                            return Future.succeededFuture("Hello, " + name);
                        }
                    }
                    """);

    // --- Scenario B/C/D: multi-impl group (default + two conditionals) ---

    private static final JavaFileObject MULTI_CONTRACT = SourceFiles.inline("com.example.UserService", """
                    package com.example;
                    import dev.vertique.services.ServiceContract;
                    import dev.vertique.services.ServiceOperation;
                    import io.vertx.core.Future;
                    @ServiceContract(value = "user-service")
                    public interface UserService {
                        @ServiceOperation("get-user")
                        Future<String> getUser(String userId);
                    }
                    """);

    /** Unconditional default impl. */
    private static final JavaFileObject USER_SERVICE_HANDLER =
            SourceFiles.inline("com.example.UserServiceHandler", """
                    package com.example;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class UserServiceHandler implements UserService {
                        @Inject public UserServiceHandler() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("default-" + userId);
                        }
                    }
                    """);

    /** First conditional impl — active when {@code sandboxEnabled=true}. */
    private static final JavaFileObject USER_SERVICE_SANDBOX =
            SourceFiles.inline("com.example.UserServiceSandbox", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "sandboxEnabled")
                    public class UserServiceSandbox implements UserService {
                        @Inject public UserServiceSandbox() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("sandbox-" + userId);
                        }
                    }
                    """);

    /** Second conditional impl — active when {@code stagingEnabled=true}. */
    private static final JavaFileObject USER_SERVICE_STAGING =
            SourceFiles.inline("com.example.UserServiceStaging", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "stagingEnabled")
                    public class UserServiceStaging implements UserService {
                        @Inject public UserServiceStaging() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("staging-" + userId);
                        }
                    }
                    """);

    // --- Scenario E: multi-impl with only two conditionals (no default) ---

    private static final JavaFileObject PAYMENT_CONTRACT = SourceFiles.inline("com.example.PaymentService", """
                    package com.example;
                    import dev.vertique.services.ServiceContract;
                    import dev.vertique.services.ServiceOperation;
                    import io.vertx.core.Future;
                    @ServiceContract(value = "payment-service")
                    public interface PaymentService {
                        @ServiceOperation("pay")
                        Future<String> pay(String amount);
                    }
                    """);

    private static final JavaFileObject PAYMENT_SANDBOX_IMPL =
            SourceFiles.inline("com.example.PaymentSandboxImpl", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "paymentSandbox")
                    public class PaymentSandboxImpl implements PaymentService {
                        @Inject public PaymentSandboxImpl() {}
                        @Override public Future<String> pay(String amount) {
                            return Future.succeededFuture("sandbox-pay-" + amount);
                        }
                    }
                    """);

    private static final JavaFileObject PAYMENT_STAGING_IMPL =
            SourceFiles.inline("com.example.PaymentStagingImpl", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "paymentStaging")
                    public class PaymentStagingImpl implements PaymentService {
                        @Inject public PaymentStagingImpl() {}
                        @Override public Future<String> pay(String amount) {
                            return Future.succeededFuture("staging-pay-" + amount);
                        }
                    }
                    """);

    // --- Tests ---

    @Test
    @DisplayName("single-impl conditional group — condition does NOT match → empty list (FR-CG011-011)")
    void singleImplConditional_noMatch_returnsEmptyList() throws Exception {
        JavaFileObject[] sources = {
            CONDITIONAL_ON_PROPERTY_SOURCE,
            CONDITIONAL_ON_PROPERTIES_SOURCE,
            AUDIT_TIER_ANNOTATION,
            SINGLE_CONDITIONAL_CONTRACT,
            SINGLE_CONDITIONAL_IMPL
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        Class<?> implClass = result.loadGeneratedClass("com.example.GreetingServiceImpl");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.GreetingService_ContractContributor");

        // Build a counting provider — must NOT be called when condition fails
        AtomicInteger callCount = new AtomicInteger(0);
        Object implInstance = implClass.getDeclaredConstructor().newInstance();
        Object provider = buildProvider(implInstance, callCount);

        // Instantiate contributor via the Provider constructor
        Constructor<?> ctor = findProviderConstructor(contributorClass);
        ServiceContractContributor contributor = (ServiceContractContributor) ctor.newInstance(provider);

        // Config without "featureX" → condition fails → empty list (not throw)
        List<?> entries = contributor.contribute(new JsonObject());
        assertNotNull(entries);
        assertTrue(entries.isEmpty(), "Expected empty list when single-impl condition does not match");
        assertEquals(0, callCount.get(), "Provider.get() must NOT be called when condition does not match");
    }

    @Test
    @DisplayName("multi-impl group — exactly one conditional matches → that impl's entry returned")
    void multiImpl_exactlyOneConditionalMatches_returnsMatchedEntry() throws Exception {
        JavaFileObject[] sources = {
            CONDITIONAL_ON_PROPERTY_SOURCE,
            CONDITIONAL_ON_PROPERTIES_SOURCE,
            AUDIT_TIER_ANNOTATION,
            MULTI_CONTRACT,
            USER_SERVICE_HANDLER,
            USER_SERVICE_SANDBOX,
            USER_SERVICE_STAGING
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        Class<?> defaultClass = result.loadGeneratedClass("com.example.UserServiceHandler");
        Class<?> sandboxClass = result.loadGeneratedClass("com.example.UserServiceSandbox");
        Class<?> stagingClass = result.loadGeneratedClass("com.example.UserServiceStaging");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.UserService_ContractContributor");

        AtomicInteger defaultCalls = new AtomicInteger(0);
        AtomicInteger sandboxCalls = new AtomicInteger(0);
        AtomicInteger stagingCalls = new AtomicInteger(0);

        Object defaultInstance = defaultClass.getDeclaredConstructor().newInstance();
        Object sandboxInstance = sandboxClass.getDeclaredConstructor().newInstance();
        Object stagingInstance = stagingClass.getDeclaredConstructor().newInstance();

        Object defaultProvider = buildProvider(defaultInstance, defaultCalls);
        Object sandboxProvider = buildProvider(sandboxInstance, sandboxCalls);
        Object stagingProvider = buildProvider(stagingInstance, stagingCalls);

        Constructor<?> ctor = findThreeProviderConstructor(contributorClass);
        ServiceContractContributor contributor =
                (ServiceContractContributor) ctor.newInstance(defaultProvider, sandboxProvider, stagingProvider);

        // Only sandboxEnabled=true in config → sandbox should be selected
        JsonObject config = new JsonObject().put("sandboxEnabled", true);
        List<?> entries = contributor.contribute(config);
        assertNotNull(entries);
        assertEquals(1, entries.size(), "Expected exactly one ContractEntry");
        assertEquals(1, sandboxCalls.get(), "sandbox Provider.get() must be called once");
        assertEquals(0, defaultCalls.get(), "default Provider.get() must NOT be called");
        assertEquals(0, stagingCalls.get(), "staging Provider.get() must NOT be called");
    }

    @Test
    @DisplayName("multi-impl group — two conditionals match simultaneously → ServiceRegistrationException")
    void multiImpl_twoConditionalMatch_throwsException() throws Exception {
        JavaFileObject[] sources = {
            CONDITIONAL_ON_PROPERTY_SOURCE,
            CONDITIONAL_ON_PROPERTIES_SOURCE,
            AUDIT_TIER_ANNOTATION,
            MULTI_CONTRACT,
            USER_SERVICE_HANDLER,
            USER_SERVICE_SANDBOX,
            USER_SERVICE_STAGING
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        Class<?> defaultClass = result.loadGeneratedClass("com.example.UserServiceHandler");
        Class<?> sandboxClass = result.loadGeneratedClass("com.example.UserServiceSandbox");
        Class<?> stagingClass = result.loadGeneratedClass("com.example.UserServiceStaging");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.UserService_ContractContributor");

        Object defaultInstance = defaultClass.getDeclaredConstructor().newInstance();
        Object sandboxInstance = sandboxClass.getDeclaredConstructor().newInstance();
        Object stagingInstance = stagingClass.getDeclaredConstructor().newInstance();

        Object defaultProvider = buildProvider(defaultInstance, new AtomicInteger());
        Object sandboxProvider = buildProvider(sandboxInstance, new AtomicInteger());
        Object stagingProvider = buildProvider(stagingInstance, new AtomicInteger());

        Constructor<?> ctor = findThreeProviderConstructor(contributorClass);
        ServiceContractContributor contributor =
                (ServiceContractContributor) ctor.newInstance(defaultProvider, sandboxProvider, stagingProvider);

        // Both sandboxEnabled and stagingEnabled active → ambiguous → must throw
        JsonObject config = new JsonObject().put("sandboxEnabled", true).put("stagingEnabled", true);
        assertThrows(
                ServiceRegistrationException.class,
                () -> contributor.contribute(config),
                "Expected ServiceRegistrationException when two conditionals match");
    }

    @Test
    @DisplayName("multi-impl group — no conditional matches, no default → ServiceRegistrationException")
    void multiImpl_noMatchNoDefault_throwsException() throws Exception {
        JavaFileObject[] sources = {
            CONDITIONAL_ON_PROPERTY_SOURCE,
            CONDITIONAL_ON_PROPERTIES_SOURCE,
            AUDIT_TIER_ANNOTATION,
            PAYMENT_CONTRACT,
            PAYMENT_SANDBOX_IMPL,
            PAYMENT_STAGING_IMPL
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        Class<?> sandboxClass = result.loadGeneratedClass("com.example.PaymentSandboxImpl");
        Class<?> stagingClass = result.loadGeneratedClass("com.example.PaymentStagingImpl");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.PaymentService_ContractContributor");

        Object sandboxInstance = sandboxClass.getDeclaredConstructor().newInstance();
        Object stagingInstance = stagingClass.getDeclaredConstructor().newInstance();

        Object sandboxProvider = buildProvider(sandboxInstance, new AtomicInteger());
        Object stagingProvider = buildProvider(stagingInstance, new AtomicInteger());

        Constructor<?> ctor = findTwoProviderConstructor(contributorClass);
        ServiceContractContributor contributor =
                (ServiceContractContributor) ctor.newInstance(sandboxProvider, stagingProvider);

        // Empty config → neither condition matches, no default → must throw
        assertThrows(
                ServiceRegistrationException.class,
                () -> contributor.contribute(new JsonObject()),
                "Expected ServiceRegistrationException when no conditionals match and no default exists");
    }

    @Test
    @DisplayName("lazy instantiation — non-selected providers' get() is not called")
    void lazyInstantiation_onlySelectedProviderGetCalled() throws Exception {
        JavaFileObject[] sources = {
            CONDITIONAL_ON_PROPERTY_SOURCE,
            CONDITIONAL_ON_PROPERTIES_SOURCE,
            AUDIT_TIER_ANNOTATION,
            MULTI_CONTRACT,
            USER_SERVICE_HANDLER,
            USER_SERVICE_SANDBOX,
            USER_SERVICE_STAGING
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        Class<?> defaultClass = result.loadGeneratedClass("com.example.UserServiceHandler");
        Class<?> sandboxClass = result.loadGeneratedClass("com.example.UserServiceSandbox");
        Class<?> stagingClass = result.loadGeneratedClass("com.example.UserServiceStaging");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.UserService_ContractContributor");

        AtomicInteger defaultCalls = new AtomicInteger(0);
        AtomicInteger sandboxCalls = new AtomicInteger(0);
        AtomicInteger stagingCalls = new AtomicInteger(0);

        Object defaultInstance = defaultClass.getDeclaredConstructor().newInstance();
        Object sandboxInstance = sandboxClass.getDeclaredConstructor().newInstance();
        Object stagingInstance = stagingClass.getDeclaredConstructor().newInstance();

        Object defaultProvider = buildProvider(defaultInstance, defaultCalls);
        Object sandboxProvider = buildProvider(sandboxInstance, sandboxCalls);
        Object stagingProvider = buildProvider(stagingInstance, stagingCalls);

        Constructor<?> ctor = findThreeProviderConstructor(contributorClass);
        ServiceContractContributor contributor =
                (ServiceContractContributor) ctor.newInstance(defaultProvider, sandboxProvider, stagingProvider);

        // No conditions active → default selected
        contributor.contribute(new JsonObject());
        assertEquals(1, defaultCalls.get(), "default Provider.get() must be called exactly once");
        assertEquals(0, sandboxCalls.get(), "sandbox Provider.get() must NOT be called when sandbox is not selected");
        assertEquals(0, stagingCalls.get(), "staging Provider.get() must NOT be called when staging is not selected");
    }

    // --- Helpers ---

    /**
     * Builds a reflective proxy implementing {@code jakarta.inject.Provider} that delegates
     * {@code get()} to a counting wrapper around the given delegate instance.
     *
     * <p>The provider interface is loaded from the harness parent classloader (the test classpath)
     * so that the generated contributor and this test code share the same {@code Provider} class
     * identity.
     *
     * @param delegate  the object to return from {@code get()}; must not be {@code null}
     * @param callCount the counter incremented each time {@code get()} is called
     * @return a proxy implementing {@code Provider<delegate.getClass()>}
     */
    private static Object buildProvider(Object delegate, AtomicInteger callCount) {
        Class<?> providerInterface;
        try {
            providerInterface =
                    Class.forName("jakarta.inject.Provider", true, ProcessorTestHarness.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("jakarta.inject.Provider not on test classpath", e);
        }
        return java.lang.reflect.Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(), new Class<?>[] {providerInterface}, (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        callCount.incrementAndGet();
                        return delegate;
                    }
                    return null;
                });
    }

    /**
     * Finds the single-parameter constructor of a generated contributor that takes a
     * {@code Provider} argument.
     *
     * @param contributorClass the generated contributor class
     * @return the matching constructor
     */
    private static Constructor<?> findProviderConstructor(Class<?> contributorClass) {
        for (Constructor<?> c : contributorClass.getDeclaredConstructors()) {
            if (c.getParameterCount() == 1) {
                c.setAccessible(true);
                return c;
            }
        }
        throw new IllegalStateException("No single-parameter constructor found on " + contributorClass.getName());
    }

    /**
     * Finds the two-parameter constructor of a generated contributor that takes two
     * {@code Provider} arguments (for all-conditional two-impl groups).
     *
     * @param contributorClass the generated contributor class
     * @return the matching constructor
     */
    private static Constructor<?> findTwoProviderConstructor(Class<?> contributorClass) {
        for (Constructor<?> c : contributorClass.getDeclaredConstructors()) {
            if (c.getParameterCount() == 2) {
                c.setAccessible(true);
                return c;
            }
        }
        throw new IllegalStateException("No two-parameter constructor found on " + contributorClass.getName());
    }

    /**
     * Finds the three-parameter constructor of a generated contributor that takes three
     * {@code Provider} arguments (for default + two-conditional groups).
     *
     * @param contributorClass the generated contributor class
     * @return the matching constructor
     */
    private static Constructor<?> findThreeProviderConstructor(Class<?> contributorClass) {
        for (Constructor<?> c : contributorClass.getDeclaredConstructors()) {
            if (c.getParameterCount() == 3) {
                c.setAccessible(true);
                return c;
            }
        }
        throw new IllegalStateException("No three-parameter constructor found on " + contributorClass.getName());
    }
}
