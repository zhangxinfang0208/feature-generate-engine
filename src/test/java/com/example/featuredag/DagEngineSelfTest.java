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
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.KeyedSequenceFilterSemantic;
import com.example.featuredag.operator.SequenceCardinalitySemantic;
import com.example.featuredag.operator.SequenceKeyDomains;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalExecutorIds;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanPrinter;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.CandidateBatchValue;
import com.example.featuredag.runtime.CandidateVectorValue;
import com.example.featuredag.runtime.BitmapSelection;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import com.example.featuredag.runtime.IndexSelection;
import com.example.featuredag.runtime.ListSequenceValue;
import com.example.featuredag.runtime.RangeSelection;
import com.example.featuredag.runtime.ScalarValue;
import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceEvent;
import com.example.featuredag.runtime.SequenceValue;
import com.example.featuredag.runtime.SequenceView;
import com.example.featuredag.runtime.ValueHandle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Dependency-free self test. Run with java -ea. */
public final class DagEngineSelfTest {
    public static void main(String[] args) throws Exception {
        FeatureValueCodecSelfTest.run();
        testExtendedExpressionParsing();
        testCompleteBusinessExpressionParsing();
        testArrayLiteralDagConstruction();
        testNullArrayLiteralDagConstruction();
        testObjectListsRemainScalarAtRuntime();
        testAlignedPlainListSequenceRuntime();
        testIndependentRawListSequenceLengthsAreAllowed();
        testWindowSequenceOperatorEvaluation();
        testThreeDayAppCountFromAlignedLists();
        testLiteralCanonicalizationSeparatesTypesAndBoundaries();
        testDiscreteFeatureDagConstruction();
        testArrayLiteralDisabledFeatureReferenceValidation();
        testBusinessOperatorRegistry();
        testAllBusinessOperatorExpressionsBuildAndInfer();
        testBusinessJsonParsing();
        testUnifiedFeatureJsonParsing();
        testLegacyDerivedFeaturesRejected();
        testIntermediateFeatureMapping();
        testOfflinePublicApi();
        testOfflineBatchPublicApi();
        testBatchDemos();
        testConfigPathInit();
        testOfflineSequenceMaterialization();
        testOnlinePublicApi();
        testOnlineBatchPublicApi();
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

        AstCall curried = (AstCall) parser.parse(
                "slice_v3_typed({\"start\": 4})(time_impr_seq_th_f_1)");
        assert curried.functionName().equals("slice_v3_typed");
        assert curried.arguments().size() == 2;
        assert curried.arguments().get(0) instanceof AstObjectLiteral;
        assert curried.arguments().get(1) instanceof AstFeatureRef;

        AstCall cast = (AstCall) parser.parse("64(CONTEXT.request_time)");
        assert cast.functionName().equals("64");
        assert ((AstFeatureRef) cast.arguments().getFirst()).featureName()
                .equals("CONTEXT.request_time");
        assert ((AstLiteral) parser.parse("42")).value() instanceof Integer;
        assert ((AstLiteral) parser.parse("3.14")).value() instanceof Double;
        expectThrows(ExpressionParseException.class, () -> parser.parse("[1, 2"));
        expectThrows(ExpressionParseException.class, () -> parser.parse("f(a,)"));
    }

