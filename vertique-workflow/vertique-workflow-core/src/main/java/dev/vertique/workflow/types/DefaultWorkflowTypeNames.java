// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowTypeNameConflictException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link WorkflowTypeNames} index, filled as workflow definitions register.
 *
 * <p>Two separate namespaces hold the contract names and the type names. Within one namespace a name
 * maps to one class. Every indexed class is reachable by its logical name and by its binary name, so a
 * logical name equal to the binary name of a different indexed class is the same conflict, whichever
 * class is indexed first. Indexing the same class under the same name again changes nothing.
 *
 * <p>Names must be nonblank, at most 255 code points and free of ISO control characters; an invalid
 * name fails indexing with a {@link WorkflowDefinitionException} that names the class and never the
 * raw value. Lookups read the index only and never load a class.
 *
 * <p>Thread-safe: lookups are lock-free and indexing is serialized.
 */
public final class DefaultWorkflowTypeNames implements WorkflowTypeNames {

    private static final int MAX_NAME_CODE_POINTS = 255;

    private final Map<String, Holder> types = new ConcurrentHashMap<>();
    private final Map<String, Holder> contracts = new ConcurrentHashMap<>();

    /** Creates an empty index. */
    public DefaultWorkflowTypeNames() {}

    @Override
    public String nameOf(Class<?> type) {
        Objects.requireNonNull(type, "type");
        WorkflowType annotation = type.getAnnotation(WorkflowType.class);
        return annotation == null ? type.getName() : annotation.value();
    }

    @Override
    public Class<?> resolve(String name) {
        return find(name)
                .orElseThrow(() -> new WorkflowUnknownTypeNameException(
                        isValidName(name)
                                ? "unknown workflow type name '" + name + "'"
                                : "unknown workflow type name"));
    }

    @Override
    public Optional<Class<?>> find(String name) {
        return lookup(types, name);
    }

    @Override
    public String contractNameOf(Class<?> contract) {
        Objects.requireNonNull(contract, "contract");
        WorkflowContract annotation = contract.getAnnotation(WorkflowContract.class);
        return annotation == null || annotation.value().isEmpty() ? contract.getName() : annotation.value();
    }

    @Override
    public Optional<Class<?>> findContract(String name) {
        return lookup(contracts, name);
    }

    private static Optional<Class<?>> lookup(Map<String, Holder> namespace, String name) {
        return name == null
                ? Optional.empty()
                : Optional.ofNullable(namespace.get(name)).map(Holder::type);
    }

    /**
     * INTERNAL framework seam — used by the workflow registry while it registers a definition; not an
     * application contract and outside the maturity promise.
     *
     * <p>Indexes a candidate definition's contract and type classes as one unit. Either every class is
     * indexed, or a name is refused and the index is left exactly as it was before the call.
     *
     * @param contract the candidate's contract interface; may be {@code null} to index none
     * @param typeClasses the candidate's state and payload classes; {@code null} elements are skipped
     * @return an action that releases what this call indexed; a name stays indexed while any other
     *     successful or concurrent call still holds it
     * @throws WorkflowDefinitionException if a name is invalid
     * @throws WorkflowTypeNameConflictException if a name is already held by a different class
     */
    public synchronized Runnable add(Class<?> contract, Collection<Class<?>> typeClasses) {
        List<Runnable> undo = new ArrayList<>();
        try {
            if (contract != null) {
                addClass(contracts, contract, contractNameOf(contract), "contract", undo);
            }
            for (Class<?> type : typeClasses) {
                if (type != null) {
                    addClass(types, type, nameOf(type), "type", undo);
                }
            }
        } catch (RuntimeException e) {
            undo.forEach(Runnable::run);
            throw e;
        }
        return () -> {
            synchronized (this) {
                undo.forEach(Runnable::run);
            }
        };
    }

    private static void addClass(
            Map<String, Holder> namespace, Class<?> type, String logical, String kind, List<Runnable> undo) {
        if (!isValidName(logical)) {
            throw new WorkflowDefinitionException("the logical " + kind + " name of class " + type.getName()
                    + " is invalid: it must be nonblank, at most " + MAX_NAME_CODE_POINTS
                    + " code points and contain no control characters");
        }
        String binary = type.getName();
        // Check both keys before writing either, so a conflict leaves nothing half-indexed.
        checkFree(namespace, logical, type, kind);
        checkFree(namespace, binary, type, kind);
        put(namespace, logical, type, undo);
        put(namespace, binary, type, undo);
    }

    private static void checkFree(Map<String, Holder> namespace, String key, Class<?> type, String kind) {
        Holder holder = namespace.get(key);
        if (holder != null && holder.type != type) {
            throw new WorkflowTypeNameConflictException("the " + kind + " name '" + key + "' is claimed by both "
                    + holder.type.getName() + " and " + type.getName());
        }
    }

    /** Takes one reference on the key; the returned action releases it and drops the key at zero. */
    private static void put(Map<String, Holder> namespace, String key, Class<?> type, List<Runnable> undo) {
        namespace.computeIfAbsent(key, k -> new Holder(type)).refs++;
        undo.add(() -> {
            Holder holder = namespace.get(key);
            if (holder != null && --holder.refs == 0) {
                namespace.remove(key);
            }
        });
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            return false;
        }
        return name.codePoints().noneMatch(Character::isISOControl);
    }

    /** A class under one name with the number of indexing calls that hold it; touched under the lock only. */
    private static final class Holder {
        private final Class<?> type;
        private int refs;

        private Holder(Class<?> type) {
            this.type = type;
        }

        private Class<?> type() {
            return type;
        }
    }
}
