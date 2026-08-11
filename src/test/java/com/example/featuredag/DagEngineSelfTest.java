package com.example.featuredag;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.FeatureDagInitializationException;
import com.example.featuredag.api.FeatureGenerationException;
import com.example.featuredag.api.FeatureValueCodecSelfTest;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.api.OnlineBatchGenerateRequest;
import com.example.featuredag.api.OnlineBatchGenerateResult;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.api.OnlineRequestGroup;
import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.demo.ExampleFeatures;
import com.example.featuredag.demo.OfflineBatchDemo;
import com.example.featuredag.demo.OnlineGroupedBatchDemo;
import com.example.featuredag.expression.AstArrayLiteral;
import com.example.featuredag.expression.AstCall;
import com.example.featuredag.expression.AstFeatureRef;
import com.example.featuredag.expression.AstLiteral;
import com.example.featuredag.expression.AstNode;
import com.example.featuredag.expression.AstObjectLiteral;
import com.example.featuredag.expression.ExpressionParseException;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.DagBuildException;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.LiteralNode;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchDomain;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchLayout;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorEvaluationException;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.KeyedSequenceFilterSemantic;
import com.example.featuredag.operator.SequenceCardinalitySemantic;
import com.example.featuredag.operator.SequenceKeyDomains;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalExecutorIds;
import com.example.featuredag.physical.OperatorInvocationPolicy;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanPrinter;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.CandidateBatchValue;
import com.example.featuredag.runtime.CandidateVectorValue;
import com.example.featuredag.runtime.BitmapSelection;
import com.example.featuredag.runtime.AsyncObserverStats;
import com.example.featuredag.runtime.AsyncRuntimeObserver;
import com.example.featuredag.runtime.CacheKind;
import com.example.featuredag.runtime.CacheStats;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionDiagnostics;
import com.example.featuredag.runtime.ExecutionPhase;
import com.example.featuredag.runtime.ExecutionResult;
import com.example.featuredag.runtime.ExecutionStatus;
import com.example.featuredag.runtime.InMemoryRuntimeObserver;
import com.example.featuredag.runtime.IndexSelection;
import com.example.featuredag.runtime.ListSequenceValue;
import com.example.featuredag.runtime.ObservabilityOptions;
import com.example.featuredag.runtime.ObservationDetailLevel;
import com.example.featuredag.runtime.OperatorInvocationKind;
import com.example.featuredag.runtime.RangeSelection;
import com.example.featuredag.runtime.RuntimeObservabilityController;
import com.example.featuredag.runtime.ScalarValue;
import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceEvent;
import com.example.featuredag.runtime.SequenceValue;
import com.example.featuredag.runtime.SequenceView;
import com.example.featuredag.runtime.ValueHandle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Dependency-free self test. Run with java -ea. */
public final class DagEngineSelfTest {
    public static void main(String[] args) throws Exception {
        FeatureValueCodecSelfTest.run();
        testExtendedExpressionParsing();
        testCanonicalNodeDeduplication();
        testCurriedInvocationValidation();
        testArrayLiteralDagConstruction();
        testOperatorTypeRuntimeConsistency();
        testSingleAndNativeBatchOperatorDispatch();
        testStandardNativeBatchMatchesSingle();
        testNullArrayLiteralDagConstruction();
        testObjectListsRemainScalarAtRuntime();
        testAlignedPlainListSequenceRuntime();
        testIndependentRawListSequenceLengthsAreAllowed();
        testWindowSequenceOperatorEvaluation();
        testThreeDayAppCountFromAlignedLists();
        testLiteralCanonicalizationSeparatesTypesAndBoundaries();
        testDiscreteFeatureDagConstruction();
        testArrayLiteralDisabledFeatureReferenceValidation();
        testOperatorRegistryConcurrentRegistration();
        testInitialBusinessOperatorRegistry();
        testInitialBusinessOperatorExpressionsBuildAndInfer();
        testBusinessJsonParsing();
        testUnifiedFeatureJsonParsing();
        testLegacyDerivedFeaturesRejected();
        testIntermediateFeatureMapping();
        testOfflinePublicApi();
        testOfflineBatchPublicApi();
        testBatchDemos();
        testConfigPathInit();
        testOfflineSequenceMaterialization();
        testEventSequencePublicApi();
        testOnlinePublicApi();
        testOnlineBatchPublicApi();
        testRuntimeObservability();
        testRuntimeObservabilityCoversBatchAndFailure();
        testRuntimeObservabilityControls();
        testAsyncRuntimeObserver();
        testRuntimeObserverFailureIsolation();
        testOnlineBaseMetadataDefaults();
        testOnlineSharedArrayOutput();
        testOnlineEngineConcurrentReuse();
        testConfigurationAndRequestValidation();
        testFailFastArrayOutput();
        testBaseOutputPolicyIsValidated();
        testDerivedOutputPolicyDefaults();
        testDeclaredScopeIsValidatedBeforeOverride();
        testDeclaredValueShapeAndScopeSemantics();
        testBaseMetadataDefaultBoundaries();
        testDagBusinessSemantics();
        testExecutionStagesAndTargetSelection();
        testCandidateVectorPreservesNullElements();
        testCandidateCardinalityAndDefaults();
        testEmptySequenceAndOfflineOutputSet();
        testCandidateDeduplicationAndFusion();
        testOnlineBatchSpecializedGrouping();
        testDirectNestedCountIndustryFusion();
        testObservableExtractIndustryPreventsFusion();
        testFusedIndustryCountsRespectSequenceViews();
        testFusionMatchesRegisteredSemanticsInsteadOfOperatorNames();
        testGenericCandidateCacheUsesOperatorTraits();
        testSequenceSelectionStrategies();
        testOfflineOnlineConsistency();

        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
        PhysicalPlanner planner = new PhysicalPlanner();
        DagRuntime runtime = new DagRuntime(operators);

        LogicalDag onlineDag = builder.build(ExampleFeatures.definitions(), ExampleFeatures.onlineTargets());
        PhysicalPlan onlinePlan = planner.plan(
                optimizer.analyze(onlineDag), ExecutionEnvironment.ONLINE, "online-test");
        assert onlineDag.featureOutputNodeIds().containsKey("user_click_score");
        assert onlineDag.featureOutputNodeIds().containsKey("item_price_log");
        assert !onlineDag.featureOutputNodeIds().containsKey("unused_feature");
        assert onlinePlan.nodes().stream().anyMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "Online plan should fuse extractIndustry + count";

        SequenceBlock sequence = sequence();
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("user_click_count", 10);
        shared.put("user_seq1", sequence);
        List<Map<String, Object>> candidates = List.of(
                Map.of("item_industry", "industry1", "item_price", 100.0),
                Map.of("item_industry", "industry2", "item_price", 50.0),
                Map.of("item_industry", "industry1", "item_price", 80.0));
        ExecutionResult onlineResult = runtime.execute(
                onlinePlan, ExecutionContext.onlineRequest("request-test", shared, candidates));
        CandidateVectorValue counts = (CandidateVectorValue) onlineResult.feature("same_industry_count");
        assert counts.values().equals(List.of(3, 1, 3)) : counts.values();
        assert onlineResult.nodeStates().values().stream()
                .anyMatch(state -> state.dedupInputCount() == 3 && state.uniqueInputCount() == 2)
                : "Expected candidate industry dedup 3 -> 2";

        LogicalDag offlineDag = builder.build(ExampleFeatures.definitions(), ExampleFeatures.transformTargets());
        PhysicalPlan offlinePlan = planner.plan(
                optimizer.analyze(offlineDag), ExecutionEnvironment.OFFLINE, "offline-test");
        assert offlinePlan.nodes().stream().noneMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "Transform must retain same_industry_seq output";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_click_count", 10);
        row.put("user_seq1", sequence);
        row.put("item_industry", "industry1");
        row.put("item_price", 100.0);
        ExecutionResult offlineResult = runtime.execute(
                offlinePlan, ExecutionContext.offlineRow("row-test", row));
        SequenceView view = (SequenceView) offlineResult.feature("same_industry_seq");
        assert view.size() == 3;
        assert view.baseBlock() == sequence : "SequenceView must share the original SequenceBlock";
        assert ((Number) offlineResult.feature("same_industry_count").raw()).intValue() == 3;

        System.out.println("All DAG engine self tests passed.");
    }

    private static void testExtendedExpressionParsing() {
        ExpressionParser parser = new ExpressionParser();
        AstCall discrete = (AstCall) parser.parse("discrete(a, [1, 10, 100])");
        AstArrayLiteral points = (AstArrayLiteral) discrete.arguments().get(1);
        assert points.elements().stream()
                .map(AstLiteral.class::cast)
                .map(AstLiteral::value)
                .toList().equals(List.of(1, 10, 100));

        assert ((AstLiteral) parser.parse("42")).value() instanceof Integer;
        assert ((AstLiteral) parser.parse("3.14")).value() instanceof Double;
        expectThrows(ExpressionParseException.class, () -> parser.parse("[1, 2"));
        expectThrows(ExpressionParseException.class, () -> parser.parse("f(a,)"));

        String longInvalidExpression = "coalesce(" + "a".repeat(10_000) + ", @)";
        ExpressionParseException longError = expectThrows(
                ExpressionParseException.class,
                () -> parser.parse(longInvalidExpression));
        assert longError.getMessage().length() < 300 : longError.getMessage().length();
        assert longError.getMessage().contains("offset") : longError.getMessage();
        ExpressionParseException longNumberError = expectThrows(
                ExpressionParseException.class,
                () -> parser.parse("9".repeat(10_000)));
        assert longNumberError.getMessage().length() < 300
                : longNumberError.getMessage().length();
    }

    private static void testCanonicalNodeDeduplication() {
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard()).build(
                List.of(
                        FeatureDefinition.raw("raw", DataType.INT, EntityScope.USER, 0),
                        FeatureDefinition.derived(
                                "first", DataType.DOUBLE, "add(raw, 1)", OutputPolicy.OUTPUT),
                        FeatureDefinition.derived(
                                "second", DataType.DOUBLE, "add(raw, 1)", OutputPolicy.OUTPUT)),
                linkedSet("first", "second"));