    private static void testCompleteBusinessExpressionParsing() {
        // The supplied fixture had misplaced closing parentheses. This form preserves every
        // operator and argument while closing each call at its documented arity boundary.
        String expression = """
                default_key_if(
                  dis2xl(
                    round(
                      div_num(
                        add(
                          list_multi(
                            k2v_f(
                              list_index_typed(
                                staytimes,
                                reverse_typed(
                                  slice_v3_typed({"start": 4})(
                                    reverse_typed(
                                      uniq_key_index(
                                        list_index_typed(
                                          goods_ids,
                                          intersection_typed(
                                            greater_in_sequence_typed(
                                              action_times,
                                              request_time,
                                              {"margin": 3600000}
                                            ),
                                            find_list_index_typed(action_types, 1)
                                          )
                                        )
                                      )
                                    )
                                  )
                                )
                              )
                            ),
                            multi_v2(
                              sign(
                                add(
                                  list_index_typed(
                                    action_times,
                                    reverse_typed(
                                      slice_v3_typed({"start": 4})(
                                        reverse_typed(
                                          uniq_key_index(
                                            list_index_typed(
                                              goods_ids,
                                              intersection_typed(
                                                greater_in_sequence_typed(
                                                  action_times,
                                                  request_time,
                                                  {"margin": 3600000}
                                                ),
                                                find_list_index_typed(action_types, 1)
                                              )
                                            )
                                          )
                                        )
                                      )
                                    )
                                  ),
                                  1
                                )
                              )
                            ),
                            {"multi_factor": -1}
                          ),
                          list_multi(
                            k2v_f(
                              list_index_typed(
                                staytimes,
                                reverse_typed(
                                  slice_v3_typed({"start": 0})(
                                    reverse_typed(
                                      uniq_key_index(
                                        list_index_typed(
                                          goods_ids,
                                          intersection_typed(
                                            greater_in_sequence_typed(
                                              action_times,
                                              request_time,
                                              {"margin": 3600000}
                                            ),
                                            find_list_index_typed(action_types, 1)
                                          )
                                        )
                                      )
                                    )
                                  )
                                )
                              )
                            ),
                            multi_v2(
                              sign(
                                add(
                                  list_index_typed(
                                    action_times,
                                    reverse_typed(
                                      slice_v3_typed({"start": 0})(
                                        reverse_typed(
                                          uniq_key_index(
                                            list_index_typed(
                                              goods_ids,
                                              intersection_typed(
                                                greater_in_sequence_typed(
                                                  action_times,
                                                  request_time,
                                                  {"margin": 3600000}
                                                ),
                                                find_list_index_typed(action_types, 1)
                                              )
                                            )
                                          )
                                        )
                                      )
                                    )
                                  ),
                                  1
                                )
                              )
                            ),
                            {"multi_factor": 1}
                          )
                        ),
                        {"divisor": 2}
                      )
                    ),
                    {"divisor": 1000, "discrete_key": "ntg_impr_seq_f_1h_sec_disc_rt"}
                  ),
                  {"default_key": -1}
                )
                """;

        AstCall parsed = (AstCall) new ExpressionParser().parse(expression);
        assert parsed.functionName().equals("default_key_if") : parsed.functionName();

        Set<String> functionNames = new LinkedHashSet<>();
        collectCallFunctionNames(parsed, functionNames);
        Set<String> expectedNames = Set.of(
                "default_key_if", "dis2xl", "round", "div_num", "add", "list_multi",
                "k2v_f", "list_index_typed", "reverse_typed", "slice_v3_typed",
                "uniq_key_index", "intersection_typed", "greater_in_sequence_typed",
                "find_list_index_typed", "multi_v2", "sign");
        assert functionNames.equals(expectedNames) : functionNames;
        assert countCalls(parsed, "greater_in_sequence_typed") == 4;
        assert countCalls(parsed, "find_list_index_typed") == 4;

        OperatorRegistry registry = OperatorRegistry.standard();
        for (String functionName : expectedNames) {
            assert registry.require(functionName) != null : functionName;
        }
        assertCallArities(parsed, registry);
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw(
                                "staytimes", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "goods_ids", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "action_times", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw(
                                "action_types", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                        FeatureDefinition.raw("request_time", DataType.INT, EntityScope.SCENE, 0),
                        FeatureDefinition.derived(
                                "hp1h_imp_hpd_final", DataType.INT,
                                expression, OutputPolicy.OUTPUT)),
                Set.of("hp1h_imp_hpd_final"));
        assertOutput(dag, "hp1h_imp_hpd_final", DataType.INT, ValueShape.SCALAR);
        assert countOperatorNodes(dag, "greater_in_sequence_typed") == 1
                : "greater_in_sequence_typed repeated subexpression was not deduplicated";
        assert countOperatorNodes(dag, "find_list_index_typed") == 1
                : "find_list_index_typed repeated subexpression was not deduplicated";
    }

    private static long countOperatorNodes(LogicalDag dag, String operatorName) {
        return dag.nodes().values().stream()
                .filter(OperatorNode.class::isInstance)
                .map(OperatorNode.class::cast)
                .filter(node -> node.operatorName().equals(operatorName))
                .count();
    }

