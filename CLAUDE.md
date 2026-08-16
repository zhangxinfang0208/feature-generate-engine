# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A three-layer feature expression DAG engine reference implementation (Java 21). Feature definitions are
compiled into an immutable logical DAG, analyzed by a read-only planner into a physical plan, then executed
by the runtime.

**`AGENTS.md` at the repo root is the authoritative contribution guide — read it before making non-trivial
changes.** It defines the layering rules (C1–C10), operator implementation constraints, the JDK 1.8 syntax
restriction on builtin operator code, and testing/commit conventions. This file summarizes commands and
architecture; it does not restate those rules.

## Commands

```bash
mvn clean package                              # compile; produces thin JAR + shaded target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
mvn test                                       # run JUnit 4 tests only (*Test.java), via surefire
bash scripts/run-self-test.sh                  # REQUIRED before every commit: runs legacy `java -ea` self-tests, then `mvn test`
./scripts/run-initial-operator-demos.sh all    # run the builtin-operator demo entry points (also: scalar|sequence|batch)
```

- Two distinct test styles coexist and are run differently:
  - Legacy self-tests (`*SelfTest.java`, e.g. `DagEngineSelfTest`) use plain Java `assert` and are excluded
    from surefire; only `run-self-test.sh` (via `java -ea`) executes them.
  - New tests are JUnit 4 (`org.junit.Test`), live in `src/test/java` as standalone `*Test.java` files, and
    run automatically under `mvn test`/`mvn package`.
- To run a single JUnit test: `mvn test -Dtest=ArithmeticOperatorsTest`
- `run-self-test.sh` enforces JDK 21+ and fails fast otherwise.
- Demo classes have no `Main-Class` in the packaged JARs; run them via the scripts above or an IDE.

## Architecture

Package root: `src/main/java/com/example/featuredag/`

```
definition   L0  FeatureDefinition, value types — immutable, self-validating
expression   L0  Expression AST + parser (discarded after logical DAG construction, not persisted)
config       L0  JSON config loading/mapping
logical      L1  LogicalDagBuilder: builds an immutable DAG backward from targetFeatures
planning     L2  Read-only planner: reference counts, reachable roots, cache eligibility (NodePlanningMetadata)
physical     L2  PhysicalPlanner + PhysicalRewriteRule-based fusion; one physical output slot per unfused node
runtime      L3  DagRuntime: executes the physical plan, Single/Batch kernel dispatch, caching
operator     —   OperatorSemantic/OperatorDefinition protocol; builtin/ holds one independent .java file per operator
api          —   Public init/generate entry points (FeatureDagEngine)
demo         —   Runnable demos exercising only the standard operator set via the public API
```

Dependencies are strictly one-directional: `definition/expression/config → logical → planning/physical →
runtime`. Planning and physical layers never mutate logical nodes; core planning/runtime code never branches
on a specific operator's name — new behavior goes through `PhysicalRewriteRule` / `PhysicalExecutorRegistry`
registrations instead.

### Operators

`OperatorRegistry.standard()` registers the single explicit list from `InitialBusinessOperators`; do not add
a forwarding aggregation layer. The authoritative first-wave contract in `AGENTS.md` is restricted to these
8 operators: `discrete`, `log_base`, `slice_by_indices`, `find_indices`, `get_seq_length`, `count_distinct`,
`zip_concat`, and `calc_delta_seq`. Working branches may contain operator extensions, but they do not broaden
that contribution contract unless `AGENTS.md` is updated first.

Each operator has its own `.java` file under `operator/builtin/` implementing its own metadata, type/shape
inference, and single-row evaluation (`SingleOperatorKernel` is the semantic baseline). Only
`find_indices`, `count_distinct`, `zip_concat`, and `calc_delta_seq` provide a native `BatchOperatorKernel`
(measured reuse benefit within a batch); the rest are batch-adapted row-by-row via
`SingleLoopBatchOperatorKernel` — do not add native Batch kernels for them without re-measuring the
cost/benefit model described in `AGENTS.md`.

### JDK version split

The overall build targets Java 21 (`maven.compiler.release=21`), but the first-wave 8 operators, their shared
support code in `operator.builtin`, and the first-wave demos are restricted to JDK 1.8-compatible syntax and
APIs (no `record`, text blocks, pattern-matching `instanceof`, `List.of/copyOf`, `Stream.toList`,
`List.getFirst/getLast`) so that code can be extracted or generated independently. This does not mean the
repo compiles under JDK 1.8 as a whole.

## Further reading

- `docs/architecture/` — operator optimization/extension, single vs. batch execution, online grouped batch
  execution, runtime observability, physical node fusion, sequence-view operator support.
- `docs/testing/` — golden case docs and the operator batch comparison report.
- `docs/superpowers/plans/` and `docs/superpowers/specs/` — historical design docs for past features, dated.
