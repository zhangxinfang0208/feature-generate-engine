# Native Batch Operators and Performance Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Implement Native Batch kernels for the eight initial operators and add a deterministic deep-expression online Batch demo that verifies CSE, routing, correctness, and performance.

**Architecture:** Each built-in operator implements BatchOperatorKernel; the existing registry, physical planner, and runtime automatically select and diagnose NATIVE execution. Expensive sequence operators keep only per-call reuse maps keyed by group and immutable input identity, while cheap scalar work executes directly over aligned BatchColumn values. A public-API demo compares repeated OnlineGenerateRequest calls with one OnlineBatchGenerateRequest without enabling physical fusion.

**Tech Stack:** Java 21 project build; Java 8-compatible source for built-ins, shared built-in support, and demo; dependency-free assert self-test; Maven; Bash and PowerShell launchers.

## Global Constraints

- The standard registry remains exactly the eight initial operators.
- Single Kernel remains the semantic baseline; Native Batch preserves row count, order, values, and failing row index.
- Kernel instances remain stateless; reuse maps live inside one evaluateBatch call.
- Built-ins, directly shared operator.builtin support, and demo use only JDK 8 language features and standard-library APIs.
- Do not add operator-name branches to planning, physical, or runtime.
- Do not add physical fusion semantics; the demo observes zero fused physical nodes.
- Preserve the user's uncommitted FeatureInputDecoder.java edit and untracked root .docx.

---

## File Structure

- Modify OperatorSupport.java for Batch failure wrapping and identity-key mechanics.
- Modify all eight operator files for operator-owned Native algorithms.
- Modify DagEngineSelfTest.java for routing, equivalence, failure-row, scan-reuse, and smoke tests.
- Create NativeBatchPerformanceDemo.java for deterministic data, assertions, and timing.
- Create native-batch-performance.json for deep expressions and aliases.
- Create dedicated Bash and PowerShell launchers.

### Task 1: Eight Native Batch Kernels

**Files:**
- Modify: src/test/java/com/example/featuredag/DagEngineSelfTest.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/OperatorSupport.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/DiscreteOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/LogBaseOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/SliceByIndicesOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/FindIndicesOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/GetSequenceLengthOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/CountDistinctOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/ZipConcatOperator.java
- Modify: src/main/java/com/example/featuredag/operator/builtin/CalculateDeltaSequenceOperator.java

**Interfaces:**
- Consumes: BatchOperatorKernel.evaluateBatch(BatchOperatorCall), aligned BatchColumn arguments, and BatchLayout.groupIndexAt(int).
- Produces: eight definitions whose registry batchKernelKind is NATIVE and whose results use ListBatchColumn.

- [ ] **Step 1: Write failing routing and equivalence tests**

Change the registry assertion to:

~~~java
assert registry.batchKernelKind(name) == BatchKernelKind.NATIVE
        : name + " should use its Native Batch kernel";
~~~

Call these methods from main after the existing Single evaluation test:

~~~java
testInitialOperatorNativeBatchEquivalence();
testNativeBatchFailureRow();
testFindIndicesNativeBatchReusesSequenceScan();
~~~

Add a NativeBatchCase record containing operatorName and row argument lists. Cover these rows:

