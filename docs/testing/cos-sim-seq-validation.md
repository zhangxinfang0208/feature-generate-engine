# cos_sim_seq 验证记录

日期：2026-10-14。

## 本次变更

新增 `CosSimSeqOperator`、标准注册、独立 JUnit 4 测试及使用文档。
工作区已有 `first_element` 等未提交改动，验证包含这些改动；标准清单共 26 项。
冻结的 `DagEngineSelfTest.java` 未修改。

## 通过项

- `mvn -q -Dtest=CosSimSeqOperatorTest test`：8 项通过。
- `mvn -q -Dtest=CosSimSeqOperatorTest,ArithmeticOperatorsTest,NumericCastAndExtremeOperatorsTest,IntersectionOperatorTest test`：32 项通过。
- `javac --release 8 -encoding UTF-8 -cp target/classes -d target/cos-sim-java8-check src/main/java/com/example/featuredag/operator/builtin/CosSimSeqOperator.java`：通过，仅 JDK 的 source/target 8 过时警告。
- `git diff --check`：通过。

## 全量验证的既有失败

已显式运行 `scripts/run-self-test.sh`：在冻结自测第 96 行失败，断言标准算子数为 20，当前为 26。
脚本因此未进入 Maven 测试阶段，随后单独执行 `mvn -q test`：305 项，8 失败，0 错误。

将当前 HEAD 导出至 `target/cos-sim-baseline` 后独立验证：

- 基线 `scripts/run-self-test.sh` 在相同断言失败（预期 20，实际 24）。
- 基线 `mvn -q test`：289 项，8 失败，0 错误；失败测试与当前工作区完全相同。

失败测试如下，均不使用 `cos_sim_seq`：

1. `HwdspClick365dAllOperatorsTest.offlineBatchMatchesSingleAndCoversNoTargetSlot`
2. `HwdspClick365dAllOperatorsTest.generatedValuesExactlyMatchThePublishedResultSet`
3. `HwdspClick365dBusinessCasesTest.coversNoMatchAndMisalignedSequenceBoundaries`
4. `HwdspClick365dBusinessCasesTest.generatesExpectedPackageStrength`
5. `HwdspClick365dFullRegistryTest.verbatimPlatformConfigParsesBuildsAndGenerates`
6. `HwdspClick365dFullRegistryTest.documentedSampleRowsMatchEngineOutputs`
7. `HwdspClick365dFullRegistryTest.offlineBatchMatchesSingleAndHandlesNoTargetCategory`
8. `TransformTestExtendedOperatorsTest.batchMatchesSingleAndNoMatchRowKeepsLiteralBaseline`

因此本次算子功能与相关测试通过，但仓库全量验证并非全绿；上述基线问题未在本次算子扩展中修改。

## 基于 PR #44 的提交前复验

PR 分支基于 `codex/first-element`（`a154cbe0b98f2d87c74c32d6f7d23dabd7be2299`），只移入 cos_sim_seq 相关改动，未带入原工作区的性能测试。

- `scripts/run-self-test.sh`：已显式运行，仍在冻结的算子数量断言失败（20 vs 26）。
- `mvn -q test`：303 项，仍为上述同样的 8 个失败，0 错误。
- 四个相关测试类共 32 项通过；Java 8 编译检查与 `git diff --check` 通过。
