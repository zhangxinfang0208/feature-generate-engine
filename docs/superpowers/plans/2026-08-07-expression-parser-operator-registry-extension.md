# Expression Parser and Operator Registry Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse all documented business expressions, build their logical DAG nodes, and register all 32 business operators with the documented inference and supported scalar execution behavior.

**Architecture:** Extend the existing sealed AST with an immutable array node and teach the recursive-descent parser, DAG builder, and configuration dependency walker to traverse it. Keep `OperatorRegistry` as the public registry but organize registrations through private category helpers; implement supported scalar evaluators directly and fail explicitly for sequence evaluators whose runtime semantics are outside scope.

**Tech Stack:** Java 21, Maven, dependency-free Java `assert` self-test harness.

## Global Constraints

- Production code remains under `src/main/java/com/example/featuredag/` with four-space indentation and explicit imports.
- No new runtime dependency is introduced.
- Sequence operators that require `SequenceValue` or `SequenceView` internal behavior must throw `UnsupportedOperationException("TODO: " + name)` when evaluated.
- `discrete_key` table lookup and Chinese-comma normalization remain out of scope.
- Existing offline and online behavior must remain compatible.

---

### Task 1: Array AST and parser syntax

**Files:**
- Create: `src/main/java/com/example/featuredag/expression/AstArrayLiteral.java`
- Modify: `src/main/java/com/example/featuredag/expression/AstNode.java`
- Modify: `src/main/java/com/example/featuredag/expression/ExpressionParser.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Produces: `record AstArrayLiteral(List<AstNode> elements, SourceSpan sourceSpan) implements AstNode`.
- Produces: `ExpressionParser.parse(String)` support for arrays, chained argument groups, numeric function names, and integer-valued `AstLiteral` nodes.

- [ ] **Step 1: Add focused parser assertions to the self-test**

Add a `testExtendedExpressionParsing()` method and invoke it from `main`. Assert the following concrete behavior:

```java
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
```

- [ ] **Step 2: Run the self-test and confirm the new assertions fail to compile or parse**

Run: `./scripts/run-self-test.sh`

Expected: failure because `AstArrayLiteral` does not exist or because `[` and chained calls are not supported.

- [ ] **Step 3: Add the immutable array AST node**

Create the record and defensively copy its elements:

```java
public record AstArrayLiteral(List<AstNode> elements, SourceSpan sourceSpan) implements AstNode {
    public AstArrayLiteral {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        elements = List.copyOf(elements);
    }
}
```

Add `AstArrayLiteral` to `AstNode.permits`.

- [ ] **Step 4: Extend lexer and parser behavior**

Add `LBRACKET` and `RBRACKET`, lex `[` and `]`, and add `case LBRACKET -> parseArray()` to `parseExpression()`. Implement `parseArray()` with the same comma-loop structure used by `parseObject()` and a `SourceSpan` covering both brackets.

Before numeric lexing, detect a run of digits followed immediately by `(` and route it to `identifierToken()`. Refactor identifier consumption so `64` is emitted as one identifier without accepting arbitrary numeric feature references.

Refactor `parseIdentifierOrCall()` so each `(...)` group appends to one argument list while `current.type() == LPAREN`. Replace the numeric ternary with explicit branches:

```java
if (token.text().contains(".")) {
    value = Double.parseDouble(token.text());
} else {
    value = Integer.parseInt(token.text());
}
```

- [ ] **Step 5: Run the self-test and commit the parser slice**

Run: `./scripts/run-self-test.sh`

Expected: all assertions pass.

```bash
git add src/main/java/com/example/featuredag/expression src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Add extended expression syntax"
```

### Task 2: Array literals in configuration and logical DAG construction

**Files:**
- Modify: `src/main/java/com/example/featuredag/logical/LogicalDagBuilder.java`
- Modify: `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: `AstArrayLiteral.elements()` from Task 1.
- Produces: array literal values as `List<Object>` literal nodes with `DataType.OBJECT` and `ValueShape.OBJECT`.
- Produces: recursive feature-reference discovery inside arrays.

- [ ] **Step 1: Add failing logical DAG assertions**

Extend the parser test or add `testArrayLiteralDagConstruction()` with a raw `a` feature and derived `bucket = discrete(a, [1, 10, 100])`. Before the operator is registered, directly verify array conversion through a temporary test operator registered on a fresh registry:

```java
OperatorRegistry registry = new OperatorRegistry().register(new OperatorDefinition() {
    public String name() { return "arrayProbe"; }
    public int minArguments() { return 2; }
    public int maxArguments() { return 2; }
    public boolean deterministic() { return true; }
    public boolean parameterized() { return true; }
    public boolean supportsSequenceView() { return false; }
    public OperatorInference infer(List<LogicalNode> inputs) {
        assert inputs.get(1) instanceof LiteralNode;
        assert ((LiteralNode) inputs.get(1)).value().equals(List.of(1, 10, 100));
        return new OperatorInference(DataType.INT, Set.of(EntityScope.ITEM), ValueShape.SCALAR);
    }
    public Object evaluate(List<Object> arguments) { return 0; }
});
```

Build a derived expression `arrayProbe(a, [1, 10, 100])` and assert construction succeeds.

- [ ] **Step 2: Run the self-test and verify DAG construction fails**

Run: `./scripts/run-self-test.sh`

Expected: `DagBuildException` reports unsupported `AstArrayLiteral`.

- [ ] **Step 3: Add array traversal to builder and mapper**

Import `AstArrayLiteral`. In `buildAst()`, convert every element via `toLiteralValue()` and pass `List.copyOf(values)` to `createLiteralNode()`. In `toLiteralValue()`, recursively return a copied list for array nodes. In `collectFeatureReferences()`, recurse through every array element.

Extend `canonicalValue()` with a `List<?>` branch using delimiters so arrays canonicalize deterministically:

```java
if (value instanceof List<?> list) {
    return list.stream()
            .map(LogicalDagBuilder::canonicalValue)
            .collect(Collectors.joining(",", "[", "]"));
}
```

- [ ] **Step 4: Run the self-test and commit DAG support**

Run: `./scripts/run-self-test.sh`

Expected: all assertions pass.

```bash
git add src/main/java/com/example/featuredag/logical/LogicalDagBuilder.java src/main/java/com/example/featuredag/config/FeatureConfigMapper.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Build DAG nodes for array literals"
```

### Task 3: Complete operator registration and scalar evaluators

**Files:**
- Modify: `src/main/java/com/example/featuredag/operator/OperatorRegistry.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Produces: `OperatorRegistry.standard().require(name)` for all 32 documented business names.
- Produces: documented arity, output `DataType`, and `ValueShape` inference.
- Produces: evaluation for `add`, `sub`, `sign`, `div_num`, `round`, `log_base`, and collection-backed `calc_delta_seq` inputs.

- [ ] **Step 1: Add registration and inference tests**

Add `testBusinessOperatorRegistry()` and invoke it from `main`. Define the exact names and ensure all are present:

```java
List<String> names = List.of(
        "64", "find_list_index_typed", "list_index_typed",
        "greater_in_sequence_typed", "greater_than_index_typed", "reverse_typed",
        "slice_v3_typed", "intersection_typed", "uniq_key_index", "list_2_map",
        "thf_default_", "value2key", "k2v", "k2v_f", "v2v", "multi_v2",
        "sub", "add", "sign", "list_multi", "div_num", "round", "dis2xl",
        "default_key_if", "discrete", "log_base", "slice_by_indices",
        "find_indices", "get_seq_length", "count_distinct", "zip_concat",
        "calc_delta_seq");
OperatorRegistry registry = OperatorRegistry.standard();
for (String name : names) assert registry.require(name) != null : name;
assert ((Number) registry.evaluate("add", List.of(1, 2, 3))).doubleValue() == 6.0;
assert ((Number) registry.evaluate("sub", List.of(5, 2))).doubleValue() == 3.0;
assert registry.evaluate("sign", List.of(-5)).equals(-1);
assert ((Number) registry.evaluate("div_num", List.of(9, Map.of("divisor", 2))))
        .doubleValue() == 4.5;
assert registry.evaluate("round", List.of(4.6)).equals(5);
assert Math.abs(((Number) registry.evaluate("log_base", List.of(8, 2, 1000)))
        .doubleValue() - 3.0) < 1e-9;
assert registry.evaluate("calc_delta_seq", List.of(List.of(2, 5, 9), 10))
        .equals(List.of(8.0, 5.0, 1.0));
```

Add minimal feature definitions matching the inference rows in the attachment and build one derived target per operator group. Assert output type and shape for representative scalar, sequence, OBJECT, and pass-through operators.

- [ ] **Step 2: Run the self-test and verify missing registrations fail**

Run: `./scripts/run-self-test.sh`

Expected: `Unknown operator: 64` or another first missing operator.

- [ ] **Step 3: Organize registry category helpers**

Keep existing registrations intact, change `add` to `2..Integer.MAX_VALUE`, and introduce private helpers such as:

```java
private static void registerSequenceOperators(OperatorRegistry registry) { ... }
private static void registerConversionOperators(OperatorRegistry registry) { ... }
private static void registerScalarOperators(OperatorRegistry registry) { ... }
private static void registerOpsListOperators(OperatorRegistry registry) { ... }
```

Use small inference helpers for pass-through input selection, fixed output type/shape, and a shared evaluator factory:

```java
private static java.util.function.Function<List<Object>, Object> unsupported(String name) {
    return args -> { throw new UnsupportedOperationException("TODO: " + name); };
}
```

Register each name exactly once with the minimum and maximum arity from the attachment. Use `unionScopes(inputs)` for scope inference; for curried `slice_v3_typed`, use the sequence argument rather than the leading config object as the pass-through type source.

- [ ] **Step 4: Implement supported scalar evaluators**

Implement numeric iteration for `add`, binary subtraction, integer sign, map-configured division, integer rounding, bounded logarithm, and base-minus-each-element evaluation for collection-backed `calc_delta_seq` inputs. Validate numeric and map inputs through existing `asNumber()` and `asMap()` helpers. Reject a zero divisor and invalid logarithm base with `IllegalArgumentException` messages naming the relevant parameter. If `calc_delta_seq` receives a `SequenceValue`, throw the documented explicit unsupported exception because converting runtime sequence events to numeric values is outside scope.

- [ ] **Step 5: Run focused self-tests and commit registry support**

Run: `./scripts/run-self-test.sh`

Expected: all registration, inference, evaluator, and existing regression assertions pass.

```bash
git add src/main/java/com/example/featuredag/operator/OperatorRegistry.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Register business expression operators"
```

### Task 4: Real business expression and end-to-end regression coverage

**Files:**
- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: complete parser, DAG array conversion, and standard registry from Tasks 1–3.
- Produces: regression evidence for the documented hp1h_imp_hpd expression and public documentation of supported expression forms.

- [ ] **Step 1: Add the complete nested-expression parser test**

Store the attachment's complete `default_key_if(dis2xl(round(...)))` expression as a Java text block. Assert `new ExpressionParser().parse(expression)` returns an `AstCall` named `default_key_if`. Recursively collect `AstCall.functionName()` values and assert all 16 expected names are present.

- [ ] **Step 2: Add minimal end-to-end DAG coverage**

Add a derived `discrete_price` feature with expression `discrete(item_price, [0, 100, 1000])`, declared `DataType.INT`, `EntityScope.ITEM`, and scalar shape. Build it with `LogicalDagBuilder` and assert the feature output is INT/SCALAR. Keep execution out of this assertion because `discrete` execution is explicitly outside scope.

Run: `./scripts/run-self-test.sh`

Expected: all new and existing assertions pass.

- [ ] **Step 3: Update README expression capability text**

Change the expression-parser capability bullet to list function calls, feature references, numeric/string/boolean/null values, object parameters, array literals, curried calls, and numeric operator names. Add one compact example containing `discrete(a, [1, 10, 100])` and `slice_v3_typed({"start": 4})(seq)`; state that registration and inference do not imply every sequence operator has runtime evaluation support.

- [ ] **Step 4: Run complete verification**

Run: `./scripts/run-self-test.sh`

Expected: process exits 0 and prints the self-test success message.

Run: `mvn package`

Expected: Maven exits 0 and creates the normal and `-all.jar` artifacts under `target/`.

Run: `git diff --check`

Expected: no output.

- [ ] **Step 5: Commit final tests and documentation**

```bash
git add src/test/java/com/example/featuredag/DagEngineSelfTest.java README.md
git commit -m "Cover business expression extensions"
```
