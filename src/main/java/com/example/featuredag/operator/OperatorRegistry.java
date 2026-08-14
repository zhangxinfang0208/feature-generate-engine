package com.example.featuredag.operator;

import com.example.featuredag.operator.builtin.InitialBusinessOperators;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 算子定义注册表，同时服务于逻辑推断、单值执行与批量执行。
 *
 * <p>注册表只负责装配、查找、参数数量校验和执行内核路由；每个标准算子的元数据、
 * 推断与求值实现位于 {@code operator.builtin} 包中的独立 Java 类。
 */
public final class OperatorRegistry {
    private final Map<String, OperatorDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, BatchOperatorKernel> batchKernels = new ConcurrentHashMap<>();
    private final Map<String, BatchOperatorKernel> scalarBatchAdapters = new ConcurrentHashMap<>();

    public OperatorRegistry register(OperatorDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        validateParameterNames(definition);
        OperatorDefinition previous = definitions.putIfAbsent(definition.name(), definition);
        if (previous != null) {
            throw new IllegalArgumentException("Operator already registered: " + definition.name());
        }
        BatchOperatorKernel scalarAdapter = new SingleLoopBatchOperatorKernel(definition);
        scalarBatchAdapters.put(definition.name(), scalarAdapter);
        batchKernels.put(
                definition.name(),
                definition instanceof BatchOperatorKernel nativeBatch ? nativeBatch : scalarAdapter);
        return this;
    }

    public OperatorDefinition require(String name) {
        OperatorDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown operator: " + name);
        }
        return definition;
    }

    public Optional<OperatorDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public OperatorInference infer(
            String name,
            List<? extends OperatorInputMetadata> inputs) {
        OperatorDefinition definition = require(name);
        validateArity(definition, inputs.size());
        return definition.infer(List.copyOf(inputs));
    }

    public Object evaluate(String name, List<Object> arguments) {
        OperatorDefinition definition = require(name);
        validateArity(definition, arguments.size());
        return definition.evaluate(arguments);
    }

    public BatchOperatorResult evaluateBatch(String name, BatchOperatorCall call) {
        return evaluateBatch(name, call, batchKernelKind(name));
    }

    public BatchOperatorResult evaluateBatch(
            String name,
            BatchOperatorCall call,
            BatchKernelKind plannedKind) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(plannedKind, "plannedKind");
        OperatorDefinition definition = require(name);
        validateArity(definition, call.arguments().size());
        BatchOperatorKernel registered = batchKernel(definition.name());
        BatchOperatorKernel kernel;
        if (plannedKind == BatchKernelKind.SCALAR_ADAPTER) {
            kernel = scalarBatchAdapter(name);
        } else {
            if (registered.batchKernelKind() != BatchKernelKind.NATIVE) {
                throw new IllegalStateException(
                        "Physical plan requires native Batch kernel for operator " + name);
            }
            kernel = registered;
        }
        BatchOperatorResult result = kernel.evaluateBatch(call);
        if (result.values().size() != call.rowCount()) {
            throw new IllegalStateException(
                    "Batch operator " + name + " returned " + result.values().size()
                            + " rows, expected " + call.rowCount());
        }
        return result;
    }

    public BatchKernelKind batchKernelKind(String name) {
        require(name);
        return batchKernel(name).batchKernelKind();
    }

    public <T extends OperatorSemantic> Optional<T> semantic(
            String operatorName,
            Class<T> semanticType) {
        Objects.requireNonNull(semanticType, "semanticType");
        OperatorDefinition definition = definitions.get(operatorName);
        if (definition == null) return Optional.empty();
        return definition.semantics().stream()
                .filter(semanticType::isInstance)
                .map(semanticType::cast)
                .findFirst();
    }

    public static OperatorRegistry standard() {
        OperatorRegistry registry = new OperatorRegistry();
        for (OperatorDefinition definition : InitialBusinessOperators.definitions()) {
            registry.register(definition);
        }
        return registry;
    }

    private BatchOperatorKernel batchKernel(String name) {
        BatchOperatorKernel kernel = batchKernels.get(name);
        if (kernel == null) {
            throw new IllegalStateException("Missing batch kernel for operator: " + name);
        }
        return kernel;
    }

    private BatchOperatorKernel scalarBatchAdapter(String name) {
        BatchOperatorKernel kernel = scalarBatchAdapters.get(name);
        if (kernel == null) {
            throw new IllegalStateException("Missing scalar Batch adapter for operator: " + name);
        }
        return kernel;
    }

    private static void validateArity(OperatorDefinition definition, int count) {
        if (count < definition.minArguments() || count > definition.maxArguments()) {
            throw new IllegalArgumentException(
                    "Operator " + definition.name() + " expects " + definition.minArguments()
                            + ".." + definition.maxArguments() + " arguments, got " + count);
        }
    }

    private static void validateParameterNames(OperatorDefinition definition) {
        List<String> parameterNames = Objects.requireNonNull(
                definition.parameterNames(),
                "parameterNames for operator " + definition.name());
        if (parameterNames.isEmpty()) return;
        if (parameterNames.size() != definition.maxArguments()) {
            throw new IllegalArgumentException(
                    "Operator " + definition.name() + " declares " + parameterNames.size()
                            + " parameter names but maxArguments is " + definition.maxArguments());
        }
        HashSet<String> uniqueNames = new HashSet<>();
        for (String parameterName : parameterNames) {
            if (parameterName == null || parameterName.isBlank()) {
                throw new IllegalArgumentException(
                        "Operator " + definition.name() + " has a blank parameter name");
            }
            if (!uniqueNames.add(parameterName)) {
                throw new IllegalArgumentException(
                        "Operator " + definition.name()
                                + " has duplicate parameter name: " + parameterName);
            }
        }
    }
}