        assert dag.nodes().values().stream()
                .filter(node -> node instanceof com.example.featuredag.logical.SourceNode)
                .count() == 1 : dag.nodes();
        assert dag.nodes().values().stream()
                .filter(node -> node instanceof LiteralNode)
                .count() == 1 : dag.nodes();
        assert countOperatorNodes(dag, "add") == 1 : dag.nodes();
        assert dag.featureOutputNodeIds().keySet().equals(linkedSet("first", "raw", "second"))
                : dag.featureOutputNodeIds();
    }

    private static void testCurriedInvocationValidation() {
        DagBuildException error = expectThrows(
                DagBuildException.class,
                () -> new LogicalDagBuilder(
                        new ExpressionParser(), OperatorRegistry.standard()).build(
                        List.of(
                                FeatureDefinition.raw(
                                        "source", DataType.INT, EntityScope.USER, 0),
                                FeatureDefinition.derived(
                                        "invalid_curried",
                                        DataType.INT,
                                        "coalesce(source)(0)",
                                        OutputPolicy.OUTPUT)),
                        Set.of("invalid_curried")));
        assert error.getMessage().contains("does not support chained invocation")
                : error.getMessage();
    }

    private static long countOperatorNodes(LogicalDag dag, String operatorName) {
        return dag.nodes().values().stream()
                .filter(OperatorNode.class::isInstance)
                .map(OperatorNode.class::cast)
                .filter(node -> node.operatorName().equals(operatorName))
                .count();
    }

    private static void assertCallArities(AstNode node, OperatorRegistry registry) {
        if (node instanceof AstCall call) {
            OperatorDefinition definition = registry.require(call.functionName());
            int argumentCount = call.arguments().size();
            assert argumentCount >= definition.minArguments()
                    && argumentCount <= definition.maxArguments()
                    : call.functionName() + " arity=" + argumentCount + ", expected="
                    + definition.minArguments() + ".." + definition.maxArguments();
            for (AstNode argument : call.arguments()) {
                assertCallArities(argument, registry);
            }
        } else if (node instanceof AstObjectLiteral objectLiteral) {
            for (AstNode value : objectLiteral.fields().values()) {
                assertCallArities(value, registry);
            }
        } else if (node instanceof AstArrayLiteral arrayLiteral) {
            for (AstNode element : arrayLiteral.elements()) {
                assertCallArities(element, registry);
            }
        }
    }

    private static void testArrayLiteralDagConstruction() {
        OperatorRegistry registry = new OperatorRegistry().register(new OperatorDefinition() {
            public String name() { return "arrayProbe"; }
            public int minArguments() { return 2; }
            public int maxArguments() { return 2; }
            public boolean deterministic() { return true; }
            public boolean parameterized() { return true; }
            public boolean supportsSequenceView() { return false; }
            public OperatorInference infer(List<OperatorInputMetadata> inputs) {
                assert inputs.get(1) instanceof LiteralNode;
                LiteralNode array = (LiteralNode) inputs.get(1);
                assert array.value().equals(List.of(1, 10, 100));
                assert array.outputType() == DataType.OBJECT;
                assert array.valueShape() == ValueShape.OBJECT;
                return new OperatorInference(DataType.INT, Set.of(EntityScope.ITEM), ValueShape.SCALAR);
            }
            public Object evaluate(List<Object> arguments) { return 0; }
        });

        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw("a", DataType.INT, EntityScope.ITEM, 0),
                        FeatureDefinition.derived(
                                "bucket", DataType.INT,
                                "arrayProbe(a, [1, 10, 100])", OutputPolicy.OUTPUT)),
                Set.of("bucket"));

        assert dag.featureOutputNodeIds().containsKey("bucket") : dag.featureOutputNodeIds();
    }

    private static void testOperatorTypeRuntimeConsistency() {
        OperatorRegistry standard = OperatorRegistry.standard();
        Object mixedLeast = standard.evaluate("least", List.of(1, 2.5));
        assert mixedLeast instanceof Double : mixedLeast.getClass();
        assert ((Double) mixedLeast) == 1.0 : mixedLeast;
        Object integerLeast = standard.evaluate("least", List.of(2, 1));
        assert integerLeast instanceof Integer : integerLeast.getClass();
        assert integerLeast.equals(1) : integerLeast;

        LogicalDag countDag = new LogicalDagBuilder(
                new ExpressionParser(), standard).build(
                List.of(FeatureDefinition.derived(
                        "literal_count",
                        DataType.INT,
                        "count([1, 2, 3])",
                        OutputPolicy.OUTPUT)),
                Set.of("literal_count"));
        PhysicalPlan countPlan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(countDag),
                ExecutionEnvironment.OFFLINE,
                "literal-count");
        ExecutionResult countResult = new DagRuntime(standard).execute(
                countPlan,
                ExecutionContext.offlineRow("literal-count-row", Map.of()));
        assert countResult.feature("literal_count").raw().equals(3)
                : countResult.featureOutputs();

        AssertionError marker = new AssertionError("fatal-marker");
        OperatorRegistry fatalRegistry = new OperatorRegistry().register(new OperatorDefinition() {
            public String name() { return "fatal"; }
            public int minArguments() { return 1; }
            public int maxArguments() { return 1; }
            public boolean deterministic() { return true; }
            public boolean parameterized() { return false; }
            public boolean supportsSequenceView() { return false; }
            public OperatorInference infer(List<OperatorInputMetadata> inputs) {
                return new OperatorInference(DataType.INT, Set.of(), ValueShape.SCALAR);
            }
            public Object evaluate(List<Object> arguments) { throw marker; }
        });
        LogicalDag fatalDag = new LogicalDagBuilder(
                new ExpressionParser(), fatalRegistry).build(
                List.of(FeatureDefinition.derived(
                        "fatal_output", DataType.INT, "fatal(1)", OutputPolicy.OUTPUT)),
                Set.of("fatal_output"));
        PhysicalPlan fatalPlan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(fatalDag),
                ExecutionEnvironment.OFFLINE,
                "fatal-error");
        AssertionError propagated = expectThrows(
                AssertionError.class,
                () -> new DagRuntime(fatalRegistry).execute(
                        fatalPlan,
                        ExecutionContext.offlineRow("fatal-error-row", Map.of())));
        assert propagated == marker : propagated;
    }

    private static void testSingleAndNativeBatchOperatorDispatch() {
        AtomicInteger singleCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        OperatorRegistry registry = new OperatorRegistry().register(
                new NativeBatchProbeOperator(singleCalls, batchCalls));
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw(
                        "batch_user", DataType.STRING, EntityScope.USER, null),
                FeatureDefinition.raw(
                        "batch_item", DataType.STRING, EntityScope.ITEM, null),
                FeatureDefinition.derived(
                        "batch_output",
                        DataType.STRING,
                        "native_batch_probe(batch_user, batch_item)",
                        OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                definitions, Set.of("batch_output"));
        PhysicalPlan onlinePlan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.ONLINE,
                "native-batch-dispatch");
        PhysicalNode operatorNode = onlinePlan.nodes().stream()
                .filter(node -> "native_batch_probe".equals(
                        node.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        assert operatorNode.executorConfig().get("batchKernelKind")
                .equals(BatchKernelKind.NATIVE.name())
                : PhysicalPlanPrinter.print(onlinePlan);
        assert operatorNode.executorConfig().get("invocationPolicy")
                == OperatorInvocationPolicy.SINGLE_OR_BATCH_BY_INPUT_DOMAIN;

        DagRuntime runtime = new DagRuntime(registry);
        ExecutionResult grouped = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineBatch(
                        "native-batch-groups",
                        List.of("group-a", "group-b"),
                        List.of(
                                Map.of("batch_user", "A"),
                                Map.of("batch_user", "B")),
                        List.of(
                                List.of(
                                        Map.of("batch_item", "x"),
                                        Map.of("batch_item", "y")),
                                List.of(Map.of("batch_item", "z")))));
        CandidateBatchValue groupedValues =
                (CandidateBatchValue) grouped.feature("batch_output");
        assert groupedValues.values().equals(List.of("A:x", "A:y", "B:z"))
                : groupedValues.values();
        assert batchCalls.get() == 1 : batchCalls.get();
        assert singleCalls.get() == 0 : singleCalls.get();
        var groupedState = grouped.nodeStates().get(operatorNode.physicalNodeId());
        assert groupedState.operatorInvocationKind()
                == OperatorInvocationKind.BATCH_NATIVE : groupedState.operatorInvocationKind();
        assert groupedState.batchDomain() == BatchDomain.ONLINE_CANDIDATE
                : groupedState.batchDomain();
        assert groupedState.batchRowCount() == 3 : groupedState.batchRowCount();

        ExecutionResult empty = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineBatch(
                        "native-batch-empty", List.of(), List.of(), List.of()));
        assert ((CandidateBatchValue) empty.feature("batch_output")).values().isEmpty();
        assert batchCalls.get() == 2 : "Empty Batch must invoke the Batch contract";
        assert singleCalls.get() == 0;
        var emptyState = empty.nodeStates().get(operatorNode.physicalNodeId());
        assert emptyState.operatorInvocationKind() == OperatorInvocationKind.BATCH_NATIVE;
        assert emptyState.batchDomain() == BatchDomain.ONLINE_CANDIDATE;
        assert emptyState.batchRowCount() == 0 : emptyState.batchRowCount();

        ExecutionResult singleRequest = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineRequest(
                        "native-batch-single-request",
                        Map.of("batch_user", "S"),
                        List.of(
                                Map.of("batch_item", "p"),
                                Map.of("batch_item", "q"))));
        assert ((CandidateVectorValue) singleRequest.feature("batch_output"))
                .values().equals(List.of("S:p", "S:q"));
        assert batchCalls.get() == 3 : batchCalls.get();
        assert singleCalls.get() == 0;
        var singleRequestState = singleRequest.nodeStates().get(operatorNode.physicalNodeId());
        assert singleRequestState.operatorInvocationKind()
                == OperatorInvocationKind.BATCH_NATIVE;
        assert singleRequestState.batchDomain() == BatchDomain.ONLINE_CANDIDATE;
        assert singleRequestState.batchRowCount() == 2 : singleRequestState.batchRowCount();

        PhysicalPlan offlinePlan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "single-dispatch");
        ExecutionResult scalar = runtime.execute(
                offlinePlan,
                ExecutionContext.offlineRow(
                        "single-dispatch-row",
                        Map.of("batch_user", "U", "batch_item", "i")));
        assert scalar.feature("batch_output").raw().equals("U:i");
        assert singleCalls.get() == 1 : singleCalls.get();
        assert batchCalls.get() == 3 : batchCalls.get();
        PhysicalNode scalarOperatorNode = offlinePlan.nodes().stream()
                .filter(node -> "native_batch_probe".equals(
                        node.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        var scalarState = scalar.nodeStates().get(scalarOperatorNode.physicalNodeId());
        assert scalarState.operatorInvocationKind() == OperatorInvocationKind.SINGLE;
        assert scalarState.batchDomain() == null : scalarState.batchDomain();
        assert scalarState.batchRowCount() == 0 : scalarState.batchRowCount();

        IllegalArgumentException onlineBatchError = expectThrows(
                IllegalArgumentException.class,
                () -> runtime.execute(
                        onlinePlan,
                        ExecutionContext.onlineBatch(
                                "native-batch-online-error",
                                List.of("good-group", "bad-group"),
                                List.of(
                                        Map.of("batch_user", "G"),
                                        Map.of("batch_user", "B")),
                                List.of(
                                        List.of(Map.of("batch_item", "ok")),
                                        List.of(Map.of("batch_item", "bad"))))));
        assert onlineBatchError.getMessage().contains("online batch group 1 (bad-group)")
                : onlineBatchError.getMessage();
        assert onlineBatchError.getMessage().contains("candidate 0")
                : onlineBatchError.getMessage();
        assert batchCalls.get() == 4 : batchCalls.get();

        OperatorRegistry standard = OperatorRegistry.standard();
        LogicalDag errorDag = new LogicalDagBuilder(new ExpressionParser(), standard).build(
                List.of(
                        FeatureDefinition.raw(
                                "numerator", DataType.DOUBLE, EntityScope.ITEM, null),
                        FeatureDefinition.raw(
                                "denominator", DataType.DOUBLE, EntityScope.ITEM, null),
                        FeatureDefinition.derived(
                                "ratio", DataType.DOUBLE,
                                "div(numerator, denominator)", OutputPolicy.OUTPUT)),
                Set.of("ratio"));
        PhysicalPlan errorPlan = new PhysicalPlanner(standard).plan(
                new LogicalDagOptimizer(standard).analyze(errorDag),
                ExecutionEnvironment.OFFLINE,
                "native-batch-error-location");
        IllegalArgumentException error = expectThrows(
                IllegalArgumentException.class,
                () -> new DagRuntime(standard).execute(
                        errorPlan,
                        ExecutionContext.offlineBatch(
                                "native-batch-error",
                                List.of(
                                        Map.of("numerator", 8.0, "denominator", 2.0),
                                        Map.of("numerator", 3.0, "denominator", 0.0)))));
        assert error.getMessage().contains("offline batch row 1") : error.getMessage();
        assert error.getMessage().contains("divisor must not be zero") : error.getMessage();
    }

    private static void testStandardNativeBatchMatchesSingle() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<NativeBatchCase> cases = List.of(
                new NativeBatchCase(
                        "count",
                        List.of(
                                List.of((Object) List.of(1, 2, 3)),
                                List.of((Object) List.of()))),
                new NativeBatchCase(
                        "add",
                        List.of(List.of(1, 2), List.of(2.5, 3), List.of(-4, 1))),
                new NativeBatchCase(
                        "log",
                        List.of(List.of(1), List.of(Math.E), List.of(10))),
                new NativeBatchCase(
                        "multiply",
                        List.of(List.of(2, 3), List.of(-4, 0.5), List.of(0, 9))),
                new NativeBatchCase(
                        "div_num",
                        List.of(
                                List.of(9, Map.of("divisor", 2)),
                                List.of(5, Map.of("divisor", 4)))),
                new NativeBatchCase(
                        "round",
                        List.of(List.of(4.4), List.of(4.6), List.of(-1.6))),
                new NativeBatchCase(
                        "div",
                        List.of(List.of(9, 2), List.of(-3, 4), List.of(1, 8))),
                new NativeBatchCase(
                        "least",
                        List.of(List.of(3, 5, 1), List.of(2.5, 3, 4), List.of(-1, 0, 8))));

        for (NativeBatchCase batchCase : cases) {
            assert registry.batchKernelKind(batchCase.operatorName()) == BatchKernelKind.NATIVE
                    : batchCase.operatorName();
            int arity = batchCase.rows().getFirst().size();
            List<BatchColumn> columns = new ArrayList<>(arity);
            for (int argumentIndex = 0; argumentIndex < arity; argumentIndex++) {
                List<Object> values = new ArrayList<>(batchCase.rows().size());
                for (List<Object> row : batchCase.rows()) {
                    values.add(row.get(argumentIndex));
                }
                columns.add(new ListBatchColumn(values));
            }
            BatchOperatorResult batch = registry.evaluateBatch(
                    batchCase.operatorName(),
                    new BatchOperatorCall(
                            new TestBatchLayout(batchCase.rows().size()), columns),
                    BatchKernelKind.NATIVE);
            for (int rowIndex = 0; rowIndex < batchCase.rows().size(); rowIndex++) {
                Object single = registry.evaluate(
                        batchCase.operatorName(), batchCase.rows().get(rowIndex));
                Object batchValue = batch.values().valueAt(rowIndex);
                assert Objects.equals(single, batchValue)
                        : batchCase.operatorName() + " row=" + rowIndex
                                + ", single=" + single + ", batch=" + batchValue;
            }
        }

        for (String adapterOperator : List.of(
                "discrete",
                "log_base",
                "slice_by_indices",
                "find_indices",
                "get_seq_length",
                "count_distinct",
                "calc_delta_seq",
                "zip_concat")) {
            assert registry.batchKernelKind(adapterOperator) == BatchKernelKind.SCALAR_ADAPTER
                    : adapterOperator;
        }
    }

    private static void testNullArrayLiteralDagConstruction() {
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard()).build(
                List.of(FeatureDefinition.derived(
                        "null_array", DataType.OBJECT,
                        "[null, [null], {\"nested\": [null]}]", OutputPolicy.OUTPUT)),
                Set.of("null_array"));
        LiteralNode literal = (LiteralNode) dag.node(
                dag.featureOutput("null_array").producerNodeId());
        List<?> values = (List<?>) literal.value();

        assert values.size() == 3 : values;
        assert values.get(0) == null : values;
        assert ((List<?>) values.get(1)).size() == 1;
        assert ((List<?>) values.get(1)).getFirst() == null;
        Map<?, ?> object = (Map<?, ?>) values.get(2);
        assert ((List<?>) object.get("nested")).getFirst() == null : object;
        expectThrows(UnsupportedOperationException.class, () -> clearList(values));
        expectThrows(
                UnsupportedOperationException.class,
                () -> clearList((List<?>) values.get(1)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void clearList(List<?> values) {
        ((List) values).clear();
    }

    private static void testAlignedPlainListSequenceRuntime() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "apps", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "timestamps", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.derived(
                                "event_count", DataType.INT, "count(apps)", OutputPolicy.OUTPUT)),
                linkedSet("apps", "timestamps", "event_count"));
        PhysicalPlan plan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "aligned-list-sequences");
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "aligned-row",
                        Map.of(
                                "apps", List.of("app0", "app1"),
                                "timestamps", List.of(20L, 10L))));

        ListSequenceValue apps = (ListSequenceValue) result.feature("apps");
        ListSequenceValue timestamps = (ListSequenceValue) result.feature("timestamps");
        assert apps.values().equals(List.of("app0", "app1")) : apps.values();
        assert timestamps.values().equals(List.of(20L, 10L)) : timestamps.values();
        assert apps.alignmentId().equals("aligned-row") : apps.alignmentId();
        assert timestamps.alignmentId().equals(apps.alignmentId());
        assert ((Number) result.feature("event_count").raw()).intValue() == 2;
    }

    private static void testIndependentRawListSequenceLengthsAreAllowed() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "apps", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "timestamps", DataType.EVENT_SEQUENCE, EntityScope.USER, null)),
                linkedSet("apps", "timestamps"));
        PhysicalPlan plan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "independent-list-sequences");

        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "independent-sequences",
                        Map.of(
                                "apps", List.of("app0", "app1"),
                                "timestamps", List.of(20L))));
        assert ((ListSequenceValue) result.feature("apps")).size() == 2;
        assert ((ListSequenceValue) result.feature("timestamps")).size() == 1;
    }

    private static void testWindowSequenceOperatorEvaluation() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assert registry.evaluate(
                "greater_in_sequence_typed",
                List.of(List.of(20L, 15L, 10L), 20L, Map.of("margin", 10L)))
                .equals(List.of(0, 1));
        assert registry.evaluate(
                "greater_in_sequence_typed",
                List.of(
                        List.of(9007199254740993L),
                        9007199254740994L,
                        Map.of("margin", 2L)))
                .equals(List.of(0));
        assert registry.evaluate(
                "list_index_typed",
                List.of(List.of("app0", "app1", "app2"), List.of(2, 0, 2)))
                .equals(List.of("app2", "app0", "app2"));
        assert registry.evaluate(
                "find_list_index_typed",
                List.of(List.of("app0", "app1", "app0"), "app0"))
                .equals(List.of(0, 2));
        assert registry.evaluate(
                "greater_in_sequence_typed",
                List.of(List.of(), 20L, Map.of("margin", 10L)))
                .equals(List.of());
        assert registry.evaluate(
                "list_index_typed",
                List.of(java.util.Arrays.asList("app0", null), List.of(1)))
                .equals(java.util.Arrays.asList((Object) null));
        assert registry.evaluate(
                "find_list_index_typed",
                java.util.Arrays.asList(java.util.Arrays.asList("app0", null), null))
                .equals(List.of(1));

        IllegalArgumentException negativeMargin = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "greater_in_sequence_typed",
                        List.of(List.of(20L), 20L, Map.of("margin", -1))));
        assert negativeMargin.getMessage().contains("margin") : negativeMargin.getMessage();

        IllegalArgumentException missingMargin = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "greater_in_sequence_typed",
                        List.of(List.of(20L), 20L, Map.of())));
        assert missingMargin.getMessage().contains("margin") : missingMargin.getMessage();

        IllegalArgumentException invalidElement = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "greater_in_sequence_typed",
                        List.of(List.of("bad"), 20L, Map.of("margin", 10))));
        assert invalidElement.getMessage().contains("index 0") : invalidElement.getMessage();

        IllegalArgumentException nullElement = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "greater_in_sequence_typed",
                        List.of(java.util.Arrays.asList((Object) null), 20L, Map.of("margin", 10))));
        assert nullElement.getMessage().contains("index 0") : nullElement.getMessage();

        IllegalArgumentException outOfBounds = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "list_index_typed",
                        List.of(List.of("app0"), List.of(1))));
        assert outOfBounds.getMessage().contains("out of bounds") : outOfBounds.getMessage();

        IllegalArgumentException fractionalIndex = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "list_index_typed",
                        List.of(List.of("app0", "app1"), List.of(0.5))));
        assert fractionalIndex.getMessage().contains("position 0")
                : fractionalIndex.getMessage();

        IllegalArgumentException roundedFractionalIndex = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "list_index_typed",
                        List.of(List.of("app0", "app1"),
                                List.of(new java.math.BigDecimal("1.0000000000000000000001")))));
        assert roundedFractionalIndex.getMessage().contains("position 0")
                : roundedFractionalIndex.getMessage();

        IllegalArgumentException nonList = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "find_list_index_typed", List.of("not-a-list", "app0")));
        assert nonList.getMessage().contains("expects List") : nonList.getMessage();
    }

    private static void testThreeDayAppCountFromAlignedLists() {
        String json = """
                {
                  "features": [
                    {"name":"auid_app_time_seq","raw_name":"auid_app_time_seq",
                     "type":"STRING","definition_type":"BASE",
                     "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"timestamp","raw_name":"timestamp",
                     "type":"INT","definition_type":"BASE",
                     "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"request_time","raw_name":"request_time","type":"INT",
                     "definition_type":"BASE","entity_scopes":["SCENE"],
                     "value_shape":"SCALAR"},
                    {"name":"target_app","raw_name":"target_app","type":"STRING",
                     "definition_type":"BASE","entity_scopes":["USER"],
                     "value_shape":"SCALAR"},
                    {"name":"auid_omnichannel_paid_cnt_3d","type":"INT",
                     "definition_type":"DERIVED",
                     "expression":"count(find_list_index_typed(list_index_typed(auid_app_time_seq, greater_in_sequence_typed(timestamp, request_time, {\\"margin\\":259200})), target_app))",
                     "output_policy":"OUTPUT","entity_scopes":["USER","SCENE"],
                     "value_shape":"SCALAR"},
                    {"name":"auid_appc3_omnichannel_paid_cnt_div10_365d","type":"INT",
                     "definition_type":"DERIVED",
                     "expression":"least(round(div_num(auid_omnichannel_paid_cnt_3d, {\\"divisor\\":10})), 1000)",
                     "output_policy":"OUTPUT","entity_scopes":["USER","SCENE"],
                     "value_shape":"SCALAR"},
                    {"name":"auid_appc3_omnichannel_paid_cnt_log_365d","type":"INT",
                     "definition_type":"DERIVED",
                     "expression":"least(round(div(log(auid_omnichannel_paid_cnt_3d), log(1.1))), 1000)",
                     "output_policy":"OUTPUT","entity_scopes":["USER","SCENE"],
                     "value_shape":"SCALAR"}
                  ],
                  "feature_set_name":"three_day_app_count","version":"1"
                }
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(
                json, InitOptions.offline("three-day-app-count"));
        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "auid-aaaa",
                Map.of(
                        "auid_app_time_seq",
                        List.of("app0", "app1", "app2", "app3", "app4"),
                        "timestamp",
                        List.of(1785549653L, 1785459831L, 1785286488L, 1785203315L, 1785114236L),
                        "request_time", List.of(1785549653),
                        "target_app", List.of("app0"))));

        assert result.featureValues().get("auid_omnichannel_paid_cnt_3d").equals(List.of(1))
                : result.featureValues();
        assert result.featureValues().get("auid_appc3_omnichannel_paid_cnt_div10_365d")
                .equals(List.of(0)) : result.featureValues();
        assert result.featureValues().get("auid_appc3_omnichannel_paid_cnt_log_365d")
                .equals(List.of(0)) : result.featureValues();
    }

    private static void testObjectListsRemainScalarAtRuntime() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "object_source", DataType.OBJECT, EntityScope.USER, List.of()),
                        FeatureDefinition.derived(
                                "object_literal", DataType.OBJECT,
                                "coalesce([1, \"two\"], null)", OutputPolicy.OUTPUT),
                        FeatureDefinition.derived(
                                "null_object_literal", DataType.OBJECT,
                                "coalesce([null, [null]], null)", OutputPolicy.OUTPUT)),
                linkedSet("object_source", "object_literal", "null_object_literal"));
        PhysicalPlan plan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "object-list-runtime-shape");
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "object-list-row", Map.of("object_source", List.of("x", "y"))));

        ValueHandle source = result.feature("object_source");
        ValueHandle literal = result.feature("object_literal");
        assert source instanceof ScalarValue : source.getClass();
        assert source.raw().equals(List.of("x", "y")) : source.raw();
        assert literal instanceof ScalarValue : literal.getClass();
        assert literal.raw().equals(List.of(1, "two")) : literal.raw();
        ValueHandle nullLiteral = result.feature("null_object_literal");
        assert nullLiteral instanceof ScalarValue : nullLiteral.getClass();
        List<?> nullValues = (List<?>) nullLiteral.raw();
        assert nullValues.getFirst() == null : nullValues;
        assert ((List<?>) nullValues.get(1)).getFirst() == null : nullValues;
    }

    private static void testLiteralCanonicalizationSeparatesTypesAndBoundaries() {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.derived(
                        "integer_list", DataType.OBJECT, "[1]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "string_list", DataType.OBJECT, "[\"1\"]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "boolean_list", DataType.OBJECT, "[true]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "boolean_string_list", DataType.OBJECT, "[\"true\"]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "comma_single", DataType.OBJECT, "[\"a,b\"]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "comma_split", DataType.OBJECT, "[\"a\", \"b\"]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "map_key_delimiter", DataType.OBJECT,
                        "{\"a=b\": \"c\"}", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "map_value_delimiter", DataType.OBJECT,
                        "{\"a\": \"b=c\"}", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "nested_integer_list", DataType.OBJECT, "[[1]]", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "nested_string_list", DataType.OBJECT, "[[\"1\"]]", OutputPolicy.OUTPUT));
        Set<String> targets = definitions.stream()
                .map(FeatureDefinition::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard()).build(definitions, targets);
        Set<String> producerNodeIds = targets.stream()
                .map(dag::featureOutput)
                .map(output -> output.producerNodeId())
                .collect(java.util.stream.Collectors.toSet());

        assert producerNodeIds.size() == definitions.size()
                : "Structurally distinct literals collided: " + producerNodeIds;

        LogicalDag reorderedMapDag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard()).build(
                List.of(
                        FeatureDefinition.derived(
                                "map_order_one", DataType.OBJECT,
                                "{\"a\": 1, \"b\": 2}", OutputPolicy.OUTPUT),
                        FeatureDefinition.derived(
                                "map_order_two", DataType.OBJECT,
                                "{\"b\": 2, \"a\": 1}", OutputPolicy.OUTPUT)),
                linkedSet("map_order_one", "map_order_two"));
        assert reorderedMapDag.featureOutput("map_order_one").producerNodeId()
                .equals(reorderedMapDag.featureOutput("map_order_two").producerNodeId())
                : "Equivalent maps should retain canonical deduplication";
    }

    private static void testDiscreteFeatureDagConstruction() {
        FeatureDefinition discretePrice = FeatureDefinition.builder()
                .name("discrete_price")
                .dataType(DataType.INT)
                .addEntityScope(EntityScope.ITEM)
                .declaredValueShape(ValueShape.SCALAR)
                .expressionContent("discrete(item_price, [0, 100, 1000])")
                .outputPolicy(OutputPolicy.OUTPUT)
                .build();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard()).build(
                List.of(
                        FeatureDefinition.raw("item_price", DataType.DOUBLE, EntityScope.ITEM, 0.0),
                        discretePrice),
                Set.of("discrete_price"));

        assertOutput(dag, "discrete_price", DataType.INT, ValueShape.SCALAR);
    }

    private static void testArrayLiteralDisabledFeatureReferenceValidation() {
        FeatureSetConfig config = FeatureConfigLoader.load("""
                {
                  "features": [
                    {"name":"a","raw_name":"a","type":"INT","definition_type":"BASE","to_use":true},
                    {"name":"hidden","raw_name":"hidden","type":"INT","definition_type":"BASE","to_use":false},
                    {"name":"bucket","type":"INT","definition_type":"DERIVED",
                     "expression":"arrayProbe(a, [[hidden]])","to_use":true,"output_policy":"OUTPUT"}
                  ],
                  "feature_set_name":"array-reference-validation","version":"latest"
                }
                """);

        IllegalArgumentException error = expectThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigMapper.map(config, ExecutionEnvironment.OFFLINE, Set.of(), Map.of()));
        assert error.getMessage().contains("hidden") && error.getMessage().contains("disabled")
                : error.getMessage();
    }

    private static void testInitialBusinessOperatorRegistry() {
        List<OperatorDefinition> definitions = InitialBusinessOperators.definitions();
        Set<String> names = Set.of(
                "discrete",
                "log_base",
                "slice_by_indices",
                "find_indices",
                "get_seq_length",
                "count_distinct",
                "zip_concat",
                "calc_delta_seq");
        assert definitions.size() == 8
                : "Expected 8 initial business operators, got " + definitions.size();
        assert definitions.stream()
                .map(OperatorDefinition::name)
                .collect(java.util.stream.Collectors.toSet())
                .equals(names)
                : definitions.stream().map(OperatorDefinition::name).toList();
        assert definitions.stream()
                .map(OperatorDefinition::getClass)
                .collect(java.util.stream.Collectors.toSet())
                .size() == definitions.size()
                : "Each initial business operator must have its own implementation class";

        Map<String, List<Integer>> arities = Map.of(
                "discrete", List.of(2, 2),
                "log_base", List.of(3, 3),
                "slice_by_indices", List.of(2, 2),
                "find_indices", List.of(2, 2),
                "get_seq_length", List.of(1, 1),
                "count_distinct", List.of(1, 1),
                "zip_concat", List.of(2, Integer.MAX_VALUE),
                "calc_delta_seq", List.of(2, 2));

        OperatorRegistry registry = OperatorRegistry.standard();
        for (String name : names) {
            OperatorDefinition definition = registry.require(name);
            assert definition.minArguments() == arities.get(name).get(0)
                    : name + " min arity=" + definition.minArguments();
            assert definition.maxArguments() == arities.get(name).get(1)
                    : name + " max arity=" + definition.maxArguments();
        }

        assert registry.evaluate("discrete", List.of(16, List.of(1, 10, 100))).equals(2);
        assert Math.abs(((Number) registry.evaluate("log_base", List.of(8, 2, 1000)))
                .doubleValue() - 3.0) < 1e-9;
        assert registry.evaluate(
                "slice_by_indices",
                List.of(List.of("a1", "a2", "a3", "a4"), List.of(1, 3)))
                .equals(List.of("a2", "a4"));
        assert registry.evaluate(
                "find_indices", List.of(List.of("a1", "a2", "a3", "a3"), "a3"))
                .equals(List.of(2, 3));
        assert registry.evaluate("get_seq_length", List.of(List.of("a1", "a2", "a3")))
                .equals(3);
        assert registry.evaluate("count_distinct", List.of(List.of("a1", "a2", "a1")))
                .equals(2);
        assert registry.evaluate(
                "zip_concat",
                List.of(List.of("a1", "a2"), List.of("b1", "b2")))
                .equals(List.of("a1#b1", "a2#b2"));
        assert registry.evaluate("calc_delta_seq", List.of(List.of(2, 5, 9), 10))
                .equals(List.of(-8.0, -5.0, -1.0));

        IllegalArgumentException invalidBase = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("log_base", List.of(8, 1, 1000)));
        assert invalidBase.getMessage().contains("base") : invalidBase.getMessage();
        IllegalArgumentException unorderedBoundaries = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("discrete", List.of(16, List.of(1, 100, 10))));
        assert unorderedBoundaries.getMessage().contains("strictly increasing")
                : unorderedBoundaries.getMessage();
        IllegalArgumentException unequalZipLengths = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "zip_concat", List.of(List.of("a1"), List.of("b1", "b2"))));
        assert unequalZipLengths.getMessage().contains("equal length")
                : unequalZipLengths.getMessage();
    }

    private static void testInitialBusinessOperatorExpressionsBuildAndInfer() {
        List<BusinessOperatorCase> cases = List.of(
                new BusinessOperatorCase(
                        "discrete", "discrete(a, [1, 10, 100])",
                        DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "log_base", "log_base(a, 2, 1000)",
                        DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "slice_by_indices", "slice_by_indices(seq, [0])",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "find_indices", "find_indices(seq, a)",
                        DataType.INT, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "get_seq_length", "get_seq_length(seq)",
                        DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "count_distinct", "count_distinct(seq)",
                        DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "zip_concat", "zip_concat(seq, seq2)",
                        DataType.STRING, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "calc_delta_seq", "calc_delta_seq(seq, a)",
                        DataType.DOUBLE, ValueShape.SEQUENCE));

        OperatorRegistry registry = OperatorRegistry.standard();
        ExpressionParser parser = new ExpressionParser();
        List<FeatureDefinition> definitions = new ArrayList<>(List.of(
                FeatureDefinition.raw("a", DataType.DOUBLE, EntityScope.ITEM, 1.0),
                FeatureDefinition.raw("seq", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("seq2", DataType.EVENT_SEQUENCE, EntityScope.ITEM, null)));
        Set<String> targets = new LinkedHashSet<>();
        Map<String, BusinessOperatorCase> casesByFeature = new LinkedHashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            BusinessOperatorCase operatorCase = cases.get(index);
            AstCall parsed = (AstCall) parser.parse(operatorCase.expression());
            assert parsed.functionName().equals(operatorCase.operatorName())
                    : operatorCase.expression();
            assertCallArities(parsed, registry);

            String featureName = "initial_business_operator_" + (index + 1);
            definitions.add(FeatureDefinition.builder()
                    .name(featureName)
                    .dataType(DataType.UNKNOWN)
                    .expressionContent(operatorCase.expression())
                    .outputPolicy(OutputPolicy.OUTPUT)
                    .build());
            targets.add(featureName);
            casesByFeature.put(featureName, operatorCase);
        }

        LogicalDag dag = new LogicalDagBuilder(parser, registry).build(definitions, targets);
        assert casesByFeature.size() == 8 : casesByFeature.keySet();
        for (Map.Entry<String, BusinessOperatorCase> entry : casesByFeature.entrySet()) {
            BusinessOperatorCase operatorCase = entry.getValue();
            assertOutput(
                    dag,
                    entry.getKey(),
                    operatorCase.outputType(),
                    operatorCase.valueShape());
        }
    }

    private record BusinessOperatorCase(
            String operatorName,
            String expression,
            DataType outputType,
            ValueShape valueShape) {}

    private static void assertOutput(
            LogicalDag dag,
            String featureName,
            DataType outputType,
            ValueShape valueShape) {
        assert dag.featureOutput(featureName).outputType() == outputType
                : featureName + " type=" + dag.featureOutput(featureName).outputType();
        assert dag.featureOutput(featureName).valueShape() == valueShape
                : featureName + " shape=" + dag.featureOutput(featureName).valueShape();
    }

    private static void testBusinessJsonParsing() {
        String json = """
                {
                  "features": [{
                    "catalog": "/mock/dir",
                    "name": "price_type1",
                    "raw_name": "price_type",
                    "store_name": "price_type1",
                    "type": "STRING",
                    "feature_type": "sparse",
                    "dft": "missing",
                    "to_use": true,
                    "order": 3,
                    "is_feedback": "true",
                    "definition_type": " ",
                    "entity_scopes": ["ITEM"],
                    "future_business_field": "kept"
                  }, {
                    "name": "price_present",
                    "store_name": "price_present_out",
                    "type": "STRING",
                    "definition_type": "DERIVED",
                    "expression": "coalesce(price_type1, \\\"missing\\\")",
                    "to_use": true,
                    "output_policy": "OUTPUT",
                    "order": 10
                  }],
                  "feature_set_name": " test_001 ",
                  "version": "latest"
                }
                """;

        FeatureSetConfig config = FeatureConfigLoader.load(json);
        assert config.features().size() == 2 : config.features();
        FeatureConfig raw = config.features().getFirst();
        assert raw.name().equals("price_type1") : raw.name();
        assert raw.rawName().equals("price_type") : raw.rawName();
        assert Boolean.TRUE.equals(raw.isFeedback()) : raw.isFeedback();
        assert raw.additionalProperties().get("future_business_field").equals("kept")
                : raw.additionalProperties();
        assert config.features().get(1).outputPolicy().equals("OUTPUT");
        assert config.featureSetName().equals(" test_001 ") : config.featureSetName();
    }

    private static void testUnifiedFeatureJsonParsing() {
        FeatureSetConfig config = FeatureConfigLoader.load("""
                {
                  "features": [
                    {"name":"price","raw_name":"raw_price","type":"DOUBLE",
                     "definition_type":null,"value_shape":"SCALAR","future_business_field":"kept"},
                    {"name":"price_score","type":"DOUBLE","definition_type":"DERIVED",
                     "expression":"multiply(price, price)","output_policy":"OUTPUT"}
                  ],
                  "feature_set_name":"test_001","version":"latest"
                }
                """);
        assert config.features().size() == 2 : config.features();
        FeatureConfig base = config.features().getFirst();
        assert base.definitionType() == null : base.definitionType();
        assert base.valueShape().equals("SCALAR") : base.valueShape();
        assert base.additionalProperties().get("future_business_field").equals("kept");
        assert config.features().get(1).expression().equals("multiply(price, price)");
    }

    private static void testLegacyDerivedFeaturesRejected() {
        IllegalArgumentException error = expectThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigLoader.load("""
                        {"features":[],"derivedFeatures":[],
                         "feature_set_name":"legacy","version":"latest"}
                        """));
        assert error.getMessage().contains("derivedFeatures") : error.getMessage();
    }

    private static void testIntermediateFeatureMapping() {
        FeatureSetConfig config = FeatureConfigLoader.load(intermediateConfigJson());
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config,
                ExecutionEnvironment.OFFLINE,
                Set.of(),
                Map.of());

        assert mapped.targetFeatures().equals(new LinkedHashSet<>(List.of("price_score")))
                : mapped.targetFeatures();
        assert mapped.outputs().size() == 1 : mapped.outputs();
        assert mapped.outputs().getFirst().storeName().equals("price_score_out");
        assert mapped.definitions().stream().anyMatch(definition ->
                definition.name().equals("normalized_price")
                        && definition.outputPolicy() == OutputPolicy.INTERNAL_ONLY)
                : "Intermediate definition must remain available to the dependency closure";

        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());
        assert dag.featureOutputNodeIds().containsKey("normalized_price")
                : "Final target must recursively build its intermediate dependency";
        assert !dag.rootNodeIds().contains(dag.featureOutputNodeIds().get("normalized_price"))
                : "Intermediate dependency must not become an output root";
    }

    private static String intermediateConfigJson() {
        return """
                {
                  "features": [
                    {
                      "name": "price",
                      "raw_name": "raw_price",
                      "store_name": "price",
                      "type": "DOUBLE",
                      "definition_type": "BASE",
                      "dft": 0.0,
                      "to_use": true,
                      "entity_scopes": ["ITEM"],
                      "value_shape": "SCALAR",
                      "order": 1
                    },
                    {
                      "name": "quality_score",
                      "raw_name": "quality_score",
                      "store_name": "quality_score",
                      "type": "DOUBLE",
                      "dft": 0.0,
                      "to_use": true,
                      "order": 2
                    },
                    {
                      "name": "normalized_price",
                      "store_name": "normalized_price",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "expression": "normalize(price, {\\\"method\\\":\\\"min_max\\\",\\\"min\\\":0,\\\"max\\\":1000})",
                      "to_use": true,
                      "output_policy": "INTERNAL_ONLY",
                      "entity_scopes": ["ITEM"],
                      "value_shape": "SCALAR",
                      "order": 1000
                    },
                    {
                      "name": "price_score",
                      "store_name": "price_score_out",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "expression": "multiply(normalized_price, quality_score)",
                      "to_use": true,
                      "output_policy": "OUTPUT",
                      "entity_scopes": ["ITEM", "USER"],
                      "value_shape": "SCALAR",
                      "order": 1001
                    }
                  ],
                  "feature_set_name": "test_001",
                  "version": "latest"
                }
                """;
    }

    private static void testOfflinePublicApi() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.offline("offline-public-api"));

        Map<String, List<?>> row = new LinkedHashMap<>();
        row.put("raw_price", List.of(100.0));
        row.put("quality_score", List.of(0.8));
        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("row-1", row));

        assert result.executionId().equals("row-1");
        assert result.featureValues().keySet().equals(Set.of("price_score_out"))
                : result.featureValues();
        double score = ((Number) result.featureValues()
                .get("price_score_out").getFirst()).doubleValue();
        assert Math.abs(score - 0.08) < 0.000001 : result.featureValues();
        assert result.candidateFeatureValues().isEmpty();
        assert !result.featureValues().containsKey("normalized_price")
                : "Internal feature leaked through the public boundary";
    }

    private static void testOfflineBatchPublicApi() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.offline("offline-batch-public-api"));

        List<Map<String, List<?>>> inputRows = List.of(
                Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8)),
                Map.of("raw_price", List.of(500.0), "quality_score", List.of(0.5)),
                Map.of("quality_score", List.of(0.9)));
        OfflineBatchGenerateResult result = engine.generateBatch(
                new OfflineBatchGenerateRequest("batch-1", inputRows));

        assert result.executionId().equals("batch-1");
        assert result.rows().size() == inputRows.size() : result.rows();
        assert Math.abs(((Number) scalarFeature(
                result.rows().get(0), "price_score_out")).doubleValue() - 0.08) < 0.000001
                : result.rows();
        assert Math.abs(((Number) scalarFeature(
                result.rows().get(1), "price_score_out")).doubleValue() - 0.25) < 0.000001
                : result.rows();
        assert Math.abs(((Number) scalarFeature(
                result.rows().get(2), "price_score_out")).doubleValue()) < 0.000001
                : result.rows();
        assert result.rows().stream().noneMatch(row -> row.containsKey("normalized_price"))
                : "Internal feature leaked through the batch public boundary";

        OfflineBatchGenerateResult empty = engine.generateBatch(
                new OfflineBatchGenerateRequest("empty-batch", List.of()));
        assert empty.rows().isEmpty() : empty.rows();

        String sequenceConfig = """
                {
                  "features": [
                    {"name":"values","raw_name":"values","type":"INT",
                     "definition_type":"BASE","entity_scopes":["USER"],
                     "value_shape":"SEQUENCE"},
                    {"name":"value_count","type":"INT","definition_type":"DERIVED",
                     "expression":"get_seq_length(values)","output_policy":"OUTPUT",
                     "entity_scopes":["USER"],"value_shape":"SCALAR"}
                  ],
                  "feature_set_name":"offline_batch_sequence","version":"1"
                }
                """;
        FeatureDagEngine sequenceEngine = FeatureDagEngine.init(
                sequenceConfig, InitOptions.offline("offline-batch-sequence"));
        OfflineBatchGenerateResult sequenceResult = sequenceEngine.generateBatch(
                new OfflineBatchGenerateRequest(
                        "sequence-batch",
                        List.of(
                                Map.of("values", List.of(1, 2, 3)),
                                Map.of("values", List.of()))));
        assert scalarFeature(sequenceResult.rows().get(0), "value_count").equals(3)
                : sequenceResult.rows();
        assert scalarFeature(sequenceResult.rows().get(1), "value_count").equals(0)
                : sequenceResult.rows();

        List<Map<String, List<?>>> missingSequenceRows = List.of(
                Map.of("values", List.of(1)),
                Map.of());
        FeatureGenerationException missing = expectThrows(
                FeatureGenerationException.class,
                () -> sequenceEngine.generateBatch(
                        new OfflineBatchGenerateRequest(
                                "missing-sequence-batch", missingSequenceRows)));
        assert missing.getMessage().contains("values") : missing.getMessage();
        assert missing.getMessage().contains("offline batch row 1") : missing.getMessage();
    }

    private static void testBatchDemos() {
        OfflineBatchGenerateResult offline = OfflineBatchDemo.run();
        assert offline.rows().size() == 3 : offline.rows();
        assert offline.rows().stream()
                .map(values -> scalarFeature(values, "user_tag_count"))
                .toList().equals(List.of(3, 3, 3)) : offline.rows();
        assert offline.rows().stream()
                .map(values -> scalarFeature(values, "score"))
                .toList().equals(List.of(20.0, 10.0, 12.0)) : offline.rows();
        assert offline.rows().stream()
                .map(values -> scalarFeature(values, "matching_tag_count"))
                .toList().equals(List.of(2, 1, 2)) : offline.rows();

        OnlineBatchGenerateResult online = OnlineGroupedBatchDemo.run();
        assert online.groupResults().size() == 3 : online.groupResults();
        GenerateResult userA = online.groupResults().get(0);
        GenerateResult userB = online.groupResults().get(1);
        GenerateResult empty = online.groupResults().get(2);
        assert userA.executionId().equals("user-a") : userA.executionId();
        assert scalarFeature(userA.featureValues(), "user_tag_count").equals(3)
                : userA.featureValues();
        assert userA.candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "matching_tag_count"))
                .toList().equals(List.of(2, 1)) : userA.candidateFeatureValues();
        assert userA.candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "score"))
                .toList().equals(List.of(20.0, 10.0)) : userA.candidateFeatureValues();
        assert userB.executionId().equals("user-b") : userB.executionId();
        assert userB.candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "score"))
                .toList().equals(List.of(12.0)) : userB.candidateFeatureValues();
        assert scalarFeature(empty.featureValues(), "user_tag_count").equals(0)
                : empty.featureValues();
        assert empty.candidateFeatureValues().isEmpty() : empty.candidateFeatureValues();

        List<Map<String, List<?>>> onlineRows = List.of(
                mergeFeatureValues(userA.featureValues(), userA.candidateFeatureValues().get(0)),
                mergeFeatureValues(userA.featureValues(), userA.candidateFeatureValues().get(1)),
                mergeFeatureValues(userB.featureValues(), userB.candidateFeatureValues().get(0)));
        assert offline.rows().equals(onlineRows)
                : "shared config produced different offline/online rows: offline="
                        + offline.rows() + ", online=" + onlineRows;
    }

    private static Map<String, List<?>> mergeFeatureValues(
            Map<String, List<?>> shared,
            Map<String, List<?>> candidate) {
        Map<String, List<?>> merged = new LinkedHashMap<>(shared);
        merged.putAll(candidate);
        return merged;
    }

    private static void testConfigPathInit() throws Exception {
        Path configPath = Files.createTempFile("feature-dag-config-", ".json");
        try {
            Files.writeString(configPath, intermediateConfigJson(), StandardCharsets.UTF_8);
            FeatureDagEngine engine = FeatureDagEngine.init(
                    configPath,
                    InitOptions.offline("offline-path-api"));
            GenerateResult result = engine.generate(new OfflineGenerateRequest(
                    "row-path",
                    Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8))));
            assert Math.abs(((Number) scalarFeature(
                    result.featureValues(), "price_score_out")).doubleValue() - 0.08)
                    < 0.000001 : result.featureValues();
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    private static void testOnlinePublicApi() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(),
                InitOptions.online("online-public-api"));
        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "request-1",
                Map.of("user_click_count", List.of(10), "user_seq1", publicIndustrySequence()),
                List.of(
                        Map.of("item_industry", List.of("industry1"), "item_price", List.of(100.0)),
                        Map.of("item_industry", List.of("industry2"), "item_price", List.of(50.0)),
                        Map.of("item_industry", List.of("industry1"), "item_price", List.of(80.0)))));

        assert result.featureValues().isEmpty() : result.featureValues();
        assert result.candidateFeatureValues().size() == 3;
        assert result.candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "same_industry_count"))
                .toList().equals(List.of(3, 1, 3)) : result.candidateFeatureValues();
        assert result.candidateFeatureValues().stream()
                .allMatch(values -> values.containsKey("final_score"));
        assert List.copyOf(result.candidateFeatureValues().getFirst().keySet())
                .equals(List.of("same_industry_count", "final_score"))
                : result.candidateFeatureValues().getFirst();
    }

    private static void testOnlineBatchPublicApi() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(),
                InitOptions.online("online-batch-public-api"));
        List<OnlineRequestGroup> groups = List.of(
                new OnlineRequestGroup(
                        "user-a",
                        Map.of(
                                "user_click_count", List.of(10),
                                "user_seq1", publicIndustrySequence()),
                        List.of(
                                Map.of("item_industry", List.of("industry1"),
                                        "item_price", List.of(100.0)),
                                Map.of("item_industry", List.of("industry2"),
                                        "item_price", List.of(50.0)),
                                Map.of("item_industry", List.of("industry1"),
                                        "item_price", List.of(80.0)))),
                new OnlineRequestGroup(
                        "user-b",
                        Map.of(
                                "user_click_count", List.of(20),
                                "user_seq1", List.of("industry2", "industry2", "industry3")),
                        List.of(
                                Map.of("item_industry", List.of("industry2"),
                                        "item_price", List.of(25.0)),
                                Map.of("item_industry", List.of("industry1"),
                                        "item_price", List.of(5.0)))),
                new OnlineRequestGroup(
                        "user-empty",
                        Map.of("user_seq1", List.of()),
                        List.of()));

        OnlineBatchGenerateResult batch = engine.generateBatch(
                new OnlineBatchGenerateRequest("online-batch-1", groups));
        assert batch.executionId().equals("online-batch-1") : batch.executionId();
        assert batch.groupResults().size() == groups.size() : batch.groupResults();
        assert batch.groupResults().get(0).candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "same_industry_count"))
                .toList().equals(List.of(3, 1, 3)) : batch.groupResults();
        assert batch.groupResults().get(1).candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "same_industry_count"))
                .toList().equals(List.of(2, 0)) : batch.groupResults();
        assert batch.groupResults().get(2).candidateFeatureValues().isEmpty()
                : batch.groupResults();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            OnlineRequestGroup group = groups.get(groupIndex);
            GenerateResult single = engine.generate(new OnlineGenerateRequest(
                    group.executionId(), group.sharedValues(), group.candidates()));
            GenerateResult grouped = batch.groupResults().get(groupIndex);
            assert grouped.executionId().equals(group.executionId()) : grouped.executionId();
            assert grouped.featureValues().equals(single.featureValues())
                    : "grouped=" + grouped.featureValues() + ", single=" + single.featureValues();
            assert grouped.candidateFeatureValues().equals(single.candidateFeatureValues())
                    : "grouped=" + grouped.candidateFeatureValues()
                            + ", single=" + single.candidateFeatureValues();
        }

        String sharedJson = """
                {
                  "feature_set_name":"online-batch-shared",
                  "version":"1",
                  "features":[
                    {"name":"scene_value","raw_name":"scene_value","type":"DOUBLE",
                     "definition_type":"BASE","entity_scopes":["SCENE"],"value_shape":"SCALAR"},
                    {"name":"scene_score","store_name":"scene_score","type":"DOUBLE",
                     "definition_type":"DERIVED","expression":"multiply(scene_value, 2.0)",
                     "output_policy":"OUTPUT","entity_scopes":["SCENE"],"value_shape":"SCALAR"}
                  ]
                }
                """;
        FeatureDagEngine sharedEngine = FeatureDagEngine.init(
                sharedJson, InitOptions.online("online-batch-shared"));
        OnlineBatchGenerateResult sharedBatch = sharedEngine.generateBatch(
                new OnlineBatchGenerateRequest(
                        "shared-batch",
                        List.of(
                                new OnlineRequestGroup(
                                        "scene-a", Map.of("scene_value", List.of(2.0)),
                                        List.of(Map.of(), Map.of())),
                                new OnlineRequestGroup(
                                        "scene-b", Map.of("scene_value", List.of(3.0)),
                                        List.of()))));
        assert sharedBatch.groupResults().get(0).featureValues()
                .equals(Map.of("scene_score", List.of(4.0))) : sharedBatch.groupResults();
        assert sharedBatch.groupResults().get(1).featureValues()
                .equals(Map.of("scene_score", List.of(6.0))) : sharedBatch.groupResults();

        OnlineBatchGenerateResult empty = engine.generateBatch(
                new OnlineBatchGenerateRequest("empty-online-batch", List.of()));
        assert empty.groupResults().isEmpty() : empty.groupResults();

        String requiredPriceConfig = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",");
        FeatureDagEngine requiredPrice = FeatureDagEngine.init(
                requiredPriceConfig, InitOptions.online("online-batch-required-price"));
        FeatureGenerationException missing = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generateBatch(new OnlineBatchGenerateRequest(
                        "missing-online-batch",
                        List.of(
                                groups.getFirst(),
                                new OnlineRequestGroup(
                                        "user-missing-price",
                                        Map.of(
                                                "user_click_count", List.of(1),
                                                "user_seq1", List.of("industry1")),
                                        List.of(Map.of(
                                                "item_industry", List.of("industry1"))))))));
        assert missing.getMessage().contains("item_price") : missing.getMessage();
        assert missing.getMessage().contains("online batch group 1") : missing.getMessage();
        assert missing.getMessage().contains("candidate 0") : missing.getMessage();
    }

    private static void testOperatorRegistryConcurrentRegistration() throws Exception {
        OperatorRegistry registry = new OperatorRegistry();
        int operatorCount = 32;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<? extends Future<?>> registrations = IntStream.range(0, operatorCount)
                    .mapToObj(index -> executor.submit(() -> registry.register(
                            echoOperator("concurrent_echo_" + index, true, new AtomicInteger()))))
                    .toList();
            for (Future<?> registration : registrations) registration.get();
        }
        assert IntStream.range(0, operatorCount)
                .allMatch(index -> registry.find("concurrent_echo_" + index).isPresent());
    }

    private static void testRuntimeObservability() {
        InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(4);
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .planId("observed-online")
                        .runtimeObserver(observer)
                        .build());

        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "observed-request",
                Map.of("user_click_count", List.of(10),
                        "user_seq1", publicIndustrySequence()),
                List.of(
                        Map.of("item_industry", List.of("industry1"),
                                "item_price", List.of(100.0)),
                        Map.of("item_industry", List.of("industry2"),
                                "item_price", List.of(50.0)),
                        Map.of("item_industry", List.of("industry1"),
                                "item_price", List.of(80.0)))));
        assert result.candidateFeatureValues().size() == 3;
        assert observer.receivedCount() == 1 : observer.receivedCount();

        ExecutionDiagnostics diagnostics = observer.latest();
        assert diagnostics.planId().equals("observed-online") : diagnostics;
        assert diagnostics.featureSetName().equals("online_features") : diagnostics;
        assert diagnostics.version().equals("latest") : diagnostics;
        assert diagnostics.executionId().equals("observed-request") : diagnostics;
        assert diagnostics.environment() == ExecutionEnvironment.ONLINE : diagnostics;
        assert diagnostics.status() == ExecutionStatus.SUCCESS : diagnostics;
        assert diagnostics.failurePhase() == ExecutionPhase.NONE : diagnostics;
        assert diagnostics.errorType() == null : diagnostics;
        assert diagnostics.groupCount() == 1 : diagnostics;
        assert diagnostics.candidateCount() == 3 : diagnostics;
        assert diagnostics.offlineRowCount() == 0 : diagnostics;
        assert diagnostics.sourceSequenceCount() == 1 : diagnostics;
        assert diagnostics.sourceSequenceElementCount() == 6 : diagnostics;
        assert diagnostics.maxSourceSequenceLength() == 6 : diagnostics;
        assert diagnostics.physicalNodeCount() > 0 : diagnostics;
        assert diagnostics.logicalNodeCount() >= diagnostics.physicalNodeCount() : diagnostics;
        assert diagnostics.nodes().size() == diagnostics.physicalNodeCount() : diagnostics;
        assert diagnostics.totalDurationNanos()
                >= diagnostics.decodeDurationNanos()
                        + diagnostics.runtimeDurationNanos()
                        + diagnostics.encodeDurationNanos()
                : diagnostics;
        assert diagnostics.nodes().stream()
                .allMatch(node -> node.status() == ExecutionStatus.SUCCESS)
                : diagnostics.nodes();
        assert diagnostics.nodes().stream()
                .anyMatch(node -> node.executorId().equals(PhysicalExecutorIds.GENERIC_OPERATOR))
                : diagnostics.nodes();
        assert diagnostics.nodes().stream()
                .filter(node -> node.executorId().equals(PhysicalExecutorIds.GENERIC_OPERATOR))
                .allMatch(node -> node.operatorInvocationKind() != null)
                : diagnostics.nodes();
        assert diagnostics.nodes().stream()
                .anyMatch(node -> node.operatorInvocationKind() != null
                        && node.operatorInvocationKind().isBatch()
                        && node.batchDomain() != null
                        && node.batchRowCount() > 0)
                : diagnostics.nodes();
    }

    private static void testRuntimeObservabilityCoversBatchAndFailure() {
        InMemoryRuntimeObserver offlineObserver = new InMemoryRuntimeObserver(2);
        FeatureDagEngine offlineEngine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("observed-offline-batch")
                        .runtimeObserver(offlineObserver)
                        .build());
        offlineEngine.generateBatch(new OfflineBatchGenerateRequest(
                "observed-offline-batch-request",
                List.of(
                        Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8)),
                        Map.of("raw_price", List.of(200.0), "quality_score", List.of(0.5)))));
        ExecutionDiagnostics offline = offlineObserver.latest();
        assert offline.status() == ExecutionStatus.SUCCESS : offline;
        assert offline.offlineRowCount() == 2 : offline;
        assert offline.groupCount() == 0 : offline;
        assert offline.candidateCount() == 0 : offline;
        assert offline.sourceSequenceCount() == 0 : offline;

        InMemoryRuntimeObserver onlineObserver = new InMemoryRuntimeObserver(4);
        String requiredPriceConfig = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",");
        FeatureDagEngine onlineEngine = FeatureDagEngine.init(
                requiredPriceConfig,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .planId("observed-online-batch")
                        .runtimeObserver(onlineObserver)
                        .build());
        List<OnlineRequestGroup> groups = List.of(
                new OnlineRequestGroup(
                        "observed-user-a",
                        Map.of("user_click_count", List.of(1),
                                "user_seq1", publicIndustrySequence()),
                        List.of(
                                Map.of("item_industry", List.of("industry1"),
                                        "item_price", List.of(10.0)),
                                Map.of("item_industry", List.of("industry2"),
                                        "item_price", List.of(20.0)))),
                new OnlineRequestGroup(
                        "observed-user-b",
                        Map.of("user_click_count", List.of(2),
                                "user_seq1", List.of("industry2")),
                        List.of(Map.of(
                                "item_industry", List.of("industry2"),
                                "item_price", List.of(30.0)))));
        onlineEngine.generateBatch(new OnlineBatchGenerateRequest(
                "observed-online-batch-request", groups));
        ExecutionDiagnostics batch = onlineObserver.latest();
        assert batch.status() == ExecutionStatus.SUCCESS : batch;
        assert batch.groupCount() == 2 : batch;
        assert batch.candidateCount() == 3 : batch;
        assert batch.sourceSequenceCount() == 2 : batch;
        assert batch.sourceSequenceElementCount() == 7 : batch;
        assert batch.maxSourceSequenceLength() == 6 : batch;

        expectThrows(
                FeatureGenerationException.class,
                () -> onlineEngine.generate(new OnlineGenerateRequest(
                        "observed-failure",
                        Map.of("user_click_count", List.of(1),
                                "user_seq1", publicIndustrySequence()),
                        List.of(Map.of("item_industry", List.of("industry1"))))));
        ExecutionDiagnostics failed = onlineObserver.latest();
        assert failed.executionId().equals("observed-failure") : failed;
        assert failed.status() == ExecutionStatus.FAILED : failed;
        assert failed.failurePhase() == ExecutionPhase.RUNTIME : failed;
        assert failed.errorType() != null : failed;
        assert failed.nodes().stream().anyMatch(node -> node.status() == ExecutionStatus.FAILED)
                : failed.nodes();
        assert onlineObserver.receivedCount() == 2 : onlineObserver.receivedCount();
    }

    private static void testRuntimeObservabilityControls() {
        InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(12);
        RuntimeObservabilityController controller = new RuntimeObservabilityController(
                ObservabilityOptions.builder()
                        .enabled(false)
                        .sampleRate(1.0)
                        .detailLevel(ObservationDetailLevel.NODE)
                        .build());
        FeatureDagEngine engine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("controlled-observer")
                        .observabilityController(controller)
                        .runtimeObserver(observer)
                        .build());

        engine.generate(observabilityOfflineRequest("disabled-request"));
        assert observer.receivedCount() == 0 : observer.receivedCount();

        controller.update(ObservabilityOptions.builder()
                .sampleRate(0.0)
                .captureFailuresAlways(false)
                .detailLevel(ObservationDetailLevel.BASIC)
                .build());
        engine.generate(observabilityOfflineRequest("unsampled-request"));
        assert observer.receivedCount() == 0 : observer.receivedCount();

        controller.update(ObservabilityOptions.builder()
                .sampleRate(1.0)
                .detailLevel(ObservationDetailLevel.BASIC)
                .build());
        engine.generate(observabilityOfflineRequest("basic-request"));
        ExecutionDiagnostics basic = observer.latest();
        assert basic.sampled() : basic;
        assert !basic.slow() : basic;
        assert basic.detailLevel() == ObservationDetailLevel.BASIC : basic;
        assert basic.cacheStats().isEmpty() : basic;
        assert basic.nodes().isEmpty() : basic;

        controller.update(ObservabilityOptions.builder()
                .sampleRate(1.0)
                .detailLevel(ObservationDetailLevel.NODE)
                .build());
        engine.generate(observabilityOfflineRequest("node-request"));
        ExecutionDiagnostics node = observer.latest();
        assert node.detailLevel() == ObservationDetailLevel.NODE : node;
        assert !node.nodes().isEmpty() : node;

        controller.setEnabled(false);
        engine.generate(observabilityOfflineRequest("dynamically-disabled-request"));
        assert observer.receivedCount() == 2 : observer.receivedCount();

        controller.update(ObservabilityOptions.builder()
                .sampleRate(0.0)
                .captureFailuresAlways(true)
                .detailLevel(ObservationDetailLevel.BASIC)
                .build());
        expectThrows(
                FeatureGenerationException.class,
                () -> engine.generate(new OnlineGenerateRequest(
                        "forced-failure-request", Map.of(), List.of())));
        ExecutionDiagnostics failed = observer.latest();
        assert observer.receivedCount() == 3 : observer.receivedCount();
        assert failed.status() == ExecutionStatus.FAILED : failed;
        assert !failed.sampled() : failed;
        assert failed.failurePhase() == ExecutionPhase.VALIDATION : failed;

        controller.update(ObservabilityOptions.builder()
                .sampleRate(0.0)
                .captureFailuresAlways(false)
                .slowRequestThreshold(Duration.ofNanos(1))
                .detailLevel(ObservationDetailLevel.CACHE)
                .build());
        engine.generate(observabilityOfflineRequest("forced-slow-request"));
        ExecutionDiagnostics slow = observer.latest();
        assert observer.receivedCount() == 4 : observer.receivedCount();
        assert slow.slow() : slow;
        assert !slow.sampled() : slow;
        assert slow.detailLevel() == ObservationDetailLevel.CACHE : slow;
        assert slow.nodes().isEmpty() : slow;

        controller.update(ObservabilityOptions.builder()
                .sampleRate(0.5)
                .captureFailuresAlways(false)
                .detailLevel(ObservationDetailLevel.BASIC)
                .build());
        long beforeDeterministicSample = observer.receivedCount();
        engine.generate(observabilityOfflineRequest("deterministic-request"));
        engine.generate(observabilityOfflineRequest("deterministic-request"));
        long deterministicDelta = observer.receivedCount() - beforeDeterministicSample;
        assert deterministicDelta == 0 || deterministicDelta == 2 : deterministicDelta;

        expectThrows(
                IllegalArgumentException.class,
                () -> ObservabilityOptions.builder().sampleRate(1.01).build());
        expectThrows(
                IllegalArgumentException.class,
                () -> ObservabilityOptions.builder()
                        .slowRequestThreshold(Duration.ofNanos(-1))
                        .build());
    }

    private static void testAsyncRuntimeObserver() throws Exception {
        InMemoryRuntimeObserver templateObserver = new InMemoryRuntimeObserver(1);
        FeatureDagEngine templateEngine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("async-observer-template")
                        .runtimeObserver(templateObserver)
                        .build());
        templateEngine.generate(observabilityOfflineRequest("async-template-request"));
        ExecutionDiagnostics template = templateObserver.latest();

        List<ExecutionDiagnostics> exported = Collections.synchronizedList(new ArrayList<>());
        try (AsyncRuntimeObserver observer = new AsyncRuntimeObserver(
                8, 4, Duration.ofMillis(10), exported::addAll)) {
            observer.onExecutionCompleted(template);
            observer.onExecutionCompleted(template);
            observer.onExecutionCompleted(template);
            assert observer.awaitDrained(Duration.ofSeconds(2)) : observer.stats();
            AsyncObserverStats stats = observer.stats();
            assert stats.accepted() == 3 : stats;
            assert stats.dropped() == 0 : stats;
            assert stats.exported() == 3 : stats;
            assert stats.exportFailures() == 0 : stats;
            assert stats.pending() == 0 : stats;
            assert exported.size() == 3 : exported;
        }

        CountDownLatch exportStarted = new CountDownLatch(1);
        CountDownLatch releaseExport = new CountDownLatch(1);
        AsyncRuntimeObserver bounded = new AsyncRuntimeObserver(
                1,
                1,
                Duration.ofMillis(10),
                batch -> {
                    exportStarted.countDown();
                    try {
                        releaseExport.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
        bounded.onExecutionCompleted(template);
        assert exportStarted.await(2, TimeUnit.SECONDS) : bounded.stats();
        bounded.onExecutionCompleted(template);
        bounded.onExecutionCompleted(template);
        assert bounded.stats().dropped() == 1 : bounded.stats();
        releaseExport.countDown();
        assert bounded.awaitDrained(Duration.ofSeconds(2)) : bounded.stats();
        assert bounded.close(Duration.ofSeconds(2)) : bounded.stats();
        long droppedBeforeClosedWrite = bounded.stats().dropped();
        bounded.onExecutionCompleted(template);
        assert bounded.stats().dropped() == droppedBeforeClosedWrite + 1 : bounded.stats();

        try (AsyncRuntimeObserver failing = new AsyncRuntimeObserver(
                2,
                2,
                Duration.ofMillis(10),
                batch -> {
                    throw new IllegalStateException("metrics backend unavailable");
                })) {
            failing.onExecutionCompleted(template);
            assert failing.awaitDrained(Duration.ofSeconds(2)) : failing.stats();
            assert failing.stats().exportFailures() == 1 : failing.stats();
            assert failing.stats().exported() == 0 : failing.stats();
        }
    }

    private static OfflineGenerateRequest observabilityOfflineRequest(String executionId) {
        return new OfflineGenerateRequest(
                executionId,
                Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8)));
    }

    private static void testRuntimeObserverFailureIsolation() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("observer-failure-isolation")
                        .runtimeObserver(diagnostics -> {
                            throw new IllegalStateException("observer unavailable");
                        })
                        .build());
        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "observer-failure-request",
                Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8))));
        assert Math.abs(((Number) scalarFeature(
                result.featureValues(), "price_score_out")).doubleValue() - 0.08) < 0.000001
                : result.featureValues();
    }

    private static void testOnlineBaseMetadataDefaults() {
        String sharedJson = onlineConfigWithoutBaseMetadata("USER");
        FeatureDagEngine sharedEngine = FeatureDagEngine.init(
                sharedJson, InitOptions.online("online-default-user-scope"));
        GenerateResult sharedResult = sharedEngine.generate(new OnlineGenerateRequest(
                "online-default-user-scope-request",
                Map.of("ad_type", List.of("banner")),
                List.of(Map.of())));
        assert sharedResult.featureValues().equals(Map.of("ad_type_copy", List.of("banner")))
                : sharedResult.featureValues();
        assert sharedResult.candidateFeatureValues().equals(List.of(Map.of()))
                : sharedResult.candidateFeatureValues();

        FeatureDagEngine sequenceEngine = FeatureDagEngine.init(
                onlineSequenceConfigWithoutBaseValueShape(),
                InitOptions.online("online-default-sequence-shape"));
        GenerateResult sequenceResult = sequenceEngine.generate(new OnlineGenerateRequest(
                "online-default-sequence-shape-request",
                Map.of("ad_history", List.of("banner", "video")),
                List.of()));
        assert sequenceResult.featureValues().equals(Map.of("ad_history_count", List.of(2)))
                : sequenceResult.featureValues();

        InitOptions itemDefaults = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("online-default-item-scope")
                .defaultRawFeatureScopes(Set.of(EntityScope.ITEM))
                .build();
        FeatureDagEngine itemEngine = FeatureDagEngine.init(
                onlineConfigWithoutBaseMetadata("ITEM"), itemDefaults);
        GenerateResult itemResult = itemEngine.generate(new OnlineGenerateRequest(
                "online-default-item-scope-request",
                Map.of(),
                List.of(
                        Map.of("ad_type", List.of("banner")),
                        Map.of("ad_type", List.of("video")))));
        assert itemResult.featureValues().isEmpty() : itemResult.featureValues();
        assert itemResult.candidateFeatureValues().stream()
                .map(values -> values.get("ad_type_copy"))
                .toList()
                .equals(List.of(List.of("banner"), List.of("video")))
                : itemResult.candidateFeatureValues();
    }

    private static String onlineConfigWithoutBaseMetadata(String derivedScope) {
        return """
                {
                  "features": [
                    {"name":"ad_type","raw_name":"ad_type","type":"STRING",
                     "definition_type":"BASE","dft":"missing",
                     "entity_scopes":null,"value_shape":null},
                    {"name":"ad_type_copy","store_name":"ad_type_copy","type":"STRING",
                     "definition_type":"DERIVED","expression":"coalesce(ad_type, \\\"missing\\\")",
                     "output_policy":"OUTPUT","entity_scopes":["%s"],"value_shape":"SCALAR"}
                  ],
                  "feature_set_name":"online_base_metadata_defaults","version":"1"
                }
                """.formatted(derivedScope);
    }

    private static String onlineSequenceConfigWithoutBaseValueShape() {
        return """
                {
                  "features": [
                    {"name":"ad_history","raw_name":"ad_history","type":"STRING",
                     "definition_type":"BASE","seq_max_length":10,
                     "entity_scopes":null,"value_shape":null},
                    {"name":"ad_history_count","type":"INT","definition_type":"DERIVED",
                     "expression":"count(ad_history)","output_policy":"OUTPUT",
                     "entity_scopes":["USER"],"value_shape":"SCALAR"}
                  ],
                  "feature_set_name":"online_base_sequence_default","version":"1"
                }
                """;
    }

    private static void testOnlineSharedArrayOutput() {
        String json = """
                {
                  "feature_set_name":"shared-array-output",
                  "version":"1",
                  "features":[
                    {"name":"scene_value","raw_name":"scene_value","type":"DOUBLE",
                     "definition_type":"BASE","entity_scopes":["SCENE"],"value_shape":"SCALAR"},
                    {"name":"scene_score","store_name":"scene_score","type":"DOUBLE",
                     "definition_type":"DERIVED","expression":"multiply(scene_value, 2.0)",
                     "output_policy":"OUTPUT","entity_scopes":["SCENE"],"value_shape":"SCALAR"}
                  ]
                }
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(
                json, InitOptions.online("shared-array-output"));
        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "shared-output-request",
                Map.of("scene_value", List.of(2.0)),
                List.of(Map.of(), Map.of())));

        assert result.featureValues().get("scene_score").equals(List.of(4.0))
                : result.featureValues();
        assert result.candidateFeatureValues().equals(List.of(Map.of(), Map.of()))
                : result.candidateFeatureValues();

        OperatorRegistry operators = OperatorRegistry.standard();
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(json), ExecutionEnvironment.ONLINE, Set.of(), Map.of());
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), operators)
                .build(mapped.definitions(), mapped.targetFeatures());
        PhysicalPlan plan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(dag),
                ExecutionEnvironment.ONLINE,
                "shared-array-output-plan");
        assert stageFor(plan, "feature:scene_score") == ExecutionStage.REQUEST_SHARED
                : PhysicalPlanPrinter.print(plan);
    }

    private static void testOnlineEngineConcurrentReuse() throws Exception {
        InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(20);
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .planId("online-concurrent")
                        .runtimeObserver(observer)
                        .build());
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<List<Integer>>> calls = IntStream.range(0, 20)
                    .mapToObj(index -> (Callable<List<Integer>>) () -> {
                        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                                "request-" + index,
                                Map.of(
                                        "user_click_count", List.of(10),
                                        "user_seq1", publicIndustrySequence()),
                                List.of(
                                        Map.of("item_industry", List.of("industry1"),
                                                "item_price", List.of(100.0)),
                                        Map.of("item_industry", List.of("industry2"),
                                                "item_price", List.of(50.0)))));
                        return result.candidateFeatureValues().stream()
                                .map(values -> (Integer) scalarFeature(
                                        values, "same_industry_count"))
                                .toList();
                    })
                    .toList();
            for (Future<List<Integer>> future : executor.invokeAll(calls)) {
                assert future.get().equals(List.of(3, 1)) : future.get();
            }
        }
        assert observer.receivedCount() == 20 : observer.receivedCount();
        assert observer.snapshots().stream()
                .allMatch(diagnostics -> diagnostics.status() == ExecutionStatus.SUCCESS)
                : observer.snapshots();
        assert observer.snapshots().stream()
                .map(ExecutionDiagnostics::executionId)
                .collect(java.util.stream.Collectors.toSet())
                .size() == 20 : observer.snapshots();
    }

    private static String onlineConfigJson() {
        return """
                {
                  "features": [
                    {"name":"user_click_count","raw_name":"user_click_count","type":"INT","definition_type":"BASE","dft":0,"entity_scopes":["USER"],"value_shape":"SCALAR"},
                    {"name":"user_seq1","raw_name":"user_seq1","type":"STRING","definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"item_industry","raw_name":"item_industry","type":"STRING","definition_type":null,"dft":"unknown","entity_scopes":["ITEM"],"value_shape":"SCALAR"},
                    {"name":"item_price","raw_name":"item_price","type":"DOUBLE","dft":0.0,"entity_scopes":["ITEM"],"value_shape":"SCALAR"},
                    {
                      "name":"user_click_score",
                      "type":"DOUBLE",
                      "definition_type":"DERIVED",
                      "expression":"normalize(coalesce(user_click_count, 0), {\\\"method\\\":\\\"min_max\\\",\\\"min\\\":0,\\\"max\\\":100})",
                      "output_policy":"INTERNAL_ONLY",
                      "entity_scopes":["USER"],
                      "value_shape":"SCALAR"
                    },
                    {
                      "name":"same_industry_seq",
                      "type":"INT",
                      "definition_type":"DERIVED",
                      "expression":"find_list_index_typed(user_seq1, item_industry)",
                      "output_policy":"INTERNAL_ONLY",
                      "entity_scopes":["USER", "ITEM"],
                      "value_shape":"SEQUENCE"
                    },
                    {
                      "name":"same_industry_count",
                      "store_name":"same_industry_count",
                      "type":"INT",
                      "definition_type":"DERIVED",
                      "expression":"count(same_industry_seq)",
                      "output_policy":"OUTPUT",
                      "entity_scopes":["USER", "ITEM"],
                      "value_shape":"SCALAR",
                      "order":1
                    },
                    {
                      "name":"item_price_log",
                      "type":"DOUBLE",
                      "definition_type":"DERIVED",
                      "expression":"log(add(item_price, 1))",
                      "output_policy":"INTERNAL_ONLY",
                      "entity_scopes":["ITEM"],
                      "value_shape":"SCALAR"
                    },
                    {
                      "name":"final_score",
                      "store_name":"final_score",
                      "type":"DOUBLE",
                      "definition_type":"DERIVED",
                      "expression":"multiply(user_click_score, item_price_log)",
                      "output_policy":"OUTPUT",
                      "entity_scopes":["USER", "ITEM"],
                      "value_shape":"SCALAR",
                      "order":2
                    }
                  ],
                  "feature_set_name":"online_features",
                  "version":"latest"
                }
                """;
    }

    private static void testOfflineSequenceMaterialization() {
        String json = """
                {
                  "features": [
                    {"name":"user_seq1","raw_name":"user_seq1","type":"STRING",
                     "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"sequence_out",
                    "store_name":"sequence_out",
                    "type":"STRING",
                    "definition_type":"DERIVED",
                    "expression":"coalesce(user_seq1, [])",
                    "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SEQUENCE"
                  }],
                  "feature_set_name":"sequence_output",
                  "version":"1"
                }
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(json, InitOptions.offline("sequence-output"));
        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "sequence-row",
                Map.of("user_seq1", publicIndustrySequence())));
        assert result.featureValues().get("sequence_out").equals(publicIndustrySequence())
                : result.featureValues();
    }

    private static void testEventSequencePublicApi() {
        String json = """
                {
                  "features": [
                    {"name":"user_events","raw_name":"user_events","type":"EVENT_SEQUENCE",
                     "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"item_industry","raw_name":"item_industry","type":"STRING",
                     "definition_type":"BASE","entity_scopes":["ITEM"],"value_shape":"SCALAR"},
                    {"name":"same_industry_seq","store_name":"same_industry_seq",
                     "type":"EVENT_SEQUENCE","definition_type":"DERIVED",
                     "expression":"extractIndustry(user_events, item_industry)",
                     "output_policy":"OUTPUT","entity_scopes":["USER","ITEM"],
                     "value_shape":"SEQUENCE","order":1},
                    {"name":"same_industry_count","store_name":"same_industry_count",
                     "type":"INT","definition_type":"DERIVED",
                     "expression":"count(same_industry_seq)",
                     "output_policy":"OUTPUT","entity_scopes":["USER","ITEM"],
                     "value_shape":"SCALAR","order":2}
                  ],
                  "feature_set_name":"event_sequence_public_api",
                  "version":"1"
                }
                """;

        FeatureDagEngine online = FeatureDagEngine.init(
                json,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .planId("event-sequence-online")
                        .targetFeatures(Set.of("same_industry_count"))
                        .build());
        GenerateResult onlineResult = online.generate(new OnlineGenerateRequest(
                "event-sequence-request",
                Map.of("user_events", publicEventSequence()),
                List.of(
                        Map.of("item_industry", List.of("industry1")),
                        Map.of("item_industry", List.of("industry2")))));
        assert onlineResult.candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "same_industry_count"))
                .toList().equals(List.of(3, 1)) : onlineResult.candidateFeatureValues();

        FeatureDagEngine offline = FeatureDagEngine.init(
                json,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("event-sequence-offline")
                        .targetFeatures(Set.of("same_industry_seq", "same_industry_count"))
                        .build());
        GenerateResult offlineResult = offline.generate(new OfflineGenerateRequest(
                "event-sequence-row",
                Map.of(
                        "user_events", publicEventSequence(),
                        "item_industry", List.of("industry1"))));
        assert offlineResult.featureValues().get("same_industry_count").equals(List.of(3))
                : offlineResult.featureValues();
        List<Map<String, Object>> expected = List.of(
                publicEventSequence().get(0),
                publicEventSequence().get(2),
                publicEventSequence().get(4));
        assert offlineResult.featureValues().get("same_industry_seq").equals(expected)
                : offlineResult.featureValues();
    }

    private static void testConfigurationAndRequestValidation() {
        IllegalArgumentException propertyTypo = expectThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigLoader.load("""
                        {
                          "features":[
                            {"name":"source","raw_name":"source","type":"INT",
                             "definition_type":"BASE","entity_scop":["ITEM"]}
                          ],
                          "feature_set_name":"property_typo","version":"1"
                        }
                        """));
        assert propertyTypo.getMessage().contains("entity_scopes")
                : propertyTypo.getMessage();

        FeatureDagInitializationException invalidJson = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init("{not-json}", InitOptions.offline("invalid-json")));
        assert invalidJson.getMessage().contains("Invalid feature config JSON")
                : invalidJson.getMessage();

        FeatureDagInitializationException duplicateName = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(
                        intermediateConfigJson().replace(
                                "\"name\": \"quality_score\"", "\"name\": \"price\""),
                        InitOptions.offline("duplicate-name")));
        assert duplicateName.getMessage().contains("price") : duplicateName.getMessage();

        String duplicateStoreJson = intermediateConfigJson()
                .replace("\"store_name\": \"normalized_price\"",
                        "\"store_name\": \"price_score_out\"")
                .replaceFirst("\"output_policy\": \"INTERNAL_ONLY\"",
                        "\"output_policy\": \"OUTPUT\"");
        FeatureDagInitializationException duplicateStore = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(duplicateStoreJson, InitOptions.offline("duplicate-store")));
        assert duplicateStore.getMessage().contains("price_score_out") : duplicateStore.getMessage();

        InitOptions internalTargetOptions = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId("internal-target")
                .targetFeatures(Set.of("normalized_price"))
                .build();
        FeatureDagInitializationException internalTarget = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(intermediateConfigJson(), internalTargetOptions));
        assert internalTarget.getMessage().contains("normalized_price") : internalTarget.getMessage();

        String disabledDependencyJson = intermediateConfigJson().replaceFirst(
                "(?s)(\"name\": \"normalized_price\".*?\"definition_type\": \"DERIVED\".*?\"to_use\": )true",
                "$1false");
        FeatureDagInitializationException disabledDependency = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(
                        disabledDependencyJson, InitOptions.offline("disabled-dependency")));
        assert disabledDependency.getMessage().contains("normalized_price")
                && disabledDependency.getMessage().contains("is disabled")
                : disabledDependency.getMessage();

        String cycleJson = intermediateConfigJson().replace(
                "normalize(price, {\\\"method\\\":\\\"min_max\\\",\\\"min\\\":0,\\\"max\\\":1000})",
                "multiply(price_score, quality_score)");
        FeatureDagInitializationException cycle = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(cycleJson, InitOptions.offline("cycle")));
        assert cycle.getMessage().toLowerCase().contains("cycle")
                && cycle.getMessage().contains("normalized_price")
                && cycle.getMessage().contains("price_score") : cycle.getMessage();

        String missingScopeJson = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,\"entity_scopes\":[\"ITEM\"]",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0");
        InitOptions defaultItemScope = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("default-item-scope")
                .defaultRawFeatureScopes(Set.of(EntityScope.ITEM))
                .build();
        FeatureDagEngine.init(missingScopeJson, defaultItemScope);

        InitOptions scopeOverride = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("scope-override")
                .rawFeatureScopes(Map.of("item_price", Set.of(com.example.featuredag.definition.EntityScope.ITEM)))
                .build();
        FeatureDagEngine.init(missingScopeJson, scopeOverride);

        FeatureDagEngine offline = FeatureDagEngine.init(
                intermediateConfigJson(), InitOptions.offline("mode-mismatch"));
        FeatureGenerationException modeMismatch = expectThrows(
                FeatureGenerationException.class,
                () -> offline.generate(new OnlineGenerateRequest(
                        "wrong-request",
                        Map.of(),
                        List.of(Map.of(
                                "raw_price", List.of(1.0),
                                "quality_score", List.of(1.0))))));
        assert modeMismatch.planId().equals("mode-mismatch") : modeMismatch.planId();
        assert modeMismatch.executionId().equals("wrong-request") : modeMismatch.executionId();

        String requiredPriceJson = intermediateConfigJson().replaceFirst(
                "\"dft\": 0.0", "\"dft\": null");
        FeatureDagEngine requiredPrice = FeatureDagEngine.init(
                requiredPriceJson, InitOptions.offline("required-price"));
        FeatureGenerationException missingInput = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generate(new OfflineGenerateRequest(
                        "missing-input", Map.of("quality_score", List.of(0.8)))));
        assert missingInput.getMessage().contains("raw_price") : missingInput.getMessage();

        Map<String, List<?>> explicitNullRow = new LinkedHashMap<>();
        explicitNullRow.put("raw_price", Collections.singletonList(null));
        explicitNullRow.put("quality_score", List.of(0.8));
        FeatureGenerationException explicitNull = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generate(new OfflineGenerateRequest(
                        "explicit-null", explicitNullRow)));
        assert !explicitNull.getMessage().contains("Missing source feature")
                : explicitNull.getMessage();

        String nullableOutputJson = """
                {
                  "feature_set_name":"nullable-array-output",
                  "version":"1",
                  "features":[
                    {"name":"source","raw_name":"source","type":"STRING","definition_type":"BASE",
                     "entity_scopes":["USER"],"value_shape":"SCALAR"},
                    {"name":"nullable_out","store_name":"nullable_out","type":"STRING",
                     "definition_type":"DERIVED","expression":"coalesce(source, null)",
                     "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR"}
                  ]
                }
                """;
        FeatureDagEngine nullableOutput = FeatureDagEngine.init(
                nullableOutputJson, InitOptions.offline("nullable-array-output"));
        GenerateResult nullableResult = nullableOutput.generate(new OfflineGenerateRequest(
                "nullable-output-row",
                Map.of("source", Collections.singletonList(null))));
        List<?> nullableValues = nullableResult.featureValues().get("nullable_out");
        assert nullableValues.size() == 1 && nullableValues.getFirst() == null
                : nullableResult.featureValues();

        ArrayList<Object> offlineValues = new ArrayList<>(List.of("original"));
        Map<String, List<?>> mutableOfflineRow = new LinkedHashMap<>();
        mutableOfflineRow.put("source", offlineValues);
        OfflineGenerateRequest offlineSnapshot = new OfflineGenerateRequest(
                "offline-snapshot", mutableOfflineRow);
        offlineValues.set(0, "changed");
        mutableOfflineRow.clear();
        assert offlineSnapshot.rowValues().get("source").equals(List.of("original"))
                : offlineSnapshot.rowValues();

        ArrayList<Object> sharedValues = new ArrayList<>(List.of("shared-original"));
        Map<String, List<?>> mutableShared = new LinkedHashMap<>();
        mutableShared.put("shared", sharedValues);
        ArrayList<Object> candidateValues = new ArrayList<>(List.of("candidate-original"));
        Map<String, List<?>> mutableCandidate = new LinkedHashMap<>();
        mutableCandidate.put("candidate", candidateValues);
        ArrayList<Map<String, List<?>>> mutableCandidates = new ArrayList<>();
        mutableCandidates.add(mutableCandidate);
        OnlineGenerateRequest onlineSnapshot = new OnlineGenerateRequest(
                "online-snapshot", mutableShared, mutableCandidates);
        sharedValues.set(0, "shared-changed");
        mutableShared.clear();
        candidateValues.set(0, "candidate-changed");
        mutableCandidate.clear();
        mutableCandidates.clear();
        assert onlineSnapshot.sharedValues().get("shared").equals(List.of("shared-original"))
                : onlineSnapshot.sharedValues();
        assert onlineSnapshot.candidates().size() == 1 : onlineSnapshot.candidates();
        assert onlineSnapshot.candidates().getFirst().get("candidate")
                .equals(List.of("candidate-original")) : onlineSnapshot.candidates();
    }

    private static void testFailFastArrayOutput() {
        String json = """
                {
                  "feature_set_name":"fail-fast-array-output",
                  "version":"1",
                  "features":[
                    {"name":"source","raw_name":"source","type":"DOUBLE","definition_type":"BASE",
                     "entity_scopes":["USER"],"value_shape":"SCALAR"},
                    {"name":"good_out","store_name":"good_out","type":"DOUBLE",
                     "definition_type":"DERIVED","expression":"coalesce(source, 0.0)",
                     "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR","order":1},
                    {"name":"bad_out","store_name":"bad_out","type":"DOUBLE",
                     "definition_type":"DERIVED","expression":"div_num(source, {\\\"divisor\\\":0})",
                     "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR","order":2}
                  ]
                }
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(
                json, InitOptions.offline("fail-fast-array-output"));
        FeatureGenerationException error = expectThrows(
                FeatureGenerationException.class,
                () -> engine.generate(new OfflineGenerateRequest(
                        "fail-fast-row", Map.of("source", List.of(2.0)))));
        assert error.getMessage().contains("divisor must not be zero") : error.getMessage();
    }

    private static void testDeclaredScopeIsValidatedBeforeOverride() {
        String invalidDeclaredScopeJson = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,\"entity_scopes\":[\"ITEM\"]",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,\"entity_scopes\":[\"OTHER\"]");
        InitOptions override = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("invalid-declared-scope")
                .rawFeatureScopes(Map.of("item_price", Set.of(EntityScope.ITEM)))
                .build();

        FeatureDagInitializationException error = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(invalidDeclaredScopeJson, override));
        assert error.getMessage().contains("OTHER") : error.getMessage();
    }

    private static void testBaseOutputPolicyIsValidated() {
        String invalidOutputPolicyJson = intermediateConfigJson().replaceFirst(
                "\"order\": 1", "\"order\": 1,\n                      \"output_policy\": \"BAD\"");

        FeatureDagInitializationException error = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(invalidOutputPolicyJson, InitOptions.offline("invalid-base-policy")));
        assert error.getMessage().contains("output_policy") : error.getMessage();
    }

    private static void testDerivedOutputPolicyDefaults() {
        assertDefaultDerivedOutputPolicy(null, "missing_output_policy", "missing_policy_output");
        assertDefaultDerivedOutputPolicy("   ", "blank_output_policy", "blank_policy_output");
    }

    private static void assertDefaultDerivedOutputPolicy(
            String outputPolicy,
            String featureName,
            String storeName) {
        String json = configWithDefaultDerivedOutputPolicy(outputPolicy, featureName, storeName);
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(json),
                ExecutionEnvironment.OFFLINE,
                Set.of(),
                Map.of());

        assert mapped.targetFeatures().equals(Set.of(featureName)) : mapped.targetFeatures();
        assert mapped.outputs().size() == 1 : mapped.outputs();
        assert mapped.outputs().getFirst().featureName().equals(featureName) : mapped.outputs();
        assert mapped.outputs().getFirst().storeName().equals(storeName) : mapped.outputs();

        FeatureDagEngine engine = FeatureDagEngine.init(
                json, InitOptions.offline("default-output-policy-" + featureName));
        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "default-output-policy-row-" + featureName,
                Map.of("source_value", List.of(41))));
        assert result.featureValues().equals(Map.of(storeName, List.of(42.0)))
                : result.featureValues();
    }

    private static String configWithDefaultDerivedOutputPolicy(
            String outputPolicy,
            String featureName,
            String storeName) {
        String policyField = outputPolicy == null ? "" : ",\"output_policy\":\"" + outputPolicy + "\"";
        return """
                {
                  "features": [
                    {"name":"source","raw_name":"source_value","type":"INT",
                     "definition_type":"BASE","entity_scopes":["USER"]},
                    {"name":"%s","store_name":"%s","type":"DOUBLE",
                     "definition_type":"DERIVED","expression":"add(source, 1)"%s}
                  ],
                  "feature_set_name":"default_output_policy","version":"1"
                }
                """.formatted(featureName, storeName, policyField);
    }

    private static void testDeclaredValueShapeAndScopeSemantics() {
        LogicalDag dag = buildDag(configWithDerivedShapeAndScopes("VECTOR", "[\"USER\", \"ITEM\"]"));
        assert dag.node(dag.featureOutputNodeIds().get("candidate_score")).valueShape()
                == ValueShape.CANDIDATE_VECTOR;
        assert dag.node("source:user_score").valueShape() == ValueShape.SCALAR;
        assert dag.node("source:user_history").valueShape() == ValueShape.SEQUENCE;

        String configWithoutShapes = configWithoutBaseValueShapes();
        FeatureSetConfig configWithoutShapesFixture = FeatureConfigLoader.load(configWithoutShapes);
        FeatureConfig userScore = configWithoutShapesFixture.features().stream()
                .filter(feature -> feature.name().equals("user_score"))
                .findFirst()
                .orElseThrow();
        FeatureConfig userHistory = configWithoutShapesFixture.features().stream()
                .filter(feature -> feature.name().equals("user_history"))
                .findFirst()
                .orElseThrow();
        assert userScore.valueShape() == null : userScore.valueShape();
        assert userHistory.valueShape() == null : userHistory.valueShape();

        LogicalDag inferredBaseShapes = buildDag(configWithoutShapes);
        assert inferredBaseShapes.node("source:user_score").valueShape() == ValueShape.SCALAR;
        assert inferredBaseShapes.node("source:user_history").valueShape() == ValueShape.SEQUENCE;

        DagBuildException shapeError = expectThrows(DagBuildException.class,
                () -> buildDag(configWithDerivedShapeAndScopes("SCALAR", "[\"USER\", \"ITEM\"]")));
        assert shapeError.getMessage().contains(
                "Declared value shape mismatch for feature candidate_score") : shapeError.getMessage();

        DagBuildException scopeError = expectThrows(DagBuildException.class,
                () -> buildDag(configWithDerivedShapeAndScopes("VECTOR", "[\"USER\"]")));
        assert scopeError.getMessage().contains(
                "Declared entity scopes mismatch for feature candidate_score") : scopeError.getMessage();

        IllegalArgumentException enumError = expectThrows(IllegalArgumentException.class,
                () -> FeatureConfigMapper.map(
                        FeatureConfigLoader.load(configWithValueShape("MATRIX")),
                        ExecutionEnvironment.ONLINE,
                        Set.of(),
                        Map.of()));
        assert enumError.getMessage().contains("Invalid value_shape for feature") : enumError.getMessage();
    }

    private static void testBaseMetadataDefaultBoundaries() {
        MappedFeatureSet defaulted = FeatureConfigMapper.map(
                FeatureConfigLoader.load(onlineConfigWithoutBaseMetadata("USER")),
                ExecutionEnvironment.ONLINE,
                Set.of(),
                Map.of(),
                Set.of(EntityScope.ITEM));
        FeatureDefinition defaultedBase = defaulted.definitions().stream()
                .filter(definition -> definition.name().equals("ad_type"))
                .findFirst()
                .orElseThrow();
        assert defaultedBase.entityScopes().equals(Set.of(EntityScope.ITEM))
                : defaultedBase.entityScopes();
        assert defaultedBase.declaredValueShape() == null
                : defaultedBase.declaredValueShape();

        MappedFeatureSet explicit = FeatureConfigMapper.map(
                FeatureConfigLoader.load(explicitBaseMetadataConfig()),
                ExecutionEnvironment.ONLINE,
                Set.of(),
                Map.of(),
                Set.of(EntityScope.ITEM));
        FeatureDefinition explicitBase = explicit.definitions().stream()
                .filter(definition -> definition.name().equals("explicit_base"))
                .findFirst()
                .orElseThrow();
        assert explicitBase.entityScopes().equals(Set.of(EntityScope.SCENE))
                : explicitBase.entityScopes();
        assert explicitBase.declaredValueShape() == ValueShape.SCALAR
                : explicitBase.declaredValueShape();

        IllegalArgumentException mapperError = expectThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigMapper.map(
                        FeatureConfigLoader.load(onlineConfigWithoutBaseMetadata("USER")),
                        ExecutionEnvironment.ONLINE,
                        Set.of(),
                        Map.of(),
                        Set.of()));
        assert mapperError.getMessage().contains("defaultBaseScopes must not be empty")
                : mapperError.getMessage();

        IllegalArgumentException optionsError = expectThrows(
                IllegalArgumentException.class,
                () -> InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .defaultRawFeatureScopes(Set.of())
                        .build());
        assert optionsError.getMessage().contains("default raw feature scopes must not be empty")
                : optionsError.getMessage();
    }

    private static String explicitBaseMetadataConfig() {
        return """
                {
                  "features": [
                    {"name":"explicit_base","raw_name":"explicit_base","type":"STRING",
                     "definition_type":"BASE","seq_max_length":10,
                     "entity_scopes":["SCENE"],"value_shape":"SCALAR"},
                    {"name":"explicit_output","type":"STRING","definition_type":"DERIVED",
                     "expression":"coalesce(explicit_base, \\\"missing\\\")","output_policy":"OUTPUT",
                     "entity_scopes":["SCENE"],"value_shape":"SCALAR"}
                  ],
                  "feature_set_name":"explicit_base_metadata","version":"1"
                }
                """;
    }

    private static LogicalDag buildDag(String json) {
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(json),
                ExecutionEnvironment.ONLINE,
                Set.of(),
                Map.of());
        return new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());
    }

    private static String configWithDerivedShapeAndScopes(String valueShape, String scopesJson) {
        return """
                {
                  "features": [
                    {"name":"user_score","raw_name":"user_score","type":"INT",
                     "entity_scopes":["USER"],"value_shape":"SCALAR"},
                    {"name":"user_history","raw_name":"user_history","type":"EVENT_SEQUENCE",
                     "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"item_score","raw_name":"item_score","type":"DOUBLE",
                     "entity_scopes":["ITEM"],"value_shape":"VECTOR"},
                    {"name":"candidate_score","type":"DOUBLE","definition_type":"DERIVED",
                     "expression":"coalesce(item_score, user_score, 0)","output_policy":"OUTPUT",
                     "entity_scopes":%s,"value_shape":"%s"},
                    {"name":"history_output","type":"EVENT_SEQUENCE","definition_type":"DERIVED",
                     "expression":"coalesce(user_history, user_history)","output_policy":"OUTPUT",
                     "entity_scopes":["USER"],"value_shape":"SEQUENCE"}
                  ],
                  "feature_set_name":"declared_shape","version":"1"
                }
                """.formatted(scopesJson, valueShape);
    }

    private static String configWithoutBaseValueShapes() {
        return configWithDerivedShapeAndScopes("VECTOR", "[\"USER\", \"ITEM\"]")
                .replaceFirst(",\"value_shape\":\"SCALAR\"", "")
                .replaceFirst(",\"value_shape\":\"SEQUENCE\"", "");
    }

    private static String configWithValueShape(String valueShape) {
        return configWithDerivedShapeAndScopes(valueShape, "[\"USER\", \"ITEM\"]");
    }

    private static void testDagBusinessSemantics() {
        LogicalDagBuilder builder = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard());
        List<FeatureDefinition> definitions = ExampleFeatures.definitions();

        LinkedHashSet<String> onlineTargets = linkedSet("same_industry_count", "final_score");
        LogicalDag online = builder.build(definitions, onlineTargets);
        assert online.featureOutputNodeIds().keySet().containsAll(Set.of(
                "same_industry_count", "same_industry_seq", "user_seq1", "item_industry",
                "final_score", "user_click_score", "user_click_count", "item_price_log", "item_price"))
                : online.featureOutputNodeIds();
        assert online.featureOutput("user_click_score").entityScopes().equals(Set.of(EntityScope.USER));
        assert online.featureOutput("item_price_log").entityScopes().equals(Set.of(EntityScope.ITEM));
        assert online.featureOutput("same_industry_count").entityScopes()
                .equals(Set.of(EntityScope.USER, EntityScope.ITEM));
        assert online.featureOutput("same_industry_seq").valueShape() == ValueShape.SEQUENCE;
        assert online.featureOutput("same_industry_count").valueShape() == ValueShape.SCALAR;

        LinkedHashSet<String> offlineTargets = linkedSet(
                "same_industry_count", "final_score", "user_click_score",
                "same_industry_seq", "item_price_log");
        LogicalDag offline = builder.build(definitions, offlineTargets);
        assert offline.nodes().keySet().containsAll(online.nodes().keySet());
        assert offline.nodes().keySet().stream()
                .filter("feature:user_click_score"::equals)
                .count() == 1 : "Common dependency must be represented by one feature output node";

        DagBuildException unknownFeature = expectThrows(
                DagBuildException.class,
                () -> builder.build(definitions, Set.of("not_defined")));
        assert unknownFeature.getMessage().contains("not_defined") : unknownFeature.getMessage();

        DagBuildException syntax = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_syntax", DataType.INT, "count(user_seq1"), Set.of("bad_syntax")));
        assert syntax.getMessage().contains("bad_syntax") : syntax.getMessage();

        DagBuildException unknownOperator = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_operator", DataType.INT,
                        "notRegistered(user_click_count)"), Set.of("bad_operator")));
        assert unknownOperator.getMessage().contains("notRegistered") : unknownOperator.getMessage();

        DagBuildException arity = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_arity", DataType.DOUBLE,
                        "normalize(user_click_count)"), Set.of("bad_arity")));
        assert arity.getMessage().contains("normalize") : arity.getMessage();

        List<FeatureDefinition> cyclic = new ArrayList<>(definitions);
        cyclic.add(FeatureDefinition.derived(
                "cycle_a", DataType.DOUBLE, "add(cycle_b, 1)", OutputPolicy.INTERNAL_ONLY));
        cyclic.add(FeatureDefinition.derived(
                "cycle_b", DataType.DOUBLE, "add(cycle_a, 1)", OutputPolicy.OUTPUT));
        DagBuildException cycle = expectThrows(
                DagBuildException.class,
                () -> builder.build(cyclic, Set.of("cycle_b")));
        assert cycle.getMessage().contains("cycle_a") && cycle.getMessage().contains("cycle_b")
                : cycle.getMessage();

        DagBuildException countScalar = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_count", DataType.INT,
                        "count(item_price)"), Set.of("bad_count")));
        assert countScalar.getMessage().contains("count")
                && countScalar.getMessage().contains("item_price") : countScalar.getMessage();

        DagBuildException declaredTypeMismatch = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_declared_type", DataType.INT,
                        "coalesce(item_industry, \"unknown\")"), Set.of("bad_declared_type")));
        assert declaredTypeMismatch.getMessage().contains("Declared type mismatch")
                && declaredTypeMismatch.getMessage().contains("bad_declared_type")
                : declaredTypeMismatch.getMessage();

        LogicalDag widened = builder.build(withDerived(
                definitions, "allowed_numeric_widening", DataType.DOUBLE,
                "round(item_price)"), Set.of("allowed_numeric_widening"));
        assert widened.featureOutput("allowed_numeric_widening").outputType() == DataType.INT
                : widened.featureOutput("allowed_numeric_widening").outputType();
    }

    private static void testExecutionStagesAndTargetSelection() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        PhysicalPlanner planner = new PhysicalPlanner();
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();

        LogicalDag onlineDag = builder.build(
                ExampleFeatures.definitions(), linkedSet("same_industry_count", "final_score"));
        PhysicalPlan plan = planner.plan(
                optimizer.analyze(onlineDag), ExecutionEnvironment.ONLINE, "stage-test");
        assert stageFor(plan, "source:user_click_count") == ExecutionStage.REQUEST_SHARED;
        assert stageFor(plan, "source:item_price") == ExecutionStage.CANDIDATE_BATCH;
        assert stageFor(plan, "feature:user_click_score") == ExecutionStage.REQUEST_SHARED;
        assert stageFor(plan, "feature:item_price_log") == ExecutionStage.CANDIDATE_BATCH;
        assert stageFor(plan, "feature:final_score") == ExecutionStage.CANDIDATE_BATCH;

        List<FeatureDefinition> sceneDefinitions = new ArrayList<>(ExampleFeatures.definitions());
        sceneDefinitions.add(FeatureDefinition.raw(
                "scene_hour", DataType.INT, EntityScope.SCENE, 0));
        sceneDefinitions.add(FeatureDefinition.derived(
                "scene_score", DataType.DOUBLE,
                "add(scene_hour, 1)", OutputPolicy.OUTPUT));
        sceneDefinitions.add(FeatureDefinition.derived(
                "user_scene_score", DataType.DOUBLE,
                "add(user_click_count, scene_hour)", OutputPolicy.OUTPUT));
        LogicalDag sceneDag = builder.build(
                sceneDefinitions, linkedSet("scene_score", "user_scene_score"));
        PhysicalPlan scenePlan = planner.plan(
                optimizer.analyze(sceneDag), ExecutionEnvironment.ONLINE, "scene-stage-test");
        assert sceneDag.featureOutput("scene_score").entityScopes().equals(Set.of(EntityScope.SCENE));
        assert sceneDag.featureOutput("user_scene_score").entityScopes()
                .equals(Set.of(EntityScope.USER, EntityScope.SCENE));
        assert stageFor(scenePlan, "feature:scene_score") == ExecutionStage.REQUEST_SHARED;
        assert stageFor(scenePlan, "feature:user_scene_score") == ExecutionStage.REQUEST_SHARED;
    }

    private static void testCandidateCardinalityAndDefaults() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("candidate-cardinality"));
        Map<String, List<?>> shared = Map.of(
                "user_click_count", List.of(12),
                "user_seq1", publicIndustrySequence());

        GenerateResult empty = engine.generate(new OnlineGenerateRequest(
                "zero-candidates", shared, List.of()));
        assert empty.candidateFeatureValues().isEmpty() : empty.candidateFeatureValues();

        GenerateResult single = engine.generate(new OnlineGenerateRequest(
                "one-candidate",
                shared,
                List.of(Map.of(
                        "item_industry", List.of("industry1"),
                        "item_price", List.of(20.0)))));
        assert single.candidateFeatureValues().size() == 1;
        assert scalarFeature(single.candidateFeatureValues().getFirst(),
                "same_industry_count").equals(3)
                : single.candidateFeatureValues();

        GenerateResult four = engine.generate(new OnlineGenerateRequest(
                "four-candidates", shared, fourCandidates()));
        assert four.candidateFeatureValues().stream()
                .map(values -> scalarFeature(values, "same_industry_count"))
                .toList().equals(List.of(3, 1, 3, 0)) : four.candidateFeatureValues();

        GenerateResult defaultPrice = engine.generate(new OnlineGenerateRequest(
                "default-price",
                shared,
                List.of(Map.of("item_industry", List.of("industry2")))));
        assert ((Number) scalarFeature(
                defaultPrice.candidateFeatureValues().getFirst(), "final_score"))
                .doubleValue() == 0.0 : defaultPrice.candidateFeatureValues();

        String requiredPriceConfig = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",");
        FeatureDagEngine requiredPrice = FeatureDagEngine.init(
                requiredPriceConfig, InitOptions.online("required-candidate-price"));
        FeatureGenerationException missingPrice = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generate(new OnlineGenerateRequest(
                        "missing-price",
                        shared,
                        List.of(Map.of("item_industry", List.of("industry1"))))));
        assert missingPrice.getMessage().contains("item_price") : missingPrice.getMessage();
    }

    private static void testCandidateVectorPreservesNullElements() {
        ArrayList<Object> values = new ArrayList<>();
        values.add(null);
        CandidateVectorValue vector = new CandidateVectorValue(values);
        values.set(0, "changed");

        assert vector.size() == 1 : vector.values();
        assert vector.valueAt(0) == null : vector.values();
        expectThrows(UnsupportedOperationException.class, () -> vector.values().set(0, "changed"));
    }

    private static void testEmptySequenceAndOfflineOutputSet() {
        FeatureDagEngine online = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("empty-sequence"));
        GenerateResult empty = online.generate(new OnlineGenerateRequest(
                "empty-sequence-request",
                Map.of("user_click_count", List.of(12), "user_seq1", List.of()),
                List.of(Map.of(
                        "item_industry", List.of("industry1"),
                        "item_price", List.of(20.0)))));
        assert scalarFeature(empty.candidateFeatureValues().getFirst(),
                "same_industry_count").equals(0)
                : empty.candidateFeatureValues();

        FeatureDagEngine offline = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.offline("offline-output-set"));
        GenerateResult result = offline.generate(new OfflineGenerateRequest(
                "offline-output-row",
                Map.of(
                        "user_click_count", List.of(12),
                        "user_seq1", publicIndustrySequence(),
                        "item_industry", List.of("industry1"),
                        "item_price", List.of(20.0))));
        assert result.featureValues().keySet().equals(
                linkedSet("same_industry_count", "final_score")) : result.featureValues();
        assert !result.featureValues().containsKey("same_industry_seq");
        assert !result.featureValues().containsKey("user_click_score");
        assert !result.featureValues().containsKey("item_price_log");
    }

    private static ExecutionStage stageFor(PhysicalPlan plan, String logicalNodeId) {
        return plan.nodes().stream()
                .filter(node -> node.logicalNodeIds().contains(logicalNodeId))
                .findFirst()
                .map(PhysicalNode::executionStage)
                .orElseThrow(() -> new AssertionError(
                        "Logical node is absent from physical plan: " + logicalNodeId));
    }

    private static List<Map<String, List<?>>> fourCandidates() {
        return List.of(
                Map.of("item_id", List.of("item1"), "item_industry", List.of("industry1"),
                        "item_price", List.of(20.0)),
                Map.of("item_id", List.of("item2"), "item_industry", List.of("industry2"),
                        "item_price", List.of(30.0)),
                Map.of("item_id", List.of("item3"), "item_industry", List.of("industry1"),
                        "item_price", List.of(40.0)),
                Map.of("item_id", List.of("item4"), "item_industry", List.of("industry4"),
                        "item_price", List.of(50.0)));
    }

    private static List<Map<String, Object>> naturalFourCandidates() {
        return List.of(
                Map.of("item_id", "item1", "item_industry", "industry1", "item_price", 20.0),
                Map.of("item_id", "item2", "item_industry", "industry2", "item_price", 30.0),
                Map.of("item_id", "item3", "item_industry", "industry1", "item_price", 40.0),
                Map.of("item_id", "item4", "item_industry", "industry4", "item_price", 50.0));
    }

    private static void testCandidateDeduplicationAndFusion() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
        PhysicalPlanner planner = new PhysicalPlanner();
        DagRuntime runtime = new DagRuntime(operators);

        LogicalDag countDag = builder.build(
                ExampleFeatures.definitions(), Set.of("same_industry_count"));
        PhysicalPlan onlinePlan = planner.plan(
                optimizer.analyze(countDag), ExecutionEnvironment.ONLINE, "dedup-four");
        PhysicalNode specialized = onlinePlan.nodes().stream()
                .filter(node -> node.executorType() == ExecutorType.SPECIALIZED)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Online count plan should fuse extraction and count"));
        Set<String> expectedConsumedNodeIds = countDag.nodes().values().stream()
                .filter(node -> node.nodeId().equals("feature:same_industry_seq")
                        || node instanceof OperatorNode operator
                        && (operator.operatorName().equals("extractIndustry")
                        || operator.operatorName().equals("count")))
                .map(LogicalNode::nodeId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assert new LinkedHashSet<>(specialized.logicalNodeIds()).equals(expectedConsumedNodeIds)
                : "expected=" + expectedConsumedNodeIds
                + ", actual=" + specialized.logicalNodeIds();

        ExecutionResult result = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineRequest(
                        "dedup-four-request",
                        Map.of("user_seq1", sequence()),
                        naturalFourCandidates()));
        CandidateVectorValue counts = (CandidateVectorValue) result.feature("same_industry_count");
        assert counts.values().equals(List.of(3, 1, 3, 0)) : counts.values();
        assert result.nodeStates().values().stream()
                .anyMatch(state -> state.dedupInputCount() == 4 && state.uniqueInputCount() == 3)
                : "Expected candidate industry dedup 4 -> 3";

        List<Map<String, Object>> reordered = List.of(
                Map.of("item_id", "item3", "item_industry", "industry1", "item_price", 40.0),
                Map.of("item_id", "item2", "item_industry", "industry2", "item_price", 30.0),
                Map.of("item_id", "item1", "item_industry", "industry1", "item_price", 20.0));
        ExecutionResult reorderedResult = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineRequest(
                        "dedup-reordered-request",
                        Map.of("user_seq1", sequence()),
                        reordered));
        CandidateVectorValue reorderedCounts =
                (CandidateVectorValue) reorderedResult.feature("same_industry_count");
        assert reorderedCounts.values().equals(List.of(3, 1, 3)) : reorderedCounts.values();

        LogicalDag transformDag = builder.build(
                ExampleFeatures.definitions(), linkedSet("same_industry_seq", "same_industry_count"));
        PhysicalPlan offlinePlan = planner.plan(
                optimizer.analyze(transformDag), ExecutionEnvironment.OFFLINE, "view-not-fused");
        assert offlinePlan.nodes().stream()
                .noneMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "An observable sequence output must prevent count fusion";
        ExecutionResult offline = runtime.execute(
                offlinePlan,
                ExecutionContext.offlineRow(
                        "view-not-fused-row",
                        Map.of("user_seq1", sequence(), "item_industry", "industry3")));
        SequenceView view = (SequenceView) offline.feature("same_industry_seq");
        assert view.size() == 2 : view.size();
        assert ((Number) offline.feature("same_industry_count").raw()).intValue() == 2;
        assert view.baseBlock().sequenceId().equals("seq-test") : view.baseBlock().sequenceId();
    }

    private static void testOnlineBatchSpecializedGrouping() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), operators).build(
                ExampleFeatures.definitions(), Set.of("same_industry_count"));
        PhysicalPlan plan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(dag),
                ExecutionEnvironment.ONLINE,
                "online-batch-specialized");
        PhysicalNode specialized = plan.nodes().stream()
                .filter(node -> node.executorType() == ExecutorType.SPECIALIZED)
                .findFirst()
                .orElseThrow(() -> new AssertionError(PhysicalPlanPrinter.print(plan)));

        SequenceBlock secondSequence = new SequenceBlock(
                "online-batch-sequence-2",
                1L,
                List.of(
                        new SequenceEvent("b-1", "industry2", 3L, "click", 1.0),
                        new SequenceEvent("b-2", "industry2", 2L, "click", 1.0),
                        new SequenceEvent("b-3", "industry3", 1L, "click", 1.0)));
        ExecutionContext context = ExecutionContext.onlineBatch(
                "specialized-batch",
                List.of("specialized-user-a", "specialized-user-b"),
                List.of(
                        Map.of("user_seq1", sequence()),
                        Map.of("user_seq1", secondSequence)),
                List.of(
                        List.of(
                                Map.of("item_industry", "industry1"),
                                Map.of("item_industry", "industry1")),
                        List.of(
                                Map.of("item_industry", "industry1"),
                                Map.of("item_industry", "industry2"))));
        ExecutionResult execution = new DagRuntime(operators).execute(plan, context);
        CandidateBatchValue counts =
                (CandidateBatchValue) execution.feature("same_industry_count");

        assert counts.values().equals(List.of(3, 3, 0, 2)) : counts.values();
        assert context.candidateGroupStart(0) == 0 : context.candidateGroupStart(0);
        assert context.candidateGroupEnd(0) == 2 : context.candidateGroupEnd(0);
        assert context.candidateGroupStart(1) == 2 : context.candidateGroupStart(1);
        assert context.candidateGroupEnd(1) == 4 : context.candidateGroupEnd(1);
        assert execution.nodeStates().values().stream().anyMatch(state ->
                state.dedupInputCount() == 4 && state.uniqueInputCount() == 3)
                : "Expected per-group key dedup counts 4 -> 3";
        var specializedState = execution.nodeStates().get(specialized.physicalNodeId());
        assert specializedState.operatorInvocationKind()
                == OperatorInvocationKind.SPECIALIZED;
        assert specializedState.batchDomain() == null : specializedState.batchDomain();
        assert specializedState.batchRowCount() == 0 : specializedState.batchRowCount();
        assert execution.nodeStates().entrySet().stream().allMatch(entry ->
                entry.getValue() != context.nodeStates().get(entry.getKey()))
                : "ExecutionResult must expose node-state snapshots, not live context state";

        SequenceBlock sharedSequence = sequence();
        ExecutionResult isolated = new DagRuntime(operators).execute(
                plan,
                ExecutionContext.onlineBatch(
                        "specialized-cache-isolation",
                        List.of("isolation-user-a", "isolation-user-b"),
                        List.of(
                                Map.of("user_seq1", sharedSequence),
                                Map.of("user_seq1", sharedSequence)),
                        List.of(
                                List.of(Map.of("item_industry", "industry1")),
                                List.of(Map.of("item_industry", "industry1")))));
        assert !isolated.nodeStates().get(specialized.physicalNodeId()).cacheHit()
                : "Sequence index/count cache must not cross online batch groups";
    }

    private static void testDirectNestedCountIndustryFusion() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
        PhysicalPlanner planner = new PhysicalPlanner();
        DagRuntime runtime = new DagRuntime(operators);

        List<FeatureDefinition> directDefinitions = List.of(
                FeatureDefinition.raw("user_seq1", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
                FeatureDefinition.derived(
                        "same_industry_count",
                        DataType.INT,
                        "count(extractIndustry(user_seq1, item_industry))",
                        OutputPolicy.OUTPUT));
        LogicalDag directDag = builder.build(directDefinitions, Set.of("same_industry_count"));
        PhysicalPlan onlinePlan = planner.plan(
                optimizer.analyze(directDag), ExecutionEnvironment.ONLINE, "direct-nested-online");
        assert onlinePlan.nodes().stream()
                .anyMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "Direct nested count/extractIndustry should fuse online";

        ExecutionResult result = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineRequest(
                        "direct-nested-request",
                        Map.of("user_seq1", sequence()),
                        naturalFourCandidates()));
        CandidateVectorValue counts = (CandidateVectorValue) result.feature("same_industry_count");
        assert counts.values().equals(List.of(3, 1, 3, 0)) : counts.values();
        assert result.nodeStates().values().stream()
                .anyMatch(state -> state.dedupInputCount() == 4 && state.uniqueInputCount() == 3)
                : "Direct nested fusion should deduplicate candidate industries";

        PhysicalPlan offlinePlan = planner.plan(
                optimizer.analyze(directDag), ExecutionEnvironment.OFFLINE, "direct-nested-offline");
        assert offlinePlan.nodes().stream()
                .noneMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "Direct nested count/extractIndustry must remain unfused offline";

        List<FeatureDefinition> sharedDefinitions = List.of(
                FeatureDefinition.raw("user_seq1", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
                FeatureDefinition.derived(
                        "same_industry_seq",
                        DataType.EVENT_SEQUENCE,
                        "extractIndustry(user_seq1, item_industry)",
                        OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "same_industry_count",
                        DataType.INT,
                        "count(extractIndustry(user_seq1, item_industry))",
                        OutputPolicy.OUTPUT));
        LogicalDag sharedDag = builder.build(
                sharedDefinitions, linkedSet("same_industry_seq", "same_industry_count"));
        PhysicalPlan sharedPlan = planner.plan(
                optimizer.analyze(sharedDag), ExecutionEnvironment.ONLINE, "shared-extract-online");
        assert sharedPlan.nodes().stream()
                .noneMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "A shared extractIndustry operator must not be eliminated by fusion";
    }

    private static void testObservableExtractIndustryPreventsFusion() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
        PhysicalPlanner planner = new PhysicalPlanner();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("user_seq1", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
                FeatureDefinition.derived(
                        "same_industry_count",
                        DataType.INT,
                        "count(extractIndustry(user_seq1, item_industry))",
                        OutputPolicy.OUTPUT));
        LogicalDag directDag = builder.build(definitions, Set.of("same_industry_count"));
        String extractNodeId = directDag.nodes().values().stream()
                .filter(node -> node instanceof com.example.featuredag.logical.OperatorNode operator
                        && "extractIndustry".equals(operator.operatorName()))
                .map(LogicalNode::nodeId)
                .findFirst()
                .orElseThrow();
        Set<String> roots = new LinkedHashSet<>(directDag.rootNodeIds());
        roots.add(extractNodeId);
        LogicalDag observableExtractDag = new LogicalDag(
                directDag.nodes(), roots, directDag.featureOutputNodeIds(), directDag.topologicalOrder());

        PhysicalPlan plan = planner.plan(
                optimizer.analyze(observableExtractDag), ExecutionEnvironment.ONLINE, "observable-extract");
        assert plan.nodes().stream()
                .noneMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "An observable extractIndustry operator must prevent count fusion";
        assert plan.nodes().stream()
                .anyMatch(node -> node.logicalNodeIds().equals(List.of(extractNodeId))
                        && node.executorType() == ExecutorType.GENERIC_OPERATOR)
                : "Observable extractIndustry must be planned generically";
    }

    private static void testFusedIndustryCountsRespectSequenceViews() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "first_view", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "second_view", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
                        FeatureDefinition.derived(
                                "first_count", DataType.INT,
                                "count(extractIndustry(first_view, item_industry))",
                                OutputPolicy.OUTPUT),
                        FeatureDefinition.derived(
                                "second_count", DataType.INT,
                                "count(extractIndustry(second_view, item_industry))",
                                OutputPolicy.OUTPUT)),
                linkedSet("first_count", "second_count"));
        PhysicalPlan plan = new PhysicalPlanner().plan(
                new LogicalDagOptimizer().analyze(dag),
                ExecutionEnvironment.ONLINE,
                "view-aware-fusion");
        assert plan.nodes().stream()
                .filter(node -> node.executorType() == ExecutorType.SPECIALIZED)
                .count() == 2 : PhysicalPlanPrinter.print(plan);

        SequenceBlock base = sequence();
        SequenceView first = SequenceView.slice(base, 0, 2);
        SequenceView second = SequenceView.slice(base, 2, 6);
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.onlineRequest(
                        "view-aware-request",
                        Map.of("first_view", first, "second_view", second),
                        List.of(Map.of("item_industry", "industry1"))));

        CandidateVectorValue firstCount =
                (CandidateVectorValue) result.feature("first_count");
        CandidateVectorValue secondCount =
                (CandidateVectorValue) result.feature("second_count");
        assert firstCount.values().equals(List.of(1)) : firstCount.values();
        assert secondCount.values().equals(List.of(2)) : secondCount.values();
    }

    private static void testFusionMatchesRegisteredSemanticsInsteadOfOperatorNames() {
        OperatorRegistry registry = new OperatorRegistry()
                .register(keyedSequenceFilterOperator("select_by_registered_key", true))
                .register(sequenceCardinalityOperator("registered_cardinality", true));

        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "history", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "candidate_key", DataType.STRING, EntityScope.ITEM, "unknown"),
                        FeatureDefinition.derived(
                                "registered_count",
                                DataType.INT,
                                "registered_cardinality(select_by_registered_key(history, candidate_key))",
                                OutputPolicy.OUTPUT)),
                Set.of("registered_count"));
        PhysicalPlan plan = new PhysicalPlanner(registry, PhysicalRewriteRegistry.standard()).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.ONLINE,
                "registered-semantics");
        PhysicalNode specialized = plan.nodes().stream()
                .filter(node -> node.executorType() == ExecutorType.SPECIALIZED)
                .findFirst()
                .orElseThrow(() -> new AssertionError(PhysicalPlanPrinter.print(plan)));
        assert specialized.executorId().equals(PhysicalExecutorIds.SEQUENCE_KEY_COUNT)
                : specialized.executorId();

        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.onlineRequest(
                        "registered-semantics-request",
                        Map.of("history", sequence()),
                        naturalFourCandidates().stream()
                                .map(candidate -> Map.<String, Object>of(
                                        "candidate_key", candidate.get("item_industry")))
                                .toList()));
        CandidateVectorValue counts = (CandidateVectorValue) result.feature("registered_count");
        assert counts.values().equals(List.of(3, 1, 3, 0)) : counts.values();

        OperatorRegistry undeclaredRegistry = new OperatorRegistry()
                .register(keyedSequenceFilterOperator("select_without_semantic", false))
                .register(sequenceCardinalityOperator("cardinality_without_semantic", false));
        LogicalDag undeclaredDag = new LogicalDagBuilder(
                new ExpressionParser(), undeclaredRegistry).build(
                List.of(
                        FeatureDefinition.raw(
                                "history", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "candidate_key", DataType.STRING, EntityScope.ITEM, "unknown"),
                        FeatureDefinition.derived(
                                "unmatched_count",
                                DataType.INT,
                                "cardinality_without_semantic("
                                        + "select_without_semantic(history, candidate_key))",
                                OutputPolicy.OUTPUT)),
                Set.of("unmatched_count"));
        PhysicalPlan undeclaredPlan = new PhysicalPlanner(
                undeclaredRegistry, PhysicalRewriteRegistry.standard()).plan(
                new LogicalDagOptimizer(undeclaredRegistry).analyze(undeclaredDag),
                ExecutionEnvironment.ONLINE,
                "undeclared-semantics");
        assert undeclaredPlan.nodes().stream()
                .noneMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "Operators without declared semantics must not match rewrite rules: "
                + PhysicalPlanPrinter.print(undeclaredPlan);
    }

    private static OperatorDefinition keyedSequenceFilterOperator(
            String name,
            boolean declareSemantic) {
        return new OperatorDefinition() {
            @Override public String name() { return name; }
            @Override public int minArguments() { return 2; }
            @Override public int maxArguments() { return 2; }
            @Override public boolean deterministic() { return true; }
            @Override public boolean parameterized() { return false; }
            @Override public boolean supportsSequenceView() { return true; }
            @Override public long estimatedCost() { return 1_000L; }
            @Override public List<com.example.featuredag.operator.OperatorSemantic> semantics() {
                return declareSemantic
                        ? List.of(new KeyedSequenceFilterSemantic(
                                0, 1, SequenceKeyDomains.INDUSTRY))
                        : List.of();
            }
            @Override public OperatorInference infer(List<OperatorInputMetadata> inputs) {
                return new OperatorInference(
                        DataType.EVENT_SEQUENCE,
                        unionEntityScopes(inputs),
                        ValueShape.SEQUENCE);
            }
            @Override public Object evaluate(List<Object> arguments) {
                return SequenceView.filterByIndustry(
                        (SequenceValue) arguments.get(0),
                        String.valueOf(arguments.get(1)));
            }
        };
    }

    private static OperatorDefinition sequenceCardinalityOperator(
            String name,
            boolean declareSemantic) {
        return new OperatorDefinition() {
            @Override public String name() { return name; }
            @Override public int minArguments() { return 1; }
            @Override public int maxArguments() { return 1; }
            @Override public boolean deterministic() { return true; }
            @Override public boolean parameterized() { return false; }
            @Override public boolean supportsSequenceView() { return true; }
            @Override public long estimatedCost() { return 1_000L; }
            @Override public List<com.example.featuredag.operator.OperatorSemantic> semantics() {
                return declareSemantic
                        ? List.of(new SequenceCardinalitySemantic(0))
                        : List.of();
            }
            @Override public OperatorInference infer(List<OperatorInputMetadata> inputs) {
                return new OperatorInference(
                        DataType.INT,
                        unionEntityScopes(inputs),
                        ValueShape.SCALAR);
            }
            @Override public Object evaluate(List<Object> arguments) {
                return ((SequenceValue) arguments.getFirst()).size();
            }
        };
    }

    private static void testGenericCandidateCacheUsesOperatorTraits() {
        AtomicInteger cachedCalls = new AtomicInteger();
        AtomicInteger uncachedCalls = new AtomicInteger();
        OperatorRegistry registry = new OperatorRegistry()
                .register(echoOperator("cached_echo", true, cachedCalls))
                .register(echoOperator("uncached_echo", false, uncachedCalls));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "candidate_key", DataType.STRING, EntityScope.ITEM, null),
                        FeatureDefinition.derived(
                                "cached_value", DataType.STRING,
                                "cached_echo(candidate_key)", OutputPolicy.OUTPUT),
                        FeatureDefinition.derived(
                                "uncached_value", DataType.STRING,
                                "uncached_echo(candidate_key)", OutputPolicy.OUTPUT)),
                linkedSet("cached_value", "uncached_value"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.ONLINE,
                "generic-candidate-cache");
        PhysicalNode cachedNode = plan.nodes().stream()
                .filter(node -> "cached_echo".equals(node.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        PhysicalNode uncachedNode = plan.nodes().stream()
                .filter(node -> "uncached_echo".equals(node.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        assert cachedNode.cachePolicy() == CachePolicy.CANDIDATE_KEY
                : PhysicalPlanPrinter.print(plan);
        assert uncachedNode.cachePolicy() == CachePolicy.NONE
                : PhysicalPlanPrinter.print(plan);
        assert cachedNode.executorConfig().get("batchKernelKind")
                .equals(BatchKernelKind.SCALAR_ADAPTER.name())
                : PhysicalPlanPrinter.print(plan);
        assert uncachedNode.executorConfig().get("batchKernelKind")
                .equals(BatchKernelKind.SCALAR_ADAPTER.name())
                : PhysicalPlanPrinter.print(plan);

        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.onlineRequest(
                        "generic-candidate-cache-request",
                        Map.of(),
                        List.of(
                                Map.of("candidate_key", "A"),
                                Map.of("candidate_key", "B"),
                                Map.of("candidate_key", "A"),
                                Map.of("candidate_key", "C"))));
        assert cachedCalls.get() == 3 : cachedCalls.get();
        assert uncachedCalls.get() == 4 : uncachedCalls.get();
        assert ((CandidateVectorValue) result.feature("cached_value"))
                .values().equals(List.of("A", "B", "A", "C"))
                : result.feature("cached_value").raw();
        assert result.nodeStates().get(cachedNode.physicalNodeId()).dedupInputCount() == 4;
        assert result.nodeStates().get(cachedNode.physicalNodeId()).uniqueInputCount() == 3;
        var cachedState = result.nodeStates().get(cachedNode.physicalNodeId());
        assert cachedState.operatorInvocationKind()
                == OperatorInvocationKind.BATCH_SCALAR_ADAPTER;
        assert cachedState.batchDomain() == BatchDomain.ONLINE_CANDIDATE;
        assert cachedState.batchRowCount() == 3 : cachedState.batchRowCount();
        var uncachedState = result.nodeStates().get(uncachedNode.physicalNodeId());
        assert uncachedState.operatorInvocationKind()
                == OperatorInvocationKind.BATCH_SCALAR_ADAPTER;
        assert uncachedState.batchDomain() == BatchDomain.ONLINE_CANDIDATE;
        assert uncachedState.batchRowCount() == 4 : uncachedState.batchRowCount();
        CacheStats requestCache = result.nodeStates()
                .get(cachedNode.physicalNodeId())
                .cacheStats()
                .get(CacheKind.CANDIDATE_KEY);
        assert requestCache.lookups() == 3 : requestCache;
        assert requestCache.hits() == 0 : requestCache;
        assert requestCache.misses() == 3 : requestCache;
        assert requestCache.puts() == 3 : requestCache;
        assert requestCache.hitRate() == 0.0 : requestCache;
        assert result.cacheStats().get(CacheKind.CANDIDATE_KEY).equals(requestCache)
                : result.cacheStats();

        cachedCalls.set(0);
        uncachedCalls.set(0);
        ExecutionResult grouped = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.onlineBatch(
                        "generic-candidate-cache-batch",
                        List.of("cache-user-a", "cache-user-b"),
                        List.of(Map.of(), Map.of()),
                        List.of(
                                List.of(
                                        Map.of("candidate_key", "A"),
                                        Map.of("candidate_key", "A")),
                                List.of(
                                        Map.of("candidate_key", "A"),
                                        Map.of("candidate_key", "A")))));
        assert grouped.feature("cached_value") instanceof CandidateBatchValue
                : grouped.feature("cached_value").getClass();
        assert cachedCalls.get() == 2 : "Cache must be isolated by group: " + cachedCalls.get();
        assert uncachedCalls.get() == 4 : uncachedCalls.get();
        assert ((CandidateBatchValue) grouped.feature("cached_value"))
                .values().equals(List.of("A", "A", "A", "A"))
                : grouped.feature("cached_value").raw();
        assert grouped.nodeStates().get(cachedNode.physicalNodeId()).dedupInputCount() == 4;
        assert grouped.nodeStates().get(cachedNode.physicalNodeId()).uniqueInputCount() == 2;
        var groupedCachedState = grouped.nodeStates().get(cachedNode.physicalNodeId());
        assert groupedCachedState.operatorInvocationKind()
                == OperatorInvocationKind.BATCH_SCALAR_ADAPTER;
        assert groupedCachedState.batchDomain() == BatchDomain.ONLINE_CANDIDATE;
        assert groupedCachedState.batchRowCount() == 2 : groupedCachedState.batchRowCount();
        CacheStats groupedCache = grouped.nodeStates()
                .get(cachedNode.physicalNodeId())
                .cacheStats()
                .get(CacheKind.CANDIDATE_KEY);
        assert groupedCache.lookups() == 2 : groupedCache;
        assert groupedCache.hits() == 0 : groupedCache;
        assert groupedCache.misses() == 2 : groupedCache;
        assert groupedCache.puts() == 2 : groupedCache;
        assert grouped.cacheStats().get(CacheKind.CANDIDATE_KEY).equals(groupedCache)
                : grouped.cacheStats();
    }

    private static OperatorDefinition echoOperator(
            String name,
            boolean deterministic,
            AtomicInteger calls) {
        return new OperatorDefinition() {
            @Override public String name() { return name; }
            @Override public int minArguments() { return 1; }
            @Override public int maxArguments() { return 1; }
            @Override public boolean deterministic() { return deterministic; }
            @Override public boolean parameterized() { return false; }
            @Override public boolean supportsSequenceView() { return false; }
            @Override public long estimatedCost() { return 1_000L; }
            @Override public OperatorInference infer(List<OperatorInputMetadata> inputs) {
                OperatorInputMetadata input = inputs.getFirst();
                return new OperatorInference(
                        input.outputType(), input.entityScopes(), ValueShape.SCALAR);
            }
            @Override public Object evaluate(List<Object> arguments) {
                calls.incrementAndGet();
                return arguments.getFirst();
            }
        };
    }

    private static final class NativeBatchProbeOperator
            implements OperatorDefinition, BatchOperatorKernel {
        private final AtomicInteger singleCalls;
        private final AtomicInteger batchCalls;

        private NativeBatchProbeOperator(
                AtomicInteger singleCalls,
                AtomicInteger batchCalls) {
            this.singleCalls = singleCalls;
            this.batchCalls = batchCalls;
        }

        @Override public String name() { return "native_batch_probe"; }
        @Override public int minArguments() { return 2; }
        @Override public int maxArguments() { return 2; }
        @Override public boolean deterministic() { return true; }
        @Override public boolean parameterized() { return false; }
        @Override public boolean supportsSequenceView() { return false; }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            Set<EntityScope> scopes = new LinkedHashSet<>();
            for (OperatorInputMetadata input : inputs) scopes.addAll(input.entityScopes());
            return new OperatorInference(DataType.STRING, scopes, ValueShape.SCALAR);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            singleCalls.incrementAndGet();
            return arguments.getFirst() + ":" + arguments.getLast();
        }

        @Override
        public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
            batchCalls.incrementAndGet();
            List<Object> values = new ArrayList<>(call.rowCount());
            for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
                if (Objects.equals(
                        call.arguments().getLast().valueAt(rowIndex), "bad")) {
                    throw new BatchOperatorEvaluationException(
                            rowIndex,
                            new IllegalArgumentException(
                                    "native batch probe rejected bad item"));
                }
                values.add(call.arguments().getFirst().valueAt(rowIndex)
                        + ":" + call.arguments().getLast().valueAt(rowIndex));
            }
            return new BatchOperatorResult(new ListBatchColumn(values));
        }
    }

    private record NativeBatchCase(
            String operatorName,
            List<List<Object>> rows) {
    }

    private record TestBatchLayout(int rowCount) implements BatchLayout {
        @Override public BatchDomain domain() { return BatchDomain.OFFLINE_ROW; }
        @Override public int groupIndexAt(int rowIndex) { return -1; }
        @Override public int indexInGroupAt(int rowIndex) { return rowIndex; }
    }

    private static void testSequenceSelectionStrategies() {
        SequenceBlock block = sequence();
        SequenceView range = SequenceView.slice(block, 2, 5);
        assert range.selection() instanceof RangeSelection : range.selection();

        SequenceBlock sparseBlock = selectionBlock(Set.of(1, 8));
        SequenceView sparse = SequenceView.filterByIndustry(sparseBlock, "keep");
        assert sparse.selection() instanceof IndexSelection : sparse.selection();
        assert sparse.baseIndexAt(0) == 1 && sparse.baseIndexAt(1) == 8;

        SequenceBlock denseBlock = selectionBlock(Set.of(0, 2, 3, 5, 6, 8));
        SequenceView dense = SequenceView.filterByIndustry(denseBlock, "keep");
        assert dense.selection() instanceof BitmapSelection : dense.selection();
        assert dense.size() == 6 : dense.size();

        SequenceView chained = SequenceView.slice(
                SequenceView.filterByIndustry(block, "industry1"), 1, 3);
        assert chained.selection() instanceof IndexSelection : chained.selection();
        assert chained.baseBlock() == block : "View chains must retain the original block";
        assert chained.baseIndexAt(0) == 2 : chained.baseIndexAt(0);
        assert chained.baseIndexAt(1) == 4 : chained.baseIndexAt(1);
    }

    private static SequenceBlock selectionBlock(Set<Integer> keepIndices) {
        List<SequenceEvent> events = IntStream.range(0, 10)
                .mapToObj(index -> new SequenceEvent(
                        "selection-" + index,
                        keepIndices.contains(index) ? "keep" : "drop",
                        index,
                        "view",
                        1.0))
                .toList();
        return new SequenceBlock("selection-block", 1L, events);
    }

    private static void testOfflineOnlineConsistency() {
        FeatureDagEngine offline = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.offline("consistency-offline"));
        FeatureDagEngine online = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("consistency-online"));
        Map<String, List<?>> shared = Map.of(
                "user_click_count", List.of(12),
                "user_seq1", publicIndustrySequence());

        Map<String, Map<String, List<?>>> offlineByItemId = new LinkedHashMap<>();
        for (Map<String, List<?>> candidate : fourCandidates()) {
            GenerateResult result = offline.generate(new OfflineGenerateRequest(
                    "offline-" + candidate.get("item_id").getFirst(),
                    mergedRow(shared, candidate)));
            offlineByItemId.put(
                    String.valueOf(candidate.get("item_id").getFirst()), result.featureValues());
        }

        GenerateResult onlineBatch = online.generate(new OnlineGenerateRequest(
                "consistency-batch", shared, fourCandidates()));
        for (int index = 0; index < fourCandidates().size(); index++) {
            String itemId = String.valueOf(
                    fourCandidates().get(index).get("item_id").getFirst());
            assertFeatureValuesEqual(
                    offlineByItemId.get(itemId),
                    onlineBatch.candidateFeatureValues().get(index));
        }

        List<Map<String, List<?>>> reordered = List.of(
                fourCandidates().get(2),
                fourCandidates().get(1),
                fourCandidates().get(0));
        GenerateResult reorderedBatch = online.generate(new OnlineGenerateRequest(
                "consistency-reordered", shared, reordered));
        for (int index = 0; index < reordered.size(); index++) {
            String itemId = String.valueOf(reordered.get(index).get("item_id").getFirst());
            assertFeatureValuesEqual(
                    offlineByItemId.get(itemId),
                    reorderedBatch.candidateFeatureValues().get(index));
        }

        Map<String, List<?>> missingUserShared = Map.of(
                "user_seq1", publicIndustrySequence());
        Map<String, List<?>> firstCandidate = fourCandidates().getFirst();
        GenerateResult offlineDefault = offline.generate(new OfflineGenerateRequest(
                "consistency-default-offline",
                mergedRow(missingUserShared, firstCandidate)));
        GenerateResult onlineDefault = online.generate(new OnlineGenerateRequest(
                "consistency-default-online",
                missingUserShared,
                List.of(firstCandidate)));
        assertFeatureValuesEqual(
                offlineDefault.featureValues(),
                onlineDefault.candidateFeatureValues().getFirst());

        Map<String, List<?>> emptySequenceShared = Map.of(
                "user_click_count", List.of(12),
                "user_seq1", List.of());
        GenerateResult offlineEmpty = offline.generate(new OfflineGenerateRequest(
                "consistency-empty-offline",
                mergedRow(emptySequenceShared, firstCandidate)));
        GenerateResult onlineEmpty = online.generate(new OnlineGenerateRequest(
                "consistency-empty-online",
                emptySequenceShared,
                List.of(firstCandidate)));
        assertFeatureValuesEqual(
                offlineEmpty.featureValues(),
                onlineEmpty.candidateFeatureValues().getFirst());
        assert scalarFeature(offlineEmpty.featureValues(), "same_industry_count").equals(0)
                : offlineEmpty.featureValues();
    }

    private static Map<String, List<?>> mergedRow(
            Map<String, List<?>> shared,
            Map<String, List<?>> candidate) {
        Map<String, List<?>> row = new LinkedHashMap<>(shared);
        candidate.forEach((key, value) -> {
            if (!key.equals("item_id")) row.put(key, value);
        });
        return row;
    }

    private static void assertFeatureValuesEqual(
            Map<String, List<?>> offline,
            Map<String, List<?>> online) {
        assert scalarFeature(offline, "same_industry_count")
                .equals(scalarFeature(online, "same_industry_count"))
                : "offline=" + offline + ", online=" + online;
        double offlineScore = ((Number) scalarFeature(offline, "final_score")).doubleValue();
        double onlineScore = ((Number) scalarFeature(online, "final_score")).doubleValue();
        assert Math.abs(offlineScore - onlineScore) <= 1e-9
                : "offline=" + offlineScore + ", online=" + onlineScore;
    }

    private static Object scalarFeature(Map<String, List<?>> values, String name) {
        List<?> featureValues = values.get(name);
        assert featureValues != null : "Missing feature " + name + " in " + values;
        assert featureValues.size() == 1 : "Expected scalar array for " + name + ": " + featureValues;
        return featureValues.getFirst();
    }

    private static List<String> publicIndustrySequence() {
        return List.of(
                "industry1", "industry2", "industry1",
                "industry3", "industry1", "industry3");
    }

    private static List<Map<String, Object>> publicEventSequence() {
        return List.of(
                Map.of(
                        "itemId", "h1", "industryId", "industry1",
                        "timestamp", 1L, "eventType", "click", "value", 1.0),
                Map.of(
                        "itemId", "h2", "industryId", "industry2",
                        "timestamp", 2L, "eventType", "click", "value", 1.0),
                Map.of(
                        "itemId", "h3", "industryId", "industry1",
                        "timestamp", 3L, "eventType", "view", "value", 1.0),
                Map.of(
                        "itemId", "h4", "industryId", "industry3",
                        "timestamp", 4L, "eventType", "view", "value", 1.0),
                Map.of(
                        "itemId", "h5", "industryId", "industry1",
                        "timestamp", 5L, "eventType", "buy", "value", 1.0),
                Map.of(
                        "itemId", "h6", "industryId", "industry3",
                        "timestamp", 6L, "eventType", "view", "value", 1.0));
    }

    private static List<FeatureDefinition> withDerived(
            List<FeatureDefinition> definitions,
            String name,
            DataType type,
            String expression) {
        List<FeatureDefinition> result = new ArrayList<>(definitions);
        result.add(FeatureDefinition.derived(name, type, expression, OutputPolicy.OUTPUT));
        return List.copyOf(result);
    }

    private static LinkedHashSet<String> linkedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static Set<EntityScope> unionEntityScopes(List<OperatorInputMetadata> inputs) {
        Set<EntityScope> result = new LinkedHashSet<>();
        for (OperatorInputMetadata input : inputs) result.addAll(input.entityScopes());
        return result;
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName() + " but nothing was thrown");
    }

    private static SequenceBlock sequence() {
        return new SequenceBlock("seq-test", 1L, List.of(
                new SequenceEvent("h1", "industry1", 1L, "click", 1.0),
                new SequenceEvent("h2", "industry2", 2L, "click", 1.0),
                new SequenceEvent("h3", "industry1", 3L, "view", 1.0),
                new SequenceEvent("h4", "industry3", 4L, "view", 1.0),
                new SequenceEvent("h5", "industry1", 5L, "buy", 1.0),
                new SequenceEvent("h6", "industry3", 6L, "view", 1.0)));
    }
}
