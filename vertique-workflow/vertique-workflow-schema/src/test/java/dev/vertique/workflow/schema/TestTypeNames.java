// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import java.util.Optional;

/**
 * A type-name index for tests that call the registration validator directly, without a registry that indexes the
 * candidate first: it finds a class by its binary name when the class exists on the test class path, the way a catalog
 * lists the fixture payloads. A name that names no class stays unknown.
 */
final class TestTypeNames implements WorkflowTypeNames {

    private final DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();

    private TestTypeNames() {}

    static WorkflowTypeNames lenient() {
        return new TestTypeNames();
    }

    @Override
    public String nameOf(Class<?> type) {
        return names.nameOf(type);
    }

    @Override
    public Class<?> resolve(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("unknown name " + name));
    }

    @Override
    public Optional<Class<?>> find(String name) {
        try {
            return Optional.of(Class.forName(name, false, TestTypeNames.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public String contractNameOf(Class<?> contract) {
        return names.contractNameOf(contract);
    }

    @Override
    public Optional<Class<?>> findContract(String name) {
        return names.findContract(name);
    }
}