~~~java
List<NativeBatchCase> cases = List.of(
        new NativeBatchCase("discrete", List.of(
                List.of(16.0, List.of(0, 10, 100)),
                List.of(150.0, List.of(0, 10, 100)))),
        new NativeBatchCase("log_base", List.of(
                List.of(8.0, 2.0, 1024.0),
                List.of(32.0, 2.0, 1024.0))),
        new NativeBatchCase("slice_by_indices", List.of(
                List.of(List.of("a", "b", "c"), List.of(0, 2)),
                List.of(List.of("x", "y", "z"), List.of(1, 2)))),
        new NativeBatchCase("find_indices", List.of(
                List.of(List.of("a", "b", "a"), "a"),
                List.of(List.of("x", "y", "x"), "y"))),
        new NativeBatchCase("get_seq_length", List.of(
                List.of((Object) List.of("a", "b")),
                List.of((Object) List.of("x", "y", "z")))),
        new NativeBatchCase("count_distinct", List.of(
                List.of((Object) List.of("a", "b", "a")),
                List.of((Object) List.of("x", "x", "x")))),
        new NativeBatchCase("zip_concat", List.of(
                List.of(List.of("a", "b"), List.of("1", "2")),
                List.of(List.of("x", "y"), List.of("3", "4")))),
        new NativeBatchCase("calc_delta_seq", List.of(
                List.of(List.of(2.0, 5.0), 10.0),
                List.of(List.of(10.0, 8.0), 5.0))));
~~~

Implement batchCall(rows, domain) by transposing rows into ListBatchColumn instances and using a FixedBatchLayout. For every case call registry.evaluateBatch(name, call, NATIVE), assert output size, and compare each row to registry.evaluate(name, row).

Add this failure assertion:

~~~java
BatchOperatorEvaluationException failure = expectThrows(
        BatchOperatorEvaluationException.class,
        () -> registry.evaluateBatch("log_base", batchCall(List.of(
                List.of(8.0, 2.0, 1024.0),
                List.of(-1.0, 2.0, 1024.0),
                List.of(32.0, 2.0, 1024.0)), BatchDomain.OFFLINE_ROW),
                BatchKernelKind.NATIVE));
assert failure.rowIndex() == 1 : failure.rowIndex();
~~~

Add CountingList<E> extending AbstractList<E>. Build expected values from a separate ordinary list, then repeat one CountingList instance four times in an ONLINE_CANDIDATE find_indices call with two rows in each group. Assert Batch equals the precomputed expected values and getCount equals sequence.size() times 2, proving one scan per group without Single evaluations changing the counter.

- [ ] **Step 2: Run the test and verify RED**

Run:

~~~powershell
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$cp = "target/test-classes;target/classes;" + (Get-Content -Raw -LiteralPath "target/test-classpath.txt").Trim()
java -ea -cp $cp com.example.featuredag.DagEngineSelfTest
~~~

Expected: exit non-zero at the first Native-kind assertion because the current kind is SCALAR_ADAPTER.

- [ ] **Step 3: Add shared Batch mechanics**

Add to OperatorSupport:

~~~java
static BatchOperatorEvaluationException batchFailure(
        int rowIndex, RuntimeException error) {
    return new BatchOperatorEvaluationException(rowIndex, error);
}

static IdentityBatchKey identityBatchKey(int groupIndex, Object... identities) {
    return new IdentityBatchKey(groupIndex, identities);
}
~~~

IdentityBatchKey stores groupIndex and a cloned Object array. Its hash uses System.identityHashCode for each object; equals requires the same group, same length, and reference equality at every position. Keep it package-private and nested in OperatorSupport. Do not add static caches, runtime types, or operator algorithms.

- [ ] **Step 4: Implement scalar Native kernels**

Make DiscreteOperator, LogBaseOperator, and GetSequenceLengthOperator implement BatchOperatorKernel and return BatchOperatorResult with ListBatchColumn.

Discrete extracts toValue, toBoundaries, and bucket helpers. Batch converts value first, caches converted boundaries by identity, computes bucket, and wraps the current row.

LogBase adds immutable LogParameterKey and LogParameters helpers. The key uses Double.doubleToLongBits(base/upbound). Preserve this row validation order: finite value, finite base, finite upbound, valid base, positive value, positive upbound. Cache Math.log(base) and upbound, then retain Single's division order for bitwise-equivalent results.

GetSequenceLength extracts evaluateSequence(Object), supporting OperatorSequence, Collection, and arrays. Batch calls it directly per row without a cache.

- [ ] **Step 5: Implement sequence Native kernels**

