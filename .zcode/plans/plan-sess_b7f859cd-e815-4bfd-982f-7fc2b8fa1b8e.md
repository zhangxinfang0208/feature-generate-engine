## 目标
在 DagDemo 现有三天计数特征 `auid_omnichannel_paid_cnt_3d`（保持原名不动，输出 [1]）基础上新增两个派生特征：
- `auid_appc3_omnichannel_paid_cnt_div10_365d` = LEAST(ROUND(count/10), 1000)
- `auid_appc3_omnichannel_paid_cnt_log_365d` = LEAST(ROUND(LOG(count)/LOG(1.1)), 1000)

## 改动

### 1. OperatorRegistry.java — 新增两个标量算子（注册在 registerScalarOperators，sub/add/round 附近）
- `least`：2..MAX 参数，deterministic、非参数化、SCALAR。推断：全部输入为 INT 则输出 INT，否则 DOUBLE（unionScopes）。求值：取数值最小值并原样返回该 Number（保证 INT 输入下输出 Integer）。
- `div`：2 参数，→ DOUBLE SCALAR。求值：value/divisor，divisor 为 0 抛 IllegalArgumentException（与 div_num 同风格）。

已确认：planning/physical/runtime 仅对 extractIndustry/count/countIndustry 有算子名特判，新增算子走注册表通用执行路径，无需改规划层。

### 2. DagDemo.java — CONFIG_JSON 追加两个 DERIVED 特征（type=INT、output_policy=OUTPUT、entity_scopes=[USER,SCENE]、value_shape=SCALAR）
- `auid_appc3_omnichannel_paid_cnt_div10_365d`：
  `least(round(div_num(auid_omnichannel_paid_cnt_3d, {"divisor":10})), 1000)`
- `auid_appc3_omnichannel_paid_cnt_log_365d`：
  `least(round(div(log(auid_omnichannel_paid_cnt_3d), log(1.1))), 1000)`

JSON 内引号沿用现有 `\\"` 写法（text block 中 `\"` 不会产生 JSON 转义）。

类型/形状/实体域一致性（C6）验证：div_num→DOUBLE、round→INT、least(INT, 字面量1000)→INT；log→DOUBLE、log(1.1)→DOUBLE、div→DOUBLE、round→INT。域 = count 的 [USER,SCENE] ∪ 字面量空域 = [USER,SCENE] ✓。

预期输出（count=1）：div10 = round(0.1) = 0 → [0]；log = round(log(1)/log(1.1)) = 0 → [0]；原 `auid_omnichannel_paid_cnt_3d=[1]` 不变。

### 3. DagEngineSelfTest.java（自测试同步覆盖）
- `testBusinessOperatorRegistry`：names/arities 增加 `least`(2, MAX)、`div`(2, 2)；新增断言 least(3,5,1)=1、least 混合类型、div(9,2)=4.5、div 除零错误信息含 "divisor"。
- 业务算子推断用例表（32→34）：新增 `least(target, a3)` → INT/SCALAR、`div(a, b)` → DOUBLE/SCALAR，更新计数断言。
- 端到端三天计数用例：config 追加两个新特征，断言 `..._div10_365d=[0]`、`..._log_365d=[0]`。

### 4. AGENTS.md — Demo 输入契约
补充两条新输出特征及预期值，说明其以 3d count 为输入、经 least/round/div_num/div/log 计算（派生特征引用派生特征，验证逻辑层目标驱动构建 C3）。

## 验证
- `./scripts/run-self-test.sh`（java -ea 断言自测试）
- `./scripts/run-demo.sh` 确认控制台输出三个特征值 [1]、[0]、[0]