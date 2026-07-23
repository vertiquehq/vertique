// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.util.GeneratedNames;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Looks up {@link GeneratedInputProcessor} instances for participating DTO types and dispatches
 * nested-type traversal during structured-body processing. Generated processors are resolved
 * from the consuming type's classloader via {@link Class#forName}; on miss, traversal continues
 * reflectively through the {@link ReflectiveContinuation} supplied by
 * {@link DefaultInputObjectProcessor}, preserving the accumulated
 * {@link InputTraversalContext}.
 *
 * <p>Resolution results are cached via {@link ClassValue}{@code <Optional<GeneratedInputProcessor<?>>>},
 * which is classloader-aware: cache entries are eligible for GC alongside their class loader,
 * eliminating the multi-classloader retention concern that a static
 * {@code ConcurrentHashMap<Class<?>, ...>} would have. The first resolution per type performs one
 * {@code Class.forName} + {@code newInstance}; all subsequent calls for the same type are O(1)
 * cache hits.
 *
 * <p><strong>Cache-miss policy</strong> mirrors {@code BeanParamAccessorRegistry}:
 * {@link ClassNotFoundException} is cached as {@link Optional#empty()} (no generated class on the
 * classpath — fall back reflectively). Any other failure during instantiation
 * ({@code NoSuchMethodException}, {@code InstantiationException}, exceptions thrown from the
 * generated constructor) is propagated as a {@link RuntimeException}: a broken generated class is
 * a build defect, not a runtime fallback case, and silently masking it would hide the bug.
 *
 * <p>An explicit {@link #register(Class, GeneratedInputProcessor)} method is provided for
 * non-classloader scenarios (e.g. unit tests that want to inject a counting double). Production
 * wiring relies entirely on the classloader path.
 */
public final class GeneratedInputProcessorDispatcher {

    private final ReflectiveContinuation continuation;

    /**
     * Manual registration cache. Checked before the classloader-driven {@link ClassValue} cache
     * so that explicit registrations (typically from tests) take precedence over generated
     * classes on the classpath.
     */
    private final ConcurrentMap<Class<?>, GeneratedInputProcessor<?>> manualRegistrations = new ConcurrentHashMap<>();

    /**
     * Classloader-driven lookup cache. Uses {@link ClassValue} so cache entries are GC-collected
     * alongside their owning classloader.
     *
     * <p>The cache value is a {@link LookupResult} sealed type so that the {@code Broken} case
     * (generated class exists but cannot be instantiated — a build defect) is cached too.
     * Without this, every subsequent call for the broken type would retry {@code Class.forName}
     * + {@code newInstance}, pinning CPU under load. The cached exception is rethrown on every
     * retrieval so consumers still see the failure immediately, but no reflection is re-attempted.
     */
    private final ClassValue<LookupResult> cache = new ClassValue<>() {
        @Override
        protected LookupResult computeValue(Class<?> type) {
            return classloaderLookup(type);
        }
    };

    /**
     * Creates a dispatcher with the given reflective continuation.
     *
     * @param continuation invoked when a nested type has no generated processor; must not be
     *                     {@code null}
     */
    public GeneratedInputProcessorDispatcher(ReflectiveContinuation continuation) {
        this.continuation = continuation;
    }

    /**
     * Manually registers a generated processor for a type, bypassing the classloader lookup.
     * Intended for unit tests or non-classloader scenarios.
     *
     * @param <T>      the target DTO type
     * @param type     the target type; must not be {@code null}
     * @param instance the generated processor instance; must not be {@code null}
     */
    public <T> void register(Class<T> type, GeneratedInputProcessor<T> instance) {
        manualRegistrations.put(type, instance);
    }

    /**
     * Resolves the generated processor for the given type, returning {@link Optional#empty()}
     * when none is available on the classpath.
     *
     * @param <T>  the target DTO type
     * @param type the target type to resolve; must not be {@code null}
     * @return the resolved processor, or empty if no generated class exists for {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<GeneratedInputProcessor<T>> resolve(Class<T> type) {
        if (!manualRegistrations.isEmpty()) {
            GeneratedInputProcessor<?> manual = manualRegistrations.get(type);
            if (manual != null) {
                return Optional.of((GeneratedInputProcessor<T>) manual);
            }
        }
        return switch (cache.get(type)) {
            case LookupResult.Found(GeneratedInputProcessor<?> processor) ->
                Optional.of((GeneratedInputProcessor<T>) processor);
            case LookupResult.Missing ignored -> Optional.empty();
            case LookupResult.Broken(RuntimeException error) -> throw error;
        };
    }

    /**
     * Dispatches a nested-type traversal: tries the generated processor first; on miss, hands
     * off to the reflective continuation with the existing {@link InputTraversalContext}, so
     * accumulated ancestor chains and sticky skip flags are preserved across the boundary.
     *
     * <p>When a generated processor is found, it is invoked with {@code parentPath} as the
     * dot-separated prefix for all field paths it composes, ensuring {@code InputValueContext}
     * records carry the full path from the request root.
     *
     * @param intermediate the nested intermediate (typically a {@code Map<String, Object>})
     * @param nestedType   the nested DTO class
     * @param policies     route-level effective policies (passed through to generated processors)
     * @param location     where the body originated
     * @param resolver     chain application contract (passed through to generated processors)
     * @param parentCtx    the accumulated traversal context from the caller
     * @param parentPath   the dot-separated path of the nested field (becomes the {@code parentPath}
     *                     argument in the generated processor's {@code process} call); use an empty
     *                     string for top-level invocations
     * @param ownerType    the owner type seen by reflective continuation when present
     * @return the processed nested intermediate
     */
    public Object dispatchNested(
            Object intermediate,
            Class<?> nestedType,
            EffectiveInputPolicies policies,
            InputLocation location,
            ChainResolver resolver,
            InputTraversalContext parentCtx,
            String parentPath,
            Class<?> ownerType) {

        if (!manualRegistrations.isEmpty()) {
            GeneratedInputProcessor<?> manual = manualRegistrations.get(nestedType);
            if (manual != null) {
                return manual.process(intermediate, policies, location, resolver, this, parentCtx, parentPath);
            }
        }
        return switch (cache.get(nestedType)) {
            case LookupResult.Found(GeneratedInputProcessor<?> generated) ->
                generated.process(intermediate, policies, location, resolver, this, parentCtx, parentPath);
            case LookupResult.Missing ignored ->
                continuation.continueAt(intermediate, nestedType, parentCtx, location, parentPath, ownerType);
            case LookupResult.Broken(RuntimeException error) -> throw error;
        };
    }

    /**
     * Walks the intermediate reflectively with {@link InputPolicyMetadata#EMPTY} per-field
     * metadata via the {@link ReflectiveContinuation#walkUnknown} callback. Intended for use
     * by generated processors handling unknown-key values (extra Jackson keys, {@code Object}
     * fields, {@code Map<String, Object>} fields) where no schema is available.
     *
     * @param value     the value to walk (map / list / string / other)
     * @param ctx       the accumulated traversal context (with inherited chains)
     * @param location  where the body originated
     * @param fieldPath the dot-separated path so far, for diagnostic context
     * @param ownerType the owner type for {@link dev.vertique.core.sanitization.InputValueContext}
     * @return the processed value, applying only inherited chains
     */
    public Object walkUnknown(
            Object value, InputTraversalContext ctx, InputLocation location, String fieldPath, Class<?> ownerType) {
        return continuation.walkUnknown(value, ctx, location, fieldPath, ownerType);
    }

    /**
     * Resumes traversal reflectively for a type that has no generated processor. Provided by
     * {@link DefaultInputObjectProcessor} when constructing the dispatcher.
     *
     * <p>Implementations must be non-functional (two methods); the interface is intentionally
     * not marked {@code @FunctionalInterface}.
     */
    public interface ReflectiveContinuation {

        /**
         * Continues processing the given intermediate at the given accumulated context.
         *
         * @param intermediate the intermediate body fragment (map / list / string)
         * @param targetType   the type of the fragment
         * @param ctx          the accumulated traversal context
         * @param location     where the body originated
         * @param fieldPath    the dot-separated path so far, for diagnostic context
         * @param ownerType    the owner type for {@code InputValueContext}
         * @return the processed intermediate fragment
         */
        Object continueAt(
                Object intermediate,
                Class<?> targetType,
                InputTraversalContext ctx,
                InputLocation location,
                String fieldPath,
                Class<?> ownerType);

        /**
         * Walks the intermediate reflectively with {@link InputPolicyMetadata#EMPTY} per-field
         * metadata, applying only the inherited chains in {@code ctx}. Used for unknown nested
         * keys (extra Jackson keys, {@code Object} fields, {@code Map<String, Object>} fields)
         * where no schema is available.
         *
         * <p>The dispatcher must NOT be consulted with the parent's class for these cases:
         * passing the parent's class would either resolve the parent's generated processor
         * (re-applying the parent's per-field switch to the child map) or fall through to
         * {@code continueAt} with {@code targetType = ownerType} (re-applying the parent's
         * metadata).
         *
         * @param intermediate the intermediate fragment (map / list / string)
         * @param ctx          the accumulated traversal context (with inherited chains)
         * @param location     where the body originated
         * @param fieldPath    the dot-separated path so far, for diagnostic context
         * @param ownerType    the owner type for {@link dev.vertique.core.sanitization.InputValueContext}
         * @return the processed intermediate, applying only inherited chains
         */
        Object walkUnknown(
                Object intermediate,
                InputTraversalContext ctx,
                InputLocation location,
                String fieldPath,
                Class<?> ownerType);
    }

    /**
     * Performs the classloader lookup for the generated companion class of {@code type}. The
     * derivation algorithm matches {@code Identifiers.generatedClassName} produced by the
     * codegen emitter:
     * <ul>
     *   <li>package: same as the source type</li>
     *   <li>simple name: binary class part (after last {@code '.'}) with {@code '$'} replaced by
     *       {@code '_'} (flattens nested types like {@code Outer$Inner} → {@code Outer_Inner})</li>
     *   <li>suffix: {@code _InputProcessor}</li>
     * </ul>
     *
     * @param type the source type
     * @return an {@link Optional} containing the loaded and instantiated processor, or empty when
     *         no generated class exists on the classpath
     * @throws RuntimeException when the generated class exists but cannot be instantiated
     */
    private LookupResult classloaderLookup(Class<?> type) {
        String fqn = generatedClassName(type);
        try {
            Class<?> generatedClass = Class.forName(fqn, true, type.getClassLoader());
            Object instance = generatedClass.getDeclaredConstructor().newInstance();
            return new LookupResult.Found((GeneratedInputProcessor<?>) instance);
        } catch (ClassNotFoundException notFound) {
            return LookupResult.Missing.INSTANCE;
        } catch (ClassCastException castEx) {
            // Present but the loaded class does not implement GeneratedInputProcessor — a build defect.
            return new LookupResult.Broken(new RuntimeException(
                    "Generated input processor %s found but does not implement GeneratedInputProcessor".formatted(fqn),
                    castEx));
        } catch (ReflectiveOperationException | LinkageError broken) {
            // Cache the failure so subsequent requests don't re-do reflection. The exception is rethrown on
            // every retrieval, so consumers still see the build defect. LinkageError covers a failing static
            // initializer / link-time NoClassDefFoundError at the eager Class.forName(initialize=true) load step.
            return new LookupResult.Broken(new RuntimeException(
                    "Generated input processor %s present but failed to load or instantiate".formatted(fqn), broken));
        }
    }

    /**
     * Cached classloader-lookup result. {@link Found} carries the resolved processor, {@link Missing}
     * indicates no companion class on the classpath (reflective fallback), {@link Broken} carries
     * the captured instantiation failure for re-throw on every retrieval — caching the failure
     * avoids re-doing reflection per request when a generated class is structurally invalid.
     */
    private sealed interface LookupResult {
        /** Successful lookup. */
        record Found(GeneratedInputProcessor<?> processor) implements LookupResult {}

        /** No companion class on the classpath. */
        enum Missing implements LookupResult {
            /** Singleton; cached once per type. */
            INSTANCE
        }

        /** Companion class found but could not be instantiated. The exception is rethrown on retrieval. */
        record Broken(RuntimeException error) implements LookupResult {}
    }

    /**
     * Derives the generated class FQN for a source type. Package-private for
     * {@link GeneratedInputProcessorDispatcherTest} to assert byte-equality with
     * {@code Identifiers.generatedClassName(...)} from {@code vertique-codegen-core}.
     *
     * @param type the source type
     * @return the FQN of the expected generated companion class
     */
    static String generatedClassName(Class<?> type) {
        return GeneratedNames.companionFqn(type, "_InputProcessor");
    }

    /**
     * Returns a dispatcher whose continuation throws {@link UnsupportedOperationException} when
     * invoked. Intended for unit tests that exercise generated processors in isolation against
     * inputs that never reach external (out-of-CU) types.
     *
     * @return a dispatcher with no reflective fallback
     */
    public static GeneratedInputProcessorDispatcher withoutContinuation() {
        return new GeneratedInputProcessorDispatcher(new ReflectiveContinuation() {
            @Override
            public Object continueAt(
                    Object intermediate,
                    Class<?> targetType,
                    InputTraversalContext ctx,
                    InputLocation location,
                    String fieldPath,
                    Class<?> ownerType) {
                throw new UnsupportedOperationException("Reflective continuation not configured; nested type "
                        + targetType.getName()
                        + " has no generated processor and no fallback was provided");
            }

            @Override
            public Object walkUnknown(
                    Object intermediate,
                    InputTraversalContext ctx,
                    InputLocation location,
                    String fieldPath,
                    Class<?> ownerType) {
                throw new UnsupportedOperationException(
                        "Reflective continuation not configured; walkUnknown invoked with no fallback");
            }
        });
    }
}