    private static int countCalls(AstNode node, String functionName) {
        int count = node instanceof AstCall call && call.functionName().equals(functionName) ? 1 : 0;
        if (node instanceof AstCall call) {
            for (AstNode argument : call.arguments()) {
                count += countCalls(argument, functionName);
            }
        } else if (node instanceof AstObjectLiteral objectLiteral) {
            for (AstNode value : objectLiteral.fields().values()) {
                count += countCalls(value, functionName);
            }
        } else if (node instanceof AstArrayLiteral arrayLiteral) {
            for (AstNode element : arrayLiteral.elements()) {
                count += countCalls(element, functionName);
            }
        }
        return count;
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
            public OperatorInference infer(List<LogicalNode> inputs) {
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

    private static void testBusinessOperatorRegistry() {
        List<String> names = List.of(
                "64", "find_list_index_typed", "list_index_typed",
                "greater_in_sequence_typed", "greater_than_index_typed", "reverse_typed",
                "slice_v3_typed", "intersection_typed", "uniq_key_index", "list_2_map",
                "thf_default_", "value2key", "k2v", "k2v_f", "v2v", "multi_v2",
                "sub", "add", "sign", "list_multi", "div_num", "round", "div", "least",
                "dis2xl", "default_key_if", "discrete", "log_base", "slice_by_indices",
                "find_indices", "get_seq_length", "count_distinct", "zip_concat",
                "calc_delta_seq");
        Map<String, List<Integer>> arities = Map.ofEntries(
                Map.entry("64", List.of(1, 1)),
                Map.entry("find_list_index_typed", List.of(2, 2)),
                Map.entry("list_index_typed", List.of(2, 2)),
                Map.entry("greater_in_sequence_typed", List.of(3, 3)),
                Map.entry("greater_than_index_typed", List.of(3, 3)),
                Map.entry("reverse_typed", List.of(1, 1)),
                Map.entry("slice_v3_typed", List.of(2, 2)),
                Map.entry("intersection_typed", List.of(2, 2)),
                Map.entry("uniq_key_index", List.of(1, 1)),
                Map.entry("list_2_map", List.of(2, 2)),
                Map.entry("thf_default_", List.of(2, 2)),
                Map.entry("value2key", List.of(1, 1)),
                Map.entry("k2v", List.of(1, 1)),
                Map.entry("k2v_f", List.of(1, 1)),
                Map.entry("v2v", List.of(1, 1)),
                Map.entry("multi_v2", List.of(1, 1)),
                Map.entry("sub", List.of(2, 2)),
                Map.entry("add", List.of(2, Integer.MAX_VALUE)),
                Map.entry("sign", List.of(1, 1)),
                Map.entry("list_multi", List.of(3, 3)),
                Map.entry("div_num", List.of(2, 2)),
                Map.entry("round", List.of(1, 1)),
                Map.entry("div", List.of(2, 2)),
                Map.entry("least", List.of(2, Integer.MAX_VALUE)),
                Map.entry("dis2xl", List.of(2, 2)),
                Map.entry("default_key_if", List.of(2, 2)),
                Map.entry("discrete", List.of(2, 2)),
                Map.entry("log_base", List.of(3, 3)),
                Map.entry("slice_by_indices", List.of(2, 2)),
                Map.entry("find_indices", List.of(2, 2)),
                Map.entry("get_seq_length", List.of(1, 1)),
                Map.entry("count_distinct", List.of(1, 1)),
                Map.entry("zip_concat", List.of(2, Integer.MAX_VALUE)),
                Map.entry("calc_delta_seq", List.of(2, 2)));

        OperatorRegistry registry = OperatorRegistry.standard();
        for (String name : names) {
            OperatorDefinition definition = registry.require(name);
            assert definition != null : name;
            assert definition.minArguments() == arities.get(name).get(0)
                    : name + " min arity=" + definition.minArguments();
            assert definition.maxArguments() == arities.get(name).get(1)
                    : name + " max arity=" + definition.maxArguments();
        }

        assert ((Number) registry.evaluate("add", List.of(1, 2, 3))).doubleValue() == 6.0;
        assert ((Number) registry.evaluate("sub", List.of(5, 2))).doubleValue() == 3.0;
        assert registry.evaluate("sign", List.of(-5)).equals(-1);
        assert ((Number) registry.evaluate("div_num", List.of(9, Map.of("divisor", 2))))
                .doubleValue() == 4.5;
        assert registry.evaluate("round", List.of(4.6)).equals(5);
        assert registry.evaluate("div", List.of(9, 2)).equals(4.5);
        assert registry.evaluate("least", List.of(3, 5, 1)).equals(1);
        assert registry.evaluate("least", List.of(2.5, 3)).equals(2.5);
        assert Math.abs(((Number) registry.evaluate("log_base", List.of(8, 2, 1000)))
                .doubleValue() - 3.0) < 1e-9;
        assert Math.abs(((Number) registry.evaluate("log_base", List.of(2000, 10, 1000)))
                .doubleValue() - 3.0) < 1e-9;
        assert registry.evaluate("discrete", List.of(16, List.of(1, 10, 100))).equals(2);
        assert registry.evaluate("discrete", List.of(10, List.of(1, 10, 100))).equals(2);
        assert registry.evaluate(
                "slice_by_indices",
                List.of(List.of("a1", "a2", "a3", "a4"), List.of(1, 3)))
                .equals(List.of("a2", "a4"));
        assert registry.evaluate(
                "find_indices", List.of(List.of("a1", "a2", "a3", "a3"), "a3"))
                .equals(List.of(2, 3));
        assert registry.evaluate("get_seq_length", List.of(List.of("a1", "a2", "a3", "a4")))
                .equals(4);
        assert registry.evaluate("get_seq_length", List.of(sequence())).equals(6);
        assert registry.evaluate("count_distinct", List.of(List.of("a1", "a2", "a1", "a3")))
                .equals(3);
        assert registry.evaluate(
                "zip_concat",
                List.of(
                        List.of("a1", "a2", "a3", "a4"),
                        List.of("b1", "b2", "b3", "b4")))
                .equals(List.of("a1#b1", "a2#b2", "a3#b3", "a4#b4"));
        assert registry.evaluate(
                "zip_concat",
                List.of(List.of("a1", "a2"), List.of("b1", "b2"), Map.of("delimiter", "|")))
                .equals(List.of("a1|b1", "a2|b2"));
        assert registry.evaluate("calc_delta_seq", List.of(List.of(2, 5, 9), 10))
                .equals(List.of(-8.0, -5.0, -1.0));

        IllegalArgumentException zeroDivisor = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("div_num", List.of(9, Map.of("divisor", 0))));
        assert zeroDivisor.getMessage().contains("divisor") : zeroDivisor.getMessage();
        IllegalArgumentException zeroDivisorPlain = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("div", List.of(9, 0)));
        assert zeroDivisorPlain.getMessage().contains("divisor")
                : zeroDivisorPlain.getMessage();
        IllegalArgumentException invalidBase = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("log_base", List.of(8, 1, 1000)));
        assert invalidBase.getMessage().contains("base") : invalidBase.getMessage();
        IllegalArgumentException sequenceDelta = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("calc_delta_seq", List.of(sequence(), 10)));
        assert sequenceDelta.getMessage().contains("expects List")
                : sequenceDelta.getMessage();
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

        List<FeatureDefinition> inferenceDefinitions = new ArrayList<>(List.of(
                FeatureDefinition.raw("a", DataType.DOUBLE, EntityScope.ITEM, 0.0),
                FeatureDefinition.raw("b", DataType.DOUBLE, EntityScope.USER, 0.0),
                FeatureDefinition.raw("c", DataType.DOUBLE, EntityScope.SCENE, 0.0),
                FeatureDefinition.raw("target", DataType.INT, EntityScope.ITEM, 0),
                FeatureDefinition.raw("seq", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("seq2", DataType.EVENT_SEQUENCE, EntityScope.ITEM, null),
                FeatureDefinition.raw("mapping", DataType.OBJECT, EntityScope.SCENE, Map.of())));
        inferenceDefinitions.addAll(List.of(
                FeatureDefinition.derived(
                        "cast64", DataType.DOUBLE, "64(a)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "matching_indices", DataType.INT,
                        "find_list_index_typed(seq, target)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "sliced", DataType.EVENT_SEQUENCE,
                        "slice_v3_typed({\"start\": 2})(seq)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "mapped", DataType.OBJECT, "list_2_map(seq, seq2)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "defaulted", DataType.EVENT_SEQUENCE,
                        "thf_default_(mapping, seq)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "float_sequence", DataType.DOUBLE, "k2v_f(seq)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "sum", DataType.DOUBLE, "add(a, b, c)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "product_sequence", DataType.DOUBLE,
                        "list_multi(seq, seq2, {\"multi_factor\": -1})", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "bucket", DataType.INT, "discrete(a, [1, 10, 100])", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "indexed_slice", DataType.EVENT_SEQUENCE,
                        "slice_by_indices(seq, [1, 3])", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "zipped", DataType.STRING, "zip_concat(seq, seq2)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "delta", DataType.DOUBLE, "calc_delta_seq(seq, a)", OutputPolicy.OUTPUT)));

        LogicalDag inferenceDag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
                inferenceDefinitions,
                linkedSet(
                        "cast64", "matching_indices", "sliced", "mapped", "defaulted",
                        "float_sequence", "sum", "product_sequence", "bucket",
                        "indexed_slice", "zipped", "delta"));
        assertOutput(inferenceDag, "cast64", DataType.DOUBLE, ValueShape.SCALAR);
        assertOutput(inferenceDag, "matching_indices", DataType.INT, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "sliced", DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "mapped", DataType.OBJECT, ValueShape.OBJECT);
        assertOutput(inferenceDag, "defaulted", DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "float_sequence", DataType.DOUBLE, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "sum", DataType.DOUBLE, ValueShape.SCALAR);
        assert inferenceDag.featureOutput("sum").entityScopes()
                .equals(Set.of(EntityScope.ITEM, EntityScope.USER, EntityScope.SCENE))
                : inferenceDag.featureOutput("sum").entityScopes();
        assertOutput(inferenceDag, "product_sequence", DataType.DOUBLE, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "bucket", DataType.INT, ValueShape.SCALAR);
        assertOutput(inferenceDag, "indexed_slice", DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "zipped", DataType.STRING, ValueShape.SEQUENCE);
        assertOutput(inferenceDag, "delta", DataType.DOUBLE, ValueShape.SEQUENCE);
    }

    private static void testAllBusinessOperatorExpressionsBuildAndInfer() {
        List<BusinessOperatorCase> cases = List.of(
                new BusinessOperatorCase("64", "64(a)", DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "find_list_index_typed", "find_list_index_typed(seq, target)",
                        DataType.INT, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "list_index_typed", "list_index_typed(seq, indexes)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "greater_in_sequence_typed",
                        "greater_in_sequence_typed(seq, target, {\"margin\": 4000})",
                        DataType.INT, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "greater_than_index_typed",
                        "greater_than_index_typed(seq, target, {\"margin\": 3000})",
                        DataType.INT, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "reverse_typed", "reverse_typed(seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "slice_v3_typed", "slice_v3_typed({\"start\": 2})(seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "intersection_typed", "intersection_typed(seq, seq2)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "uniq_key_index", "uniq_key_index(seq)",
                        DataType.INT, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "list_2_map", "list_2_map(seq, seq2)",
                        DataType.OBJECT, ValueShape.OBJECT),
                new BusinessOperatorCase(
                        "thf_default_", "thf_default_(mapping, seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "value2key", "value2key(seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "k2v", "k2v(seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "k2v_f", "k2v_f(seq)",
                        DataType.DOUBLE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "v2v", "v2v(seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "multi_v2", "multi_v2(seq)",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "sub", "sub(a, b)", DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "add", "add(a, b, c)", DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "sign", "sign(a)", DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "list_multi", "list_multi(seq, seq2, {\"multi_factor\": -1})",
                        DataType.DOUBLE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "div_num", "div_num(a, {\"divisor\": 2})",
                        DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "round", "round(a)", DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "dis2xl",
                        "dis2xl(a, {\"divisor\": 1000, \"discrete_key\": \"table1\"})",
                        DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "default_key_if", "default_key_if(a, {\"default_key\": -1})",
                        DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "discrete", "discrete(a, [1, 10, 100])",
                        DataType.INT, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "log_base", "log_base(a, 2, 1000)",
                        DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "slice_by_indices", "slice_by_indices(seq, [1, 3])",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "find_indices", "find_indices(seq, a3)",
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
                        DataType.DOUBLE, ValueShape.SEQUENCE),
                new BusinessOperatorCase(
                        "div", "div(a, b)",
                        DataType.DOUBLE, ValueShape.SCALAR),
                new BusinessOperatorCase(
                        "least", "least(target, a3)",
                        DataType.INT, ValueShape.SCALAR));
        assert cases.stream()
                .map(BusinessOperatorCase::operatorName)
                .collect(java.util.stream.Collectors.toSet())
                .size() == 34 : "Business operator cases must contain 34 distinct names";

        OperatorRegistry registry = OperatorRegistry.standard();
        ExpressionParser parser = new ExpressionParser();
        List<FeatureDefinition> definitions = new ArrayList<>(List.of(
                FeatureDefinition.raw("a", DataType.DOUBLE, EntityScope.ITEM, 0.0),
                FeatureDefinition.raw("b", DataType.DOUBLE, EntityScope.USER, 0.0),
                FeatureDefinition.raw("c", DataType.DOUBLE, EntityScope.SCENE, 0.0),
                FeatureDefinition.raw("target", DataType.INT, EntityScope.ITEM, 0),
                FeatureDefinition.raw("a3", DataType.INT, EntityScope.SCENE, 0),
                FeatureDefinition.raw("seq", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("seq2", DataType.EVENT_SEQUENCE, EntityScope.ITEM, null),
                FeatureDefinition.raw("indexes", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("mapping", DataType.OBJECT, EntityScope.SCENE, Map.of())));
        Set<String> targets = new LinkedHashSet<>();
        Map<String, BusinessOperatorCase> casesByFeature = new LinkedHashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            BusinessOperatorCase operatorCase = cases.get(index);
            AstCall parsed = (AstCall) parser.parse(operatorCase.expression());
            assert parsed.functionName().equals(operatorCase.operatorName())
                    : operatorCase.expression();
            assertCallArities(parsed, registry);

            String featureName = "business_operator_" + (index + 1);
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
        assert casesByFeature.size() == 34 : casesByFeature.keySet();
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

    private static void collectCallFunctionNames(AstNode node, Set<String> functionNames) {
        if (node instanceof AstCall call) {
            functionNames.add(call.functionName());
            for (AstNode argument : call.arguments()) {
                collectCallFunctionNames(argument, functionNames);
            }
        } else if (node instanceof AstObjectLiteral objectLiteral) {
            for (AstNode fieldValue : objectLiteral.fields().values()) {
                collectCallFunctionNames(fieldValue, functionNames);
            }
        } else if (node instanceof AstArrayLiteral arrayLiteral) {
            for (AstNode element : arrayLiteral.elements()) {
                collectCallFunctionNames(element, functionNames);
            }
        }
    }

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
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("online-concurrent"));
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

    private static void testConfigurationAndRequestValidation() {
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
        assert onlinePlan.nodes().stream()
                .anyMatch(node -> node.executorType() == ExecutorType.SPECIALIZED)
                : "Online count plan should fuse extraction and count";

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
                .register(new OperatorDefinition() {
                    @Override public String name() { return "select_by_registered_key"; }
                    @Override public int minArguments() { return 2; }
                    @Override public int maxArguments() { return 2; }
                    @Override public boolean deterministic() { return true; }
                    @Override public boolean parameterized() { return false; }
                    @Override public boolean supportsSequenceView() { return true; }
                    @Override public long estimatedCost() { return 1_000L; }
                    @Override public List<com.example.featuredag.operator.OperatorSemantic> semantics() {
                        return List.of(new KeyedSequenceFilterSemantic(
                                0, 1, SequenceKeyDomains.INDUSTRY));
                    }
                    @Override public OperatorInference infer(List<LogicalNode> inputs) {
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
                })
                .register(new OperatorDefinition() {
                    @Override public String name() { return "registered_cardinality"; }
                    @Override public int minArguments() { return 1; }
                    @Override public int maxArguments() { return 1; }
                    @Override public boolean deterministic() { return true; }
                    @Override public boolean parameterized() { return false; }
                    @Override public boolean supportsSequenceView() { return true; }
                    @Override public long estimatedCost() { return 1_000L; }
                    @Override public List<com.example.featuredag.operator.OperatorSemantic> semantics() {
                        return List.of(new SequenceCardinalitySemantic(0));
                    }
                    @Override public OperatorInference infer(List<LogicalNode> inputs) {
                        return new OperatorInference(
                                DataType.INT,
                                unionEntityScopes(inputs),
                                ValueShape.SCALAR);
                    }
                    @Override public Object evaluate(List<Object> arguments) {
                        return ((SequenceValue) arguments.getFirst()).size();
                    }
                });

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
        assert result.nodeStates().get(cachedNode.physicalNodeId()).dedupInputCount() == 4;
        assert result.nodeStates().get(cachedNode.physicalNodeId()).uniqueInputCount() == 3;

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
        assert grouped.nodeStates().get(cachedNode.physicalNodeId()).dedupInputCount() == 4;
        assert grouped.nodeStates().get(cachedNode.physicalNodeId()).uniqueInputCount() == 2;
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
            @Override public OperatorInference infer(List<LogicalNode> inputs) {
                LogicalNode input = inputs.getFirst();
                return new OperatorInference(
                        input.outputType(), input.entityScopes(), ValueShape.SCALAR);
            }
            @Override public Object evaluate(List<Object> arguments) {
                calls.incrementAndGet();
                return arguments.getFirst();
            }
        };
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

    private static Set<EntityScope> unionEntityScopes(List<LogicalNode> inputs) {
        Set<EntityScope> result = new LinkedHashSet<>();
        for (LogicalNode input : inputs) result.addAll(input.entityScopes());
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