SliceByIndices extracts slice(sequenceRaw, indicesRaw) and caches successful immutable results by group plus sequence/indices identity.

FindIndices extracts find(sequenceRaw, target) for Single. Batch keeps:

~~~java
Map<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>> indexes =
        new LinkedHashMap<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>>();
~~~

On the first group/sequence identity, scan once into a LinkedHashMap from element to positions, freeze every positions list, then freeze the map. Return the indexed target list or Collections.emptyList. Different groups never share an index.

CountDistinct extracts count(sequenceRaw) and caches Integer results by group plus sequence identity.

ZipConcat extracts zip(arguments). Its ZipBatchKey uses group, reference identity for all sequence inputs, and value equality for the effective delimiter. Cache only successful immutable results and preserve minimum-sequence/equal-length errors.

CalculateDeltaSequence extracts calculate(sequenceRaw, baseRaw). Its DeltaBatchKey uses group, sequence identity, and Double.doubleToLongBits(base). Validate base before key construction and cache only successful immutable results.

Every Batch loop catches RuntimeException for the current row and throws OperatorSupport.batchFailure(row, error).

- [ ] **Step 6: Run the complete self-test and verify GREEN**

Run the Step 2 commands.

Expected: exit 0; eight NATIVE kinds, equivalence, row 1 failure, and two sequence scans for two online groups all pass.

- [ ] **Step 7: Commit Task 1**

Run git diff --check, inspect only the eight built-ins, OperatorSupport, and DagEngineSelfTest, stage those exact paths, and commit:

~~~powershell
git commit -m "Add native batch kernels"
~~~

The user's unrelated files remain unstaged.

### Task 2: Deep Native Batch Performance Demo

**Files:**
- Modify: src/test/java/com/example/featuredag/DagEngineSelfTest.java
- Create: src/main/java/com/example/featuredag/demo/NativeBatchPerformanceDemo.java
- Create: src/main/resources/demo/native-batch-performance.json
- Create: scripts/run-native-batch-performance-demo.sh
- Create: scripts/run-native-batch-performance-demo.ps1

**Interfaces:**
- Consumes: public FeatureDagEngine, online request/batch APIs, and InMemoryRuntimeObserver.
- Produces: NativeBatchPerformanceDemo.main(String[]) and runSmokeTest().

- [ ] **Step 1: Write the failing smoke-test contract**

Call testNativeBatchPerformanceDemo from self-test. Use reflection so tests compile before the production class exists:

~~~java
private static void testNativeBatchPerformanceDemo() {
    try {
        Class<?> demo = Class.forName(
                "com.example.featuredag.demo.NativeBatchPerformanceDemo");
        demo.getMethod("runSmokeTest").invoke(null);
    } catch (ClassNotFoundException error) {
        throw new AssertionError("Native Batch performance demo is missing", error);
    } catch (ReflectiveOperationException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new AssertionError(
                "Native Batch performance demo failed",
                cause == null ? error : cause);
    }
}
~~~

- [ ] **Step 2: Run and verify RED**

Run the Task 1 test command.

Expected: exit non-zero with Native Batch performance demo is missing.

- [ ] **Step 3: Create the deep-expression config**

Define these USER sequence RAW features:

~~~text
codes STRING, labels STRING, all_indices INT,
index_tags STRING, number_sequence DOUBLE
~~~

Define ITEM scalar target_tag STRING and delta_base DOUBLE.

Define complex_distinct and complex_distinct_alias with the exact expression:

~~~text
count_distinct(zip_concat(slice_by_indices(codes, slice_by_indices(all_indices, find_indices(index_tags, target_tag))), slice_by_indices(labels, slice_by_indices(all_indices, find_indices(index_tags, target_tag))), {"delimiter":"|"}))
~~~

Define deep_numeric_bucket and deep_numeric_bucket_alias with:

~~~text
discrete(log_base(get_seq_length(calc_delta_seq(number_sequence, delta_base)), 2, 1048576), [0, 4, 8, 12, 16])
~~~

