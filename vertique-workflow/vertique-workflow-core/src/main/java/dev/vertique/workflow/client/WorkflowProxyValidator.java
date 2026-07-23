// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.contract.BusinessKey;
import dev.vertique.workflow.contract.BusinessKeyed;
import dev.vertique.workflow.contract.IdempotencyKey;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.SignalDedupKeyed;
import dev.vertique.workflow.contract.SubjectRef;
import dev.vertique.workflow.contract.SubjectReferenced;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowQuery;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a workflow contract interface against the rules in §8.3 of the implementation plan.
 *
 * <p>This class is called by {@link WorkflowClientFactory#create(Class)} before creating a proxy.
 * All validation is performed eagerly at proxy-creation time so contract violations are surfaced
 * at application startup rather than at the first invocation.
 *
 * <p>Validation rules (each violation throws {@link WorkflowProxyContractException}):
 * <ul>
 *   <li>Interface must be annotated with {@code @WorkflowContract}</li>
 *   <li>{@code definitionId} and {@code definitionVersion} must resolve in the registry</li>
 *   <li>Default methods are not supported on workflow contract interfaces (cycle-1 constraint);
 *       every non-{@link Object} method must carry exactly one role annotation</li>
 *   <li>Every non-default, non-{@link Object} method must carry exactly one of
 *       {@code @WorkflowStart}, {@code @WorkflowSignal}, or {@code @WorkflowQuery}</li>
 *   <li>Exactly one {@code @WorkflowStart} method; return type must be
 *       {@code Future<WorkflowInstanceId>}</li>
 *   <li>{@code @WorkflowStart}: exactly one payload parameter required; idempotency key source
 *       required ({@code @IdempotencyKey} param or payload implements {@code IdempotencyKeyed})</li>
 *   <li>{@code @WorkflowSignal}: return type must be {@code Future<Void>}; exactly one payload
 *       parameter required; dedup key source required; signal name must be in the plan; no two
 *       methods with the same signal name</li>
 *   <li>{@code @WorkflowQuery}: return type must be exactly {@code Future<WorkflowView>} (no supertype
 *       or wildcard); single parameter of type {@code WorkflowInstanceId}</li>
 *   <li>No method may carry two of {@code @WorkflowStart}, {@code @WorkflowSignal},
 *       {@code @WorkflowQuery}</li>
 * </ul>
 */
public final class WorkflowProxyValidator {

    /** Utility class; not instantiable. */
    private WorkflowProxyValidator() {}

    // --- Validate ---

    /**
     * Validates the contract interface and throws {@link WorkflowProxyContractException} on the
     * first violation found.
     *
     * @param contractInterface the interface to validate
     * @param registry the workflow registry used to resolve the definition and plan
     * @throws WorkflowProxyContractException if any validation rule is violated
     */
    public static void validate(Class<?> contractInterface, WorkflowRegistry registry) {
        // 1. Require @WorkflowContract
        WorkflowContract wca = contractInterface.getAnnotation(WorkflowContract.class);
        if (wca == null) {
            throw new WorkflowProxyContractException(
                    "Contract interface " + contractInterface.getName() + " is missing @WorkflowContract annotation");
        }

        // 1a. Must be an interface. The generated-proxy / JDK-proxy mechanics both require an interface, and
        // the codegen processor rejects @WorkflowContract on a class at compile time — fail here too so the
        // contract is validated identically with or without codegen (rather than failing later from proxy
        // creation mechanics).
        if (!contractInterface.isInterface()) {
            throw new WorkflowProxyContractException(
                    "@WorkflowContract " + contractInterface.getName() + " must be an interface");
        }

        // 2. Resolve the definition in the registry
        String definitionId = wca.definitionId();
        long definitionVersion = wca.definitionVersion();
        RuntimeWorkflow rw;
        try {
            rw = registry.resolvePinned(definitionId, definitionVersion);
        } catch (WorkflowVersionPinUnavailableException e) {
            throw new WorkflowProxyContractException(
                    "Contract " + contractInterface.getName() + " references definition '" + definitionId + "' v"
                            + definitionVersion + " which is not registered: " + e.getMessage(),
                    e);
        } catch (dev.vertique.workflow.exception.WorkflowDefinitionMissingException e) {
            throw new WorkflowProxyContractException(
                    "Contract " + contractInterface.getName() + " references definition '" + definitionId
                            + "' which is not registered",
                    e);
        }

        // 3. Collect all non-Object methods and validate role annotations
        List<Method> methods = getContractMethods(contractInterface);

        // 3a. Reject sibling-inherited duplicate signatures: two methods with the same name + erased
        // parameter types declared by unrelated super-interfaces. Class#getMethods() returns both (the
        // codegen scanner likewise preserves both via MethodOverrides), and a generated concrete proxy
        // cannot implement two methods with the same signature — both paths reject the ambiguous shape
        // with identical wording for parity. Override chains are already collapsed by getMethods() to the
        // most-derived declaration; synthetic bridge methods are excluded by getContractMethods().
        Map<String, Long> signatureCounts = new HashMap<>();
        for (Method method : methods) {
            signatureCounts.merge(erasedSignature(method), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> entry : signatureCounts.entrySet()) {
            if (entry.getValue() > 1) {
                throw new WorkflowProxyContractException("Contract " + contractInterface.getName()
                        + " inherits an ambiguous operation '" + entry.getKey()
                        + "' from multiple unrelated super-interfaces; a workflow contract may not inherit the same"
                        + " method signature from sibling interfaces");
            }
        }

        List<Method> startMethods = new ArrayList<>();
        Set<String> signalNames = new HashSet<>();
        Set<String> planSignalNames = collectPlanSignalNames(rw);

        for (Method method : methods) {
            // Default methods are not supported in cycle 1 — the proxy cannot forward invocations
            // to the default implementation without MethodHandles.privateLookupIn machinery.
            if (method.isDefault()) {
                throw new WorkflowProxyContractException(
                        "Method " + method.getName() + " in " + contractInterface.getName()
                                + " is a default method; default methods are not supported on workflow"
                                + " contract interfaces in cycle 1.");
            }

            WorkflowStart ws = method.getAnnotation(WorkflowStart.class);
            WorkflowSignal wsg = method.getAnnotation(WorkflowSignal.class);
            WorkflowQuery wq = method.getAnnotation(WorkflowQuery.class);

            // Conflicting annotations check
            int annotationCount = (ws != null ? 1 : 0) + (wsg != null ? 1 : 0) + (wq != null ? 1 : 0);
            if (annotationCount > 1) {
                throw new WorkflowProxyContractException(
                        "Method " + method.getName() + " on " + contractInterface.getName()
                                + " has conflicting workflow annotations; a method must have at most one"
                                + " of @WorkflowStart, @WorkflowSignal, @WorkflowQuery");
            }

            // Every non-default, non-Object method must carry exactly one role annotation.
            if (annotationCount == 0) {
                throw new WorkflowProxyContractException(
                        "Method " + method.getName() + " in " + contractInterface.getName()
                                + " has no workflow role annotation; declare exactly one of"
                                + " @WorkflowStart, @WorkflowSignal, or @WorkflowQuery.");
            }

            if (ws != null) {
                startMethods.add(method);
                validateStartMethod(method, contractInterface, rw);
            } else if (wsg != null) {
                String signalName = wsg.value();
                if (!signalNames.add(signalName)) {
                    throw new WorkflowProxyContractException(
                            "Duplicate @WorkflowSignal(\"" + signalName + "\") methods on "
                                    + contractInterface.getName()
                                    + "; signal names must be unique within a contract");
                }
                if (!planSignalNames.contains(signalName)) {
                    throw new WorkflowProxyContractException(
                            "Signal name \"" + signalName + "\" on method " + method.getName() + " in "
                                    + contractInterface.getName()
                                    + " does not match any WaitSignalNode in the resolved plan for '"
                                    + definitionId + "' v" + definitionVersion);
                }
                validateSignalMethod(method, contractInterface, rw, signalName);
            } else if (wq != null) {
                validateQueryMethod(method, contractInterface);
            }
        }

        // 4. Exactly one @WorkflowStart
        if (startMethods.size() != 1) {
            throw new WorkflowProxyContractException("Contract " + contractInterface.getName()
                    + " must have exactly one @WorkflowStart method; found " + startMethods.size());
        }
    }

    // --- Precompile handlers ---

    /**
     * Pre-compiles per-method operation handlers for the given contract, for use by the proxy
     * invocation handler.
     *
     * <p>Callers must invoke {@link #validate(Class, WorkflowRegistry)} first. This method
     * assumes the contract is valid and does not re-validate.
     *
     * @param contractInterface the validated contract interface
     * @param ops the {@code WorkflowOperations} instance the proxy will delegate to
     * @param registry the workflow registry used to look up runtime metadata
     * @return a map from {@link Method} to the corresponding {@link OperationHandler}; never null
     */
    public static Map<Method, OperationHandler> precompileHandlers(
            Class<?> contractInterface, WorkflowOperations ops, WorkflowRegistry registry) {
        WorkflowContract wca = contractInterface.getAnnotation(WorkflowContract.class);
        String definitionId = wca.definitionId();
        long definitionVersion = wca.definitionVersion();

        Map<Method, OperationHandler> handlers = new HashMap<>();
        List<Method> methods = getContractMethods(contractInterface);

        for (Method method : methods) {
            WorkflowStart ws = method.getAnnotation(WorkflowStart.class);
            WorkflowSignal wsg = method.getAnnotation(WorkflowSignal.class);
            WorkflowQuery wq = method.getAnnotation(WorkflowQuery.class);

            if (ws != null) {
                handlers.put(method, buildStartHandler(method, definitionId, definitionVersion, ops));
            } else if (wsg != null) {
                handlers.put(method, buildSignalHandler(method, wsg.value(), ops));
            } else if (wq != null) {
                handlers.put(method, buildQueryHandler(method, ops));
            }
        }
        return handlers;
    }

    // --- Private validation helpers ---

    /**
     * Validates a {@link WorkflowStart}-annotated method.
     *
     * <p>Rules enforced:
     * <ul>
     *   <li>Return type must be {@code Future<WorkflowInstanceId>}.</li>
     *   <li>{@code WorkflowInstanceId} parameters are forbidden (start creates a new instance).</li>
     *   <li>Exactly one non-annotated, non-special parameter (the payload) is required.</li>
     *   <li>No duplicate {@code @IdempotencyKey}, {@code @BusinessKey}, or {@code @SubjectRef}
     *       parameters.</li>
     *   <li>Idempotency key source is required.</li>
     *   <li>The payload type must exactly match {@link RuntimeWorkflow#startPayloadType()} declared
     *       in the registered plan.</li>
     * </ul>
     *
     * @param method the method to validate
     * @param contractInterface the declaring interface (for error messages)
     * @param rw the resolved runtime workflow whose plan declares the expected start payload type
     * @throws WorkflowProxyContractException if any rule is violated
     */
    private static void validateStartMethod(Method method, Class<?> contractInterface, RuntimeWorkflow rw) {
        // Return type must be Future<WorkflowInstanceId>
        if (!isFutureOf(method.getGenericReturnType(), WorkflowInstanceId.class)) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowStart but return type is not Future<WorkflowInstanceId>; got "
                    + method.getGenericReturnType());
        }

        // Find the payload parameter and check idempotency key source
        Class<?>[] paramTypes = method.getParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        Class<?> payloadType = null;
        boolean hasIdempotencyKeyParam = false;
        boolean hasBusinessKeyParam = false;
        boolean hasSubjectRefParam = false;

        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == WorkflowInstanceId.class) {
                // WorkflowInstanceId is forbidden on @WorkflowStart (start creates a new instance)
                throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                        + contractInterface.getName()
                        + ": @WorkflowStart must not have a WorkflowInstanceId parameter;"
                        + " start always creates a new instance");
            } else if (hasAnnotation(paramAnnotations[i], IdempotencyKey.class)) {
                // @IdempotencyKey must be on a String parameter; duplicates are rejected
                if (paramTypes[i] != String.class) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName() + ": @IdempotencyKey must be on a String parameter");
                }
                if (hasIdempotencyKeyParam) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName()
                            + ": duplicate @IdempotencyKey parameters are not allowed");
                }
                hasIdempotencyKeyParam = true;
            } else if (hasAnnotation(paramAnnotations[i], BusinessKey.class)) {
                // @BusinessKey must be on a String parameter; duplicates are rejected
                if (paramTypes[i] != String.class) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName() + ": @BusinessKey must be on a String parameter");
                }
                if (hasBusinessKeyParam) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName() + ": duplicate @BusinessKey parameters are not allowed");
                }
                hasBusinessKeyParam = true;
            } else if (hasAnnotation(paramAnnotations[i], SubjectRef.class)) {
                // @SubjectRef must be on a WorkflowSubjectRef parameter; duplicates are rejected
                if (paramTypes[i] != WorkflowSubjectRef.class) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName() + ": @SubjectRef must be on a WorkflowSubjectRef parameter");
                }
                if (hasSubjectRefParam) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName() + ": duplicate @SubjectRef parameters are not allowed");
                }
                hasSubjectRefParam = true;
            } else {
                // Non-annotated parameter is the payload; only one is allowed
                if (payloadType != null) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName()
                            + ": @WorkflowStart must have at most one payload (non-annotated) parameter;"
                            + " found more than one");
                }
                payloadType = paramTypes[i];
            }
        }

        // Payload parameter is required
        if (payloadType == null) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                    + contractInterface.getName()
                    + " is @WorkflowStart but has no payload parameter;"
                    + " exactly one payload parameter is required");
        }

        // Idempotency key source is required
        boolean payloadIsIdempotencyKeyed = IdempotencyKeyed.class.isAssignableFrom(payloadType);
        if (!hasIdempotencyKeyParam && !payloadIsIdempotencyKeyed) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowStart but has no idempotency key source; either add"
                    + " @IdempotencyKey String parameter or have the payload implement"
                    + " IdempotencyKeyed");
        }

        // Payload type must exactly match the plan's declared startPayloadType
        if (!payloadType.equals(rw.startPayloadType())) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowStart with payload type " + payloadType.getName()
                    + ", but the registered plan declares startPayloadType="
                    + rw.startPayloadType().getName()
                    + ". Adjust the contract method or the plan's wf.init(...) declaration.");
        }
    }

    /**
     * Validates a {@link WorkflowSignal}-annotated method.
     *
     * <p>Rules enforced:
     * <ul>
     *   <li>Return type must be {@code Future<Void>}.</li>
     *   <li>Exactly one {@code WorkflowInstanceId} parameter, and it must be the first parameter.</li>
     *   <li>Exactly one non-annotated, non-instance-id parameter (the payload) is required.</li>
     *   <li>No duplicate {@code @SignalDedupKey} parameters.</li>
     *   <li>Dedup key source is required.</li>
     *   <li>The payload type must exactly match the type declared in
     *       {@link RuntimeWorkflow#signalPayloadTypes()} for {@code signalName}, when present.</li>
     * </ul>
     *
     * @param method the method to validate
     * @param contractInterface the declaring interface (for error messages)
     * @param rw the resolved runtime workflow whose plan declares the expected signal payload types
     * @param signalName the signal name from the {@link WorkflowSignal} annotation
     * @throws WorkflowProxyContractException if any rule is violated
     */
    private static void validateSignalMethod(
            Method method, Class<?> contractInterface, RuntimeWorkflow rw, String signalName) {
        // Return type must be Future<Void>
        if (!isFutureOf(method.getGenericReturnType(), Void.class)) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowSignal but return type is not Future<Void>; got "
                    + method.getGenericReturnType());
        }

        // Find payload type and check dedup key source
        Class<?>[] paramTypes = method.getParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        Class<?> payloadType = null;
        boolean hasDedupKeyParam = false;
        int instanceIdCount = 0;
        int firstInstanceIdIndex = -1;

        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == WorkflowInstanceId.class) {
                instanceIdCount++;
                if (firstInstanceIdIndex < 0) {
                    firstInstanceIdIndex = i;
                }
            } else if (hasAnnotation(paramAnnotations[i], SignalDedupKey.class)) {
                if (paramTypes[i] != String.class) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName() + ": @SignalDedupKey must be on a String parameter");
                }
                if (hasDedupKeyParam) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName()
                            + ": duplicate @SignalDedupKey parameters are not allowed");
                }
                hasDedupKeyParam = true;
            } else {
                // Non-annotated, non-instance-id parameter is the payload; only one allowed
                if (payloadType != null) {
                    throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                            + contractInterface.getName()
                            + ": @WorkflowSignal must have at most one payload (non-annotated) parameter;"
                            + " found more than one");
                }
                payloadType = paramTypes[i];
            }
        }

        // Exactly one WorkflowInstanceId parameter required
        if (instanceIdCount == 0) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                    + contractInterface.getName()
                    + ": @WorkflowSignal requires exactly one WorkflowInstanceId parameter; found none");
        }
        if (instanceIdCount > 1) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                    + contractInterface.getName()
                    + ": @WorkflowSignal requires exactly one WorkflowInstanceId parameter; found "
                    + instanceIdCount);
        }
        // WorkflowInstanceId must be the first parameter
        if (firstInstanceIdIndex != 0) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                    + contractInterface.getName()
                    + ": @WorkflowSignal requires WorkflowInstanceId to be the first parameter"
                    + " (found at index " + firstInstanceIdIndex + ")");
        }

        // Payload parameter is required
        if (payloadType == null) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in "
                    + contractInterface.getName()
                    + " is @WorkflowSignal but has no payload parameter;"
                    + " exactly one payload parameter is required");
        }

        // Dedup key source is required
        boolean payloadIsDedupKeyed = SignalDedupKeyed.class.isAssignableFrom(payloadType);
        if (!hasDedupKeyParam && !payloadIsDedupKeyed) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowSignal but has no dedup key source; either add"
                    + " @SignalDedupKey String parameter or have the payload implement"
                    + " SignalDedupKeyed");
        }

        // Payload type must exactly match the plan's declared signal payload type for this signal
        Class<?> declared = rw.signalPayloadTypes().get(signalName);
        if (declared != null && !declared.equals(payloadType)) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowSignal(\"" + signalName + "\") with payload type " + payloadType.getName()
                    + ", but the registered plan declares payloadType=" + declared.getName()
                    + " for that signal. Adjust the contract method or the plan's waitFor(...) declaration.");
        }
    }

    /**
     * Validates a {@link WorkflowQuery}-annotated method.
     *
     * @param method the method to validate
     * @param contractInterface the declaring interface (for error messages)
     * @throws WorkflowProxyContractException if any rule is violated
     */
    private static void validateQueryMethod(Method method, Class<?> contractInterface) {
        // V1: a @WorkflowQuery must return exactly Future<WorkflowView> — no supertype/wildcard. A single
        // untyped workflow-view query is the cycle-1 surface; typed query projections are a later, explicit
        // API (e.g. query(id, Class<V>)), not a broadening of this return type. Keeping runtime and codegen
        // on the same exact rule avoids any generated-vs-reflective divergence on the query return.
        Type returnType = method.getGenericReturnType();
        if (!isFutureOf(returnType, WorkflowView.class)) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowQuery but return type is not Future<WorkflowView>; got " + returnType);
        }

        // Must have exactly one parameter of type WorkflowInstanceId
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != 1 || paramTypes[0] != WorkflowInstanceId.class) {
            throw new WorkflowProxyContractException("Method " + method.getName() + " in " + contractInterface.getName()
                    + " is @WorkflowQuery but must have exactly one parameter of type"
                    + " WorkflowInstanceId; got " + paramTypes.length + " params");
        }
    }

    // --- Private handler builders ---

    /**
     * Builds an {@link OperationHandler} for a {@link WorkflowStart}-annotated method.
     *
     * @param method the start method
     * @param definitionId the workflow definition id
     * @param definitionVersion the definition version from {@link WorkflowContract#definitionVersion()};
     *     threaded into {@link StartCommand#requestedDefinitionVersion()} so that contract-bound
     *     proxies are version-pinned even after a newer version is deployed
     * @param ops the workflow operations to delegate to
     * @return the compiled handler
     */
    private static OperationHandler buildStartHandler(
            Method method, String definitionId, long definitionVersion, WorkflowOperations ops) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        // Pre-compute parameter roles
        int idempotencyKeyParamIndex = -1;
        int businessKeyParamIndex = -1;
        int subjectRefParamIndex = -1;
        int payloadParamIndex = -1;

        for (int i = 0; i < paramTypes.length; i++) {
            if (hasAnnotation(paramAnnotations[i], IdempotencyKey.class)) {
                idempotencyKeyParamIndex = i;
            } else if (hasAnnotation(paramAnnotations[i], BusinessKey.class)) {
                businessKeyParamIndex = i;
            } else if (hasAnnotation(paramAnnotations[i], SubjectRef.class)) {
                subjectRefParamIndex = i;
            } else if (paramTypes[i] != WorkflowInstanceId.class) {
                payloadParamIndex = i;
            }
        }

        final int iKeyIdx = idempotencyKeyParamIndex;
        final int bKeyIdx = businessKeyParamIndex;
        final int sRefIdx = subjectRefParamIndex;
        final int payloadIdx = payloadParamIndex;

        return args -> {
            Object payload = payloadIdx >= 0 ? args[payloadIdx] : null;
            String idempotencyKey;
            if (iKeyIdx >= 0) {
                idempotencyKey = (String) args[iKeyIdx];
            } else {
                // payload must implement IdempotencyKeyed (validated)
                idempotencyKey = ((IdempotencyKeyed) payload).idempotencyKey();
            }
            String businessKey;
            if (bKeyIdx >= 0) {
                businessKey = (String) args[bKeyIdx];
            } else if (payload instanceof BusinessKeyed bk) {
                businessKey = bk.businessKey();
            } else {
                businessKey = null;
            }
            WorkflowSubjectRef subjectRef;
            if (sRefIdx >= 0) {
                subjectRef = (WorkflowSubjectRef) args[sRefIdx];
            } else if (payload instanceof SubjectReferenced sr) {
                subjectRef = sr.subjectRef();
            } else {
                subjectRef = null;
            }
            StartCommand cmd =
                    new StartCommand(definitionId, payload, idempotencyKey, businessKey, subjectRef, definitionVersion);
            return ops.start(cmd);
        };
    }

    /**
     * Builds an {@link OperationHandler} for a {@link WorkflowSignal}-annotated method.
     *
     * @param method the signal method
     * @param signalName the signal name from the annotation
     * @param ops the workflow operations to delegate to
     * @return the compiled handler
     */
    private static OperationHandler buildSignalHandler(Method method, String signalName, WorkflowOperations ops) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        int instanceIdParamIndex = -1;
        int dedupKeyParamIndex = -1;
        int payloadParamIndex = -1;

        for (int i = 0; i < paramTypes.length; i++) {
            if (hasAnnotation(paramAnnotations[i], SignalDedupKey.class)) {
                dedupKeyParamIndex = i;
            } else if (paramTypes[i] == WorkflowInstanceId.class) {
                instanceIdParamIndex = i;
            } else {
                payloadParamIndex = i;
            }
        }

        final int idIdx = instanceIdParamIndex;
        final int dkIdx = dedupKeyParamIndex;
        final int payloadIdx = payloadParamIndex;

        return args -> {
            WorkflowInstanceId instanceId = idIdx >= 0 ? (WorkflowInstanceId) args[idIdx] : null;
            Object payload = payloadIdx >= 0 ? args[payloadIdx] : null;
            String dedupKey;
            if (dkIdx >= 0) {
                dedupKey = (String) args[dkIdx];
            } else {
                // payload must implement SignalDedupKeyed (validated)
                dedupKey = ((SignalDedupKeyed) payload).dedupKey();
            }
            return ops.signal(instanceId, signalName, payload, dedupKey);
        };
    }

    /**
     * Builds an {@link OperationHandler} for a {@link WorkflowQuery}-annotated method.
     *
     * @param method the query method
     * @param ops the workflow operations to delegate to
     * @return the compiled handler
     */
    private static OperationHandler buildQueryHandler(Method method, WorkflowOperations ops) {
        return args -> {
            WorkflowInstanceId instanceId = (WorkflowInstanceId) args[0];
            return ops.query(instanceId);
        };
    }

    // --- Utility helpers ---

    /**
     * Returns all non-{@link Object} methods declared by the contract interface (including methods
     * inherited from super-interfaces), excluding synthetic bridge methods. Bridge methods are a
     * compiler artifact of covariant overrides (the same name + erased parameter types as the real
     * method but a super return type); excluding them keeps the method view aligned with the
     * codegen scanner (which works on source elements that have no bridges) and prevents a bridge
     * from being mistaken for a sibling-duplicate signature.
     *
     * @param contractInterface the interface to inspect
     * @return list of non-Object, non-bridge methods; order is stable (declaration order per interface)
     */
    private static List<Method> getContractMethods(Class<?> contractInterface) {
        return Arrays.stream(contractInterface.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .filter(m -> !m.isBridge())
                .toList();
    }

    /**
     * Returns the erased signature of a method — its name plus the canonical names of its erased
     * parameter types — used to detect sibling-inherited duplicate operations. Uses
     * {@link Class#getCanonicalName()} (e.g. {@code com.example.Outer.Inner}, {@code java.lang.String[]})
     * so the signature token embedded in the ambiguity error matches the codegen-side
     * {@code MethodOverrides.erasedSignature} format ({@code types.erasure(...).toString()}, which is
     * also source/canonical) rather than diverging on nested ({@code $}) or array ({@code [L…;})
     * binary names. Falls back to {@link Class#getName()} for the rare local/anonymous parameter type
     * whose canonical name is {@code null}.
     *
     * @param method the method whose erased signature to compute
     * @return the erased signature, e.g. {@code start(com.example.StartOrder)}
     */
    private static String erasedSignature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String canonical = params[i].getCanonicalName();
            sb.append(canonical != null ? canonical : params[i].getName());
        }
        return sb.append(')').toString();
    }

    /**
     * Collects all signal names declared by {@link WaitSignalNode}s in the given plan.
     *
     * @param rw the resolved runtime workflow
     * @return set of signal names present in the plan
     */
    private static Set<String> collectPlanSignalNames(RuntimeWorkflow rw) {
        Set<String> names = new HashSet<>();
        for (var node : rw.plan().nodes()) {
            if (node instanceof WaitSignalNode w) {
                names.add(w.signalName());
            }
        }
        return names;
    }

    /**
     * Returns {@code true} if the given {@link Type} is {@code Future<T>} for the specified type
     * argument class.
     *
     * @param type the type to inspect
     * @param typeArg the expected type argument class (e.g., {@code WorkflowInstanceId.class})
     * @return true if the type matches {@code Future<typeArg>}
     */
    private static boolean isFutureOf(Type type, Class<?> typeArg) {
        if (!(type instanceof ParameterizedType pt)) return false;
        if (pt.getRawType() != Future.class) return false;
        Type[] args = pt.getActualTypeArguments();
        if (args.length != 1) return false;
        Type arg = args[0];
        return arg instanceof Class<?> cls && cls == typeArg;
    }

    /**
     * Returns {@code true} if any annotation in the array is of the given type.
     *
     * @param annotations the parameter annotation array to search
     * @param annotationType the annotation type to look for
     * @return true if the annotation is present
     */
    private static boolean hasAnnotation(Annotation[] annotations, Class<? extends Annotation> annotationType) {
        for (Annotation a : annotations) {
            if (annotationType.isInstance(a)) return true;
        }
        return false;
    }
}
