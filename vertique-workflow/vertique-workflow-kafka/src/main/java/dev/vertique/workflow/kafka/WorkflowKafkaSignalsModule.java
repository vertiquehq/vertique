// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.workflow.services.source.WorkflowSignalSourceDelivery;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns every {@link KafkaSignalSource} of the application into a {@code KafkaConsumerBinding} for the Kafka consumer
 * registry.
 *
 * <p>The generated registrations module includes this module, because {@code KafkaSignalSource} names it through
 * {@code @SignalSourceType}; an application names only the generated module. This module declares no
 * {@code @Multibinds} for the {@code Set<KafkaSignalSource<?>>}: a component that leaves out the generated module has no
 * binding for it and fails to build, instead of running with no source consuming.
 *
 * <p>Each source's {@code define} runs once, when the binding set is first built, which is after the dependency graph
 * exists. The module needs only a {@code WorkflowSignalSourceDelivery} and a {@code CorrelationContextFactory} of the
 * application.
 */
@Module
public abstract class WorkflowKafkaSignalsModule {

    /**
     * Builds the binding of each source.
     *
     * @param sources the sources the generated wiring registered
     * @param delivery the delivery bean that records the sources' signals
     * @param correlationFactory the factory that seeds the correlation of a source that restores it from headers
     * @return one consumer binding per source
     * @throws IllegalStateException naming the class, when a source's message type cannot be read from its
     *     {@code KafkaSignalSource} type argument, or when a source's builder cannot be built
     */
    @Provides
    @ElementsIntoSet
    static Set<KafkaConsumerBinding<?>> kafkaSignalBindings(
            Set<KafkaSignalSource<?>> sources,
            WorkflowSignalSourceDelivery delivery,
            CorrelationContextFactory correlationFactory) {
        Set<KafkaConsumerBinding<?>> bindings = new LinkedHashSet<>();
        for (KafkaSignalSource<?> source : sources) {
            bindings.add(binding(source, delivery, correlationFactory));
        }
        return bindings;
    }

    private static <T> KafkaConsumerBinding<?> binding(
            KafkaSignalSource<T> source, WorkflowSignalSourceDelivery delivery, CorrelationContextFactory factory) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) messageType(source.getClass());
        DefaultKafkaSignals<T> builder = new DefaultKafkaSignals<>(delivery, type, factory);
        source.define(builder);
        try {
            return builder.build();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(source.getClass().getName() + ": " + e.getMessage(), e);
        }
    }

    /** Reads the class's {@code KafkaSignalSource} type argument, or fails naming the class. */
    static Class<?> messageType(Class<?> sourceClass) {
        Type argument = argumentOf(sourceClass, new HashMap<>());
        if (argument instanceof Class<?> type) {
            return type;
        }
        throw new IllegalStateException(sourceClass.getName()
                + " must state the message type of KafkaSignalSource as a class in its type argument, but it is "
                + (argument == null ? "raw or unresolved" : argument.getTypeName()));
    }

    /** Walks the class's generic supertypes to {@code KafkaSignalSource}, resolving type variables on the way. */
    private static Type argumentOf(Type type, Map<TypeVariable<?>, Type> bindings) {
        Class<?> raw;
        if (type instanceof ParameterizedType parameterized) {
            raw = (Class<?>) parameterized.getRawType();
            Type[] actuals = parameterized.getActualTypeArguments();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            for (int i = 0; i < variables.length; i++) {
                Type actual = actuals[i];
                bindings.put(
                        variables[i], actual instanceof TypeVariable<?> variable ? bindings.get(variable) : actual);
            }
            if (raw == KafkaSignalSource.class) {
                return bindings.get(variables[0]);
            }
        } else if (type instanceof Class<?> plain) {
            raw = plain;
            if (raw == KafkaSignalSource.class) {
                return null;
            }
        } else {
            return null;
        }
        for (Type supertype : supertypesOf(raw)) {
            Type found = argumentOf(supertype, bindings);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Set<Type> supertypesOf(Class<?> type) {
        Set<Type> supertypes = new LinkedHashSet<>();
        if (type.getGenericSuperclass() != null) {
            supertypes.add(type.getGenericSuperclass());
        }
        supertypes.addAll(List.of(type.getGenericInterfaces()));
        return supertypes;
    }
}
