package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.expression.AstCall;
import com.example.featuredag.expression.AstLiteral;
import com.example.featuredag.expression.ExpressionParseException;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NamedOperatorArgumentsTest {
    @Test
    public void parserPreservesNamedArgumentWithoutTreatingItsNameAsAFeature() {
        AstCall call = (AstCall) new ExpressionParser().parse("div(a, divisor=3600)");

        assertEquals("div", call.functionName());
        assertEquals(2, call.arguments().size());
        assertNull(call.argumentName(0));
        assertEquals("divisor", call.argumentName(1));
        assertEquals(Integer.valueOf(3600), ((AstLiteral) call.arguments().get(1)).value());
        assertTrue(call.hasNamedArguments());
        assertFalse(call.argumentNames().containsValue("a"));
    }

    @Test
    public void namedAndPositionalFormsNormalizeToTheSameLogicalInputs() {
        LogicalDag dag = build(
                "div_positional", DataType.DOUBLE, "div(a, 3600)",
                "div_named", DataType.DOUBLE, "div(a, divisor=3600)",
                "div_reordered", DataType.DOUBLE, "div(divisor=3600, value=a)",
                "sub_named", DataType.INT, "sub(a, margin=86400)",
                "add_positional", DataType.INT, "add(a, 100)",
                "add_named", DataType.INT, "add(a, addend=100)",
                "mul_positional", DataType.INT, "mul(a, 2)",
                "mul_named", DataType.INT, "mul(a, multiplier=2)");

        String positionalProducer = dag.featureOutput("div_positional").producerNodeId();
        assertEquals(positionalProducer, dag.featureOutput("div_named").producerNodeId());
        assertEquals(positionalProducer, dag.featureOutput("div_reordered").producerNodeId());

        OperatorNode sub = (OperatorNode) dag.node(
                dag.featureOutput("sub_named").producerNodeId());
        assertEquals("sub", sub.operatorName());
        LiteralNode margin = (LiteralNode) dag.node(sub.inputs().get(1).nodeId());
        assertEquals(Integer.valueOf(86400), margin.value());
        assertEquals(
                dag.featureOutput("add_positional").producerNodeId(),
                dag.featureOutput("add_named").producerNodeId());
        assertEquals(
                dag.featureOutput("mul_positional").producerNodeId(),
                dag.featureOutput("mul_named").producerNodeId());
    }

    @Test
    public void namedConstantArgumentsExecuteThroughTheExistingRuntimeProtocol() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = build(
                "hours", DataType.DOUBLE, "div(a, divisor=3600)",
                "previous_day", DataType.INT, "sub(a, margin=86400)",
                "with_offset", DataType.INT, "add(a, addend=100)",
                "scaled", DataType.INT, "mul(a, multiplier=2)");
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "named-constant-arguments");

        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "named-constant-arguments", Map.<String, Object>of("a", 90000)));

        assertEquals(Double.valueOf(25.0), result.feature("hours").raw());
        assertEquals(Long.valueOf(3600L), result.feature("previous_day").raw());
        assertEquals(Long.valueOf(90100L), result.feature("with_offset").raw());
        assertEquals(Long.valueOf(180000L), result.feature("scaled").raw());
    }

    @Test
    public void invalidNamedArgumentsFailWithActionableMessages() {
        DagBuildException unknown = assertBuildFailure("div(a, denominator=3600)");
        assertTrue(unknown.getMessage(), unknown.getMessage().contains("Unknown named argument 'denominator'"));

        DagBuildException duplicate = assertBuildFailure("div(a, value=3600)");
        assertTrue(duplicate.getMessage(), duplicate.getMessage().contains("specified more than once"));

        DagBuildException missing = assertBuildFailure("div(divisor=3600)");
        assertTrue(missing.getMessage(), missing.getMessage().contains("Missing required argument 'value'"));

        ExpressionParseException positionalAfterNamed = assertThrows(
                ExpressionParseException.class,
                () -> new ExpressionParser().parse("div(divisor=3600, a)"));
        assertTrue(
                positionalAfterNamed.getMessage(),
                positionalAfterNamed.getMessage().contains(
                        "Positional argument must not follow a named argument"));
    }

    private static LogicalDag build(Object... featureTriples) {
        List<FeatureDefinition> definitions = new java.util.ArrayList<>();
        definitions.add(FeatureDefinition.raw("a", DataType.INT, EntityScope.USER, 1));
        Set<String> targets = new java.util.LinkedHashSet<>();
        for (int index = 0; index < featureTriples.length; index += 3) {
            String name = (String) featureTriples[index];
            DataType type = (DataType) featureTriples[index + 1];
            String expression = (String) featureTriples[index + 2];
            definitions.add(FeatureDefinition.derived(
                    name, type, expression, OutputPolicy.OUTPUT));
            targets.add(name);
        }
        return new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(definitions, targets);
    }

    private static DagBuildException assertBuildFailure(String expression) {
        return assertThrows(
                DagBuildException.class,
                () -> build("invalid", DataType.DOUBLE, expression));
    }
}