All outputs are INT, SCALAR, OUTPUT, and scoped to USER plus ITEM.

- [ ] **Step 4: Implement deterministic data and structure checks**

Create a Java 8-compatible final class. Public entrypoints:

~~~java
public static void runSmokeTest() {
    run(new Options(64, 2, 16, 0, 1), false);
}

public static void main(String[] args) {
    run(Options.parse(args), true);
}
~~~

Options accepts optional positional sequenceLength, groupCount, candidatesPerGroup, warmups, and measurements. Defaults are 10000, 8, 1000, 2, 5. All except warmups are positive; warmups is non-negative.

For group g and index i generate:

~~~text
codes[i] = "code-" + ((i + g) % 128)
labels[i] = "label-" + (i % 32)
all_indices[i] = i
index_tags[i] = "tag-" + (i % 64)
number_sequence[i] = i + g + 1.0
~~~

Candidate c uses target_tag tag-(c % 64) and delta_base (c % 32) + 1.0. Use Java 8 collection construction only.

Create one engine targeting the two primary outputs and another targeting all four outputs, each with an InMemoryRuntimeObserver. Run a grouped Batch and assert:

~~~text
alias logical nodes = primary logical nodes + 2
alias physical nodes = primary physical nodes + 2
fusedPhysicalNodeCount = 0
BATCH_NATIVE node snapshot count = 10
each alias value equals its primary value
~~~

- [ ] **Step 5: Implement correctness comparison and timing**

For every warmup and measurement round, run all groups as repeated OnlineGenerateRequest calls, then once as OnlineBatchGenerateRequest. Compare every featureValues map and candidateFeatureValues list before recording timings.

Record System.nanoTime wall samples. Print input sizes, min/median/p95, candidates per second, and latest grouped decode/runtime/encode plus logical/physical/Native/fused node counts. Never assert an acceleration threshold and never print feature payloads.

- [ ] **Step 6: Add dedicated scripts**

Both scripts accept five values with the same defaults, compile with Maven, build target/demo-classpath.txt, and run:

~~~text
com.example.featuredag.demo.NativeBatchPerformanceDemo
~~~

The Bash script selects path separator for Unix versus MINGW/MSYS/CYGWIN. The PowerShell script resolves repository root, checks each exit code, and restores location in finally.

- [ ] **Step 7: Run self-test and bounded demo**

Run the self-test command, then:

~~~powershell
./scripts/run-native-batch-performance-demo.ps1 512 3 64 1 2
~~~

Expected: self-test passes; demo prints timing and nativeNodes=10, fusedNodes=0 after correctness checks.

- [ ] **Step 8: Commit Task 2**

Run git diff --check, inspect the demo/resource/scripts/self-test/plan only, stage their exact paths, and commit:

~~~powershell
git commit -m "Add native batch performance demo"
~~~

### Task 3: Final Verification

**Files:**
- Verify only. If a defect appears, first add a failing assertion to a task-owned test file.

**Interfaces:**
- Consumes: complete implementation.
- Produces: passing self-test, Maven artifacts, and bounded demo report.

- [ ] **Step 1: Run mandatory self-test**

~~~bash
./scripts/run-self-test.sh
~~~

Expected: All DAG engine self tests passed.

- [ ] **Step 2: Run package**

~~~powershell
mvn clean package
~~~

Expected: exit 0 and thin plus shaded JARs under target. This does not replace the explicit self-test.

- [ ] **Step 3: Run bounded performance demo**

~~~powershell
./scripts/run-native-batch-performance-demo.ps1 512 3 64 1 2
~~~

Expected: all structure/correctness assertions pass before timing output.

- [ ] **Step 4: Inspect final repository state**

Run git status --short and git log -3 --oneline --decorate.

Expected: only the user's pre-existing FeatureInputDecoder.java change and root .docx remain outside the feature commits; no generated target files are tracked.
