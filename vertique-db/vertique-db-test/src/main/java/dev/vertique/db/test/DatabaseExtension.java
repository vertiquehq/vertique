// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that auto-starts and stops {@link DatabaseContainer} fields.
 *
 * <p>Finds all {@code static} fields of type {@link DatabaseContainer} in the test class and starts
 * them before all tests, then stops them after all tests.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @ExtendWith(DatabaseExtension.class)
 * class MyIT {
 *     static final PostgresContainer db = new PostgresContainer()
 *         .withDatabaseName("test_db")
 *         .withMigration();
 *
 *     // db is automatically started and stopped
 * }
 * }</pre>
 */
public class DatabaseExtension implements BeforeAllCallback, AfterAllCallback {

    /**
     * Starts all static {@link DatabaseContainer} fields found in the test class.
     *
     * @param context the extension context provided by JUnit 5
     * @throws Exception if a field cannot be accessed or a container fails to start
     */
    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        for (DatabaseContainer<?, ?> container : findContainerFields(context)) {
            container.start();
        }
    }

    /**
     * Stops all static {@link DatabaseContainer} fields found in the test class.
     *
     * @param context the extension context provided by JUnit 5
     * @throws Exception if a field cannot be accessed or a container fails to stop
     */
    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        for (DatabaseContainer<?, ?> container : findContainerFields(context)) {
            container.close();
        }
    }

    /**
     * Reflects over the test class to find all static {@link DatabaseContainer} fields.
     *
     * @param context the extension context provided by JUnit 5
     * @return an ordered list of containers found in the test class
     * @throws Exception if a field cannot be accessed
     */
    private List<DatabaseContainer<?, ?>> findContainerFields(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        List<DatabaseContainer<?, ?>> containers = new ArrayList<>();
        for (Field field : testClass.getDeclaredFields()) {
            if (DatabaseContainer.class.isAssignableFrom(field.getType()) && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof DatabaseContainer<?, ?> dc) {
                    containers.add(dc);
                }
            }
        }
        return containers;
    }
}
