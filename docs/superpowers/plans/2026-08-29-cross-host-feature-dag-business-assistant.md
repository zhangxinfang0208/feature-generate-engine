# Cross-host Feature DAG Business Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a zero-runtime-dependency Feature DAG business-assistant skill, packaged once for both ChatGPT/Codex and Claude Code, that validates expression syntax, guides BASE and DERIVED metadata completion, emits frontend property arrays, and performs reachable-subgraph rule validation.

**Architecture:** One shared instruction-only skill under `plugins/feature-dag-business-assistant/skills/` owns the workflow and progressively loads five focused references. Two host manifests point at that same skill directory. Behavioral evals compare fresh-context outputs without and with the skill; no Java, Python, Node, script, MCP, or engine source is required by plugin users.

**Tech Stack:** Agent Skills `SKILL.md`, Markdown references, JSON plugin manifests, YAML OpenAI UI metadata, Codex/Claude Code plugin packaging, isolated subagent behavioral evals, repository Bash self-test.

**Spec:** `docs/superpowers/specs/2026-08-28-cross-host-feature-dag-business-assistant-design.md`

## Global Constraints

- Plugin users need no Java installation, engine repository, local script runtime, MCP server, or executable dependency.
- The plugin contains one shared `SKILL.md`; Codex and Claude Code behavior must not be duplicated.
- Shared skill frontmatter contains only `name` and `description`.
- Initial expression validation is syntax-only; operator existence, arity, type, and shape belong to later semantic validation.
- Existing nonblank conflicting fields are reported for manual correction and never emitted as overwrite properties.
- `NUMBER.data_value` is a JSON number; `LIST.data_value` is a JSON string containing a serialized list such as `"[\"USER\"]"`; `STRING.data_value` is a string; `BOOLEAN.data_value` is the string `"true"` or `"false"`.
- Every emitted property uses `default_value: ""` and `required: "true"`.
- Final rule validation checks only the requested target and its reachable dependencies and ignores unrelated feature entries and non-engine business fields.
- Unknown operator contracts produce `信息不完整`, not an invented valid/invalid verdict.
- A rule-only success must be labeled `规则校验通过`, never `真实引擎校验通过`.
- Standard registry completeness auditing is outside v1; the 21 current operator semantic contracts remain independently extensible.
- Newline style may follow the existing Windows checkout; all text remains UTF-8.

## File Structure

```text
plugins/feature-dag-business-assistant/
├── .codex-plugin/plugin.json
├── .claude-plugin/plugin.json
└── skills/configuring-feature-dag-models/
    ├── SKILL.md
    ├── agents/openai.yaml
    ├── evals/
    │   ├── evals.json
    │   ├── baseline.md
    │   └── with-skill.md
    └── references/
        ├── expression-grammar.md
        ├── feature-fields.md
        ├── operator-contracts.md
        ├── output-contracts.md
        └── validator-extension.md
```

- `SKILL.md`: compact workflow, stage gates, reference routing, and non-negotiable result boundaries.
- `expression-grammar.md`: parser-compatible syntax and AST-style BASE reference extraction.
- `feature-fields.md`: BASE/DERIVED completeness, inference boundaries, reachable-subgraph validation, and conflicts.
- `operator-contracts.md`: all current 21 operator semantic contracts in one extensible table/catalog.
- `output-contracts.md`: exact Chinese response shapes and valid frontend property-array examples.
- `validator-extension.md`: optional future remote validator request/result contract and fallback behavior.
- `evals/evals.json`: realistic prompts and behavioral assertions shared by control and skill-enabled evaluations.
- `evals/baseline.md`, `evals/with-skill.md`: evidence from fresh isolated runs.

---

### Task 1: RED Baseline Behavioral Evals

**Files:**
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/evals.json`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/baseline.md`

**Interfaces:**
- Consumes: approved design spec and the user-authorized isolated-agent test permission.
- Produces: four stable eval case IDs and observed no-skill failures that later skill wording must address.

- [ ] **Step 1: Create the eval catalog before any `SKILL.md` exists**

Create `evals/evals.json` with this top-level shape and exact case IDs:

```json
{
  "skill": "configuring-feature-dag-models",
  "cases": [
    {
      "id": "syntax-stop",
      "purpose": "A missing closing parenthesis must stop before BASE metadata work.",
      "prompt": "请只校验这个表达式是否存在少括号等语法问题：zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {\"delimiter\":\"#\"}",
      "assertions": [
        "Declares syntax invalid",
        "Identifies the unclosed call near the end",
        "Does not ask for BASE configuration"
      ]
    },
    {
      "id": "extract-and-do-not-guess",
      "purpose": "Extract six BASE refs, report an existing conflict, and refuse to guess missing metadata under pressure.",
      "prompt": "请处理下面的表达式并马上给我可复制到前台的配置，不要向我追问。表达式：zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {\"direction\":\"BASE_MINUS_ELEMENT\",\"divisor\":60}), {\"delimiter\":\"#\"})。已知配置：norm_tag1id 序列 type=STRING、seq_max_length=365，缺 definition_type/value_shape/entity_scopes；normalized_tag1_id_h 的 type 为空、seq_max_length=1，缺 definition_type/value_shape/entity_scopes；ts 序列 type=BIGINT、value_shape=SCALAR、seq_max_length=365、entity_scopes=[USER]；其余三个 BASE 尚未提供。",
      "assertions": [
        "Lists exactly six BASE names in first-seen order",
        "Does not treat operator names, object keys, or string literals as BASE features",
        "Reports existing value_shape conflict without emitting an overwrite property",
        "Asks for unknown type or length instead of guessing"
      ]
    },
    {
      "id": "reachable-only",
      "purpose": "Ignore an invalid unrelated derived feature while validating the requested target subtree.",
      "prompt": "请最终校验目标特征 target_score。完整模型：{\"feature_set_name\":\"reachability_case\",\"version\":\"1\",\"features\":[{\"name\":\"user_score\",\"definition_type\":\"BASE\",\"type\":\"INT\",\"value_shape\":\"SCALAR\",\"entity_scopes\":[\"USER\"],\"seq_max_length\":1,\"to_use\":true},{\"name\":\"target_score\",\"definition_type\":\"DERIVED\",\"type\":\"INT\",\"value_shape\":\"SCALAR\",\"entity_scopes\":[\"USER\"],\"expression\":\"add(user_score, 1)\",\"output_policy\":\"OUTPUT\",\"to_use\":true},{\"name\":\"unrelated_broken\",\"definition_type\":\"DERIVED\",\"type\":\"STRING\",\"expression\":\"zip_concat(missing_seq\",\"to_use\":true}]}。",
      "assertions": [
        "Evaluates only target_score and user_score",
        "Does not fail because unrelated_broken has malformed syntax",
        "Labels success as rule validation only"
      ]
    },
    {
      "id": "unknown-operator-incomplete",
      "purpose": "A syntactically valid unknown operator must not receive invented semantics.",
      "prompt": "衍生特征 future_output 的表达式是 future_transform(base_a)。base_a 已配置 definition_type=BASE、type=STRING、value_shape=SCALAR、entity_scopes=[USER]、seq_max_length=1。请校验表达式并生成衍生特征新增属性。",
      "assertions": [
        "Initial syntax passes",
        "Semantic result is incomplete because future_transform has no contract",
        "Does not claim the operator is registered or illegal",
        "Does not invent DERIVED type, shape, scope, or maximum length"
      ]
    }
  ]
}
```

The controller prompts used for isolated runs must contain the following exact scenario data:

```text
syntax-stop expression:
zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"}

extract-and-do-not-guess expression:
zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {"direction":"BASE_MINUS_ELEMENT","divisor":60}), {"delimiter":"#"})

extract-and-do-not-guess partial metadata:
- auid_hwdsp_clk_norm_tag1id_seq_time_365d: type=STRING, seq_max_length=365; definition_type/value_shape/entity_scopes absent
- normalized_tag1_id_h: type blank, seq_max_length=1; definition_type/value_shape/entity_scopes absent
- auid_hwdsp_clk_ts_seq_time_365d: type=BIGINT, value_shape=SCALAR, seq_max_length=365, entity_scopes=[USER]
- the other three BASE entries are not supplied

reachable-only model:
{"feature_set_name":"reachability_case","version":"1","features":[{"name":"user_score","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["USER"],"seq_max_length":1,"to_use":true},{"name":"target_score","definition_type":"DERIVED","type":"INT","value_shape":"SCALAR","entity_scopes":["USER"],"expression":"add(user_score, 1)","output_policy":"OUTPUT","to_use":true},{"name":"unrelated_broken","definition_type":"DERIVED","type":"STRING","expression":"zip_concat(missing_seq","to_use":true}]}
target: target_score

unknown-operator-incomplete expression:
future_transform(base_a)
target: future_output
base_a metadata: definition_type=BASE, type=STRING, value_shape=SCALAR, entity_scopes=[USER], seq_max_length=1
```

- [ ] **Step 2: Run all four cases in fresh contexts without skill guidance**

Spawn one isolated agent per case with `fork_turns: "none"`. Tell each agent only:

```text
Act as a feature-expression configuration assistant. Complete the scenario below without reading any repository design, plan, eval, plugin, or skill files. Return the answer you would give the business user.
```

Construct each agent message by concatenating that instruction with the matching case's `prompt` value from `evals/evals.json`. The agent message itself must not mention the eval file or ask the agent to read it.

Do not provide the assertions or intended result to the agent. Run at most three agents concurrently, then run the fourth after a slot is free.

- [ ] **Step 3: Grade and record the control outputs**

Create `evals/baseline.md` with the literal headings `## syntax-stop`, `## extract-and-do-not-guess`, `## reachable-only`, and `## unknown-operator-incomplete`. Under each heading, add `### Raw output` followed by the complete agent response, `### Assertion results` with one `PASS` or `FAIL` bullet per assertion and one-sentence evidence, and `### Observed failure pattern` naming the concrete omission, guess, scope error, or wrong output shape. Write `No failure observed` only when every assertion for that case passed.

At least one control case must demonstrate a material failure. If every case passes, add a fifth control case that combines incomplete BASE metadata, the instruction “do not ask questions,” and a request for immediately copyable properties; run it before authoring the skill.

- [ ] **Step 4: Verify RED evidence exists and the skill is still absent**

Run:

```powershell
if (Test-Path 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md') { throw 'SKILL.md exists before RED' }
$baseline = Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/baseline.md' -Raw
if ($baseline -notmatch 'FAIL') { throw 'No failing baseline assertion recorded' }
```

Expected: command exits successfully.

- [ ] **Step 5: Commit the RED evidence**

```powershell
git add -- 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/evals.json' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/baseline.md'
git commit -m "Record feature DAG skill baselines"
```

---

### Task 2: Dual-host Plugin Shell and Minimal Skill

**Files:**
- Create: `plugins/feature-dag-business-assistant/.codex-plugin/plugin.json`
- Create: `plugins/feature-dag-business-assistant/.claude-plugin/plugin.json`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/agents/openai.yaml`

**Interfaces:**
- Consumes: RED failure patterns in `evals/baseline.md`.
- Produces: plugin identity `feature-dag-business-assistant`, shared skill identity `configuring-feature-dag-models`, and reference links used by Tasks 3-4.

- [ ] **Step 1: Read the plugin and skill authoring instructions before scaffolding**

Use `plugin-creator`, `skill-creator`, `superpowers:writing-skills`, and `superpowers:test-driven-development`. Preserve the approved paths and do not add scripts, MCP declarations, hooks, assets, or runtime dependencies.

- [ ] **Step 2: Create the Codex manifest**

Create `.codex-plugin/plugin.json` with these semantic values:

```json
{
  "name": "feature-dag-business-assistant",
  "version": "0.1.0",
  "description": "Help business users prepare and validate Feature DAG expressions and model feature metadata.",
  "skills": "./skills/",
  "interface": {
    "displayName": "Feature DAG Business Assistant",
    "shortDescription": "Prepare Feature DAG model metadata",
    "category": "Developer Tools"
  }
}
```

If the current `plugin-creator` validator rejects only the category spelling, use its documented developer-tools category value and keep all other values unchanged.

- [ ] **Step 3: Create the Claude Code manifest pointing to the same skill**

Create `.claude-plugin/plugin.json`:

```json
{
  "name": "feature-dag-business-assistant",
  "displayName": "Feature DAG Business Assistant",
  "version": "0.1.0",
  "description": "Help business users prepare and validate Feature DAG expressions and model feature metadata.",
  "skills": "./skills/"
}
```

- [ ] **Step 4: Write the minimal shared skill entrypoint**

Use this frontmatter and keep host-specific invocation syntax out of the body:

```markdown
---
name: configuring-feature-dag-models
description: Use when business users are preparing Feature DAG expressions, BASE feature metadata, DERIVED feature properties, or a final model feature-set JSON for rule validation.
---
```

The body must state the core principle in two sentences: gather evidence in stages, and never invent missing feature semantics. Add five reference links with explicit read conditions, even though Tasks 3-4 create the referenced files next.

- [ ] **Step 5: Generate Codex UI metadata**

Run the bundled generator with:

```powershell
python 'C:\Users\Administrator.SHI--20230219CS\.codex\skills\.system\skill-creator\scripts\generate_openai_yaml.py' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models' --interface 'display_name=Feature DAG Model Configurator' --interface 'short_description=Validate expressions and prepare model feature metadata' --interface 'default_prompt=Use $configuring-feature-dag-models to validate this Feature DAG expression and guide me through the required model metadata.'
```

Review the generated YAML and ensure it has no tool dependencies or explicit-only invocation policy.

- [ ] **Step 6: Validate the package shell**

Run:

```powershell
Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/.codex-plugin/plugin.json' -Raw | ConvertFrom-Json | Out-Null
Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/.claude-plugin/plugin.json' -Raw | ConvertFrom-Json | Out-Null
claude plugin validate 'plugins/feature-dag-business-assistant'
```

Expected: both JSON parses succeed and Claude validation reports a valid plugin. Missing reference-file warnings are acceptable only until Task 3 and must disappear before Task 6.

- [ ] **Step 7: Commit the dual-host shell**

```powershell
git add -- 'plugins/feature-dag-business-assistant/.codex-plugin/plugin.json' 'plugins/feature-dag-business-assistant/.claude-plugin/plugin.json' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/agents/openai.yaml'
git commit -m "Scaffold cross-host feature DAG plugin"
```

---

### Task 3: Core Workflow, Grammar, Feature Rules, and Output Contracts

**Files:**
- Modify: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/expression-grammar.md`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/feature-fields.md`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/output-contracts.md`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/validator-extension.md`

**Interfaces:**
- Consumes: shared skill identity from Task 2 and baseline failure patterns from Task 1.
- Produces: stage state machine, syntax/base extraction rules, metadata decision rules, exact frontend JSON formats, and future validator protocol. Task 4 plugs operator inference into the semantic stage.

- [ ] **Step 1: Expand `SKILL.md` into a compact stage controller**

Write positive stage contracts in this order:

```text
1. Syntax intake: expression + business-provided DERIVED name; syntax only.
2. BASE discovery: ordered unique feature refs after successful syntax.
3. BASE completion: request current entries, ask only unresolved facts, emit additions only for absent fields, report conflicts.
4. DERIVED completion: infer through operator contracts, ask for unknown max length, emit absent fields.
5. Final validation: target + reachable dependencies only.
6. Verdict: PASS/FAIL/INCOMPLETE with explicit rule-only boundary.
```

At each stage identify the one reference that must be read. Require `operator-contracts.md` only after syntax succeeds and semantic inference is needed. Keep `SKILL.md` under 500 words excluding frontmatter.

- [ ] **Step 2: Write parser-compatible expression grammar**

`expression-grammar.md` must cover:

- identifier start `[letter|_]` and continuation `[letter|digit|_|.]`;
- function calls, nested calls, positional and named arguments, and rejection of positional arguments after a named argument;
- single/double quoted strings and `\n`, `\r`, `\t`, `\\`, `\"`, and `\'` escapes;
- integer, BIGINT-range integer, decimal, negative numeric, boolean, and null literals;
- arrays and objects; object keys can be identifiers or strings; duplicate keys invalid;
- no trailing comma and no trailing token;
- maximum nesting depth 200;
- explicit `\_` normalization only outside strings with a user-visible notice;
- AST-style reference extraction, including refs nested in array elements and object values.

Include the six expected refs from the approved long expression in this exact order:

```text
auid_hwdsp_clk_crtv_clstid_seq_time_365d
auid_hwdsp_clk_norm_tag1id_seq_time_365d
normalized_tag1_id_h
auid_hwdsp_clk_slotid_seq_time_365d
auid_hwdsp_clk_ts_seq_time_365d
timestamp_s
```

- [ ] **Step 3: Write BASE/DERIVED field and reachability rules**

`feature-fields.md` must define:

- `name` is identity; `raw_name` is not a substitute;
- blank string and null count as missing;
- BASE core fields: `definition_type`, `type`, `value_shape`, `entity_scopes`, `seq_max_length`;
- supported data types `INT`, `BIGINT`, `DOUBLE`, `STRING`, `BOOLEAN`, `OBJECT`, `EVENT_SEQUENCE`, and the reason `UNKNOWN` is not a completed business declaration;
- shapes `SCALAR` and `SEQUENCE` for ordinary business inputs, with `EVENT_SEQUENCE` requiring `SEQUENCE`;
- scopes `USER`, `SCENE`, and `ITEM` and exact-set comparison for declared DERIVED scopes;
- positive sequence maximum, including the rule that a sequence may legitimately have maximum length 1 and therefore needs explicit `value_shape`;
- optional `dft`, with type/shape compatibility when present;
- `to_use=false` as a reachable-feature conflict;
- absent fields versus conflicting fields and the no-overwrite rule;
- target-rooted recursive reachability, derived-to-derived traversal, relevant duplicate rejection, and DFS cycle detection;
- safe numeric widening `INT -> BIGINT -> DOUBLE` only.

- [ ] **Step 4: Write exact response and frontend JSON contracts**

`output-contracts.md` must contain the approved Chinese phase templates and at least these four valid property examples:

```json
[
  {
    "raw_name": "seq_max_length",
    "data_value": 365,
    "data_type": "NUMBER",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "entity_scopes",
    "data_value": "[\"USER\"]",
    "data_type": "LIST",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "value_shape",
    "data_value": "SEQUENCE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "to_use",
    "data_value": "true",
    "data_type": "BOOLEAN",
    "default_value": "",
    "required": "true"
  }
]
```

Also include a DERIVED expression property whose `data_value` is a legal JSON string containing escaped object-literal quotes. State that unsupported object-valued additions return `信息不完整` instead of malformed JSON.

- [ ] **Step 5: Define the optional remote validator contract**

`validator-extension.md` must define:

```json
{
  "target_feature": "derived_feature_name",
  "feature_set": {
    "feature_set_name": "model_name",
    "version": "1",
    "features": []
  }
}
```

Define `PASS`, `FAIL`, and `INCOMPLETE`, with each issue containing `stage`, optional `feature`, optional `field`, `message`, and optional `offset`. State the routing rule: use a compatible available validator first; on absence/failure, disclose the fallback and run rule validation; never relabel fallback success as remote success.

- [ ] **Step 6: Run static checks for core references**

Run:

```powershell
$root = 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models'
$required = @('references/expression-grammar.md','references/feature-fields.md','references/output-contracts.md','references/validator-extension.md')
foreach ($relative in $required) { if (-not (Test-Path -LiteralPath (Join-Path $root $relative))) { throw "Missing $relative" } }
$entry = Get-Content -LiteralPath (Join-Path $root 'SKILL.md') -Raw
foreach ($relative in $required) { if ($entry -notmatch [regex]::Escape($relative)) { throw "SKILL.md does not route to $relative" } }
if ($entry -match '真实引擎校验通过') { throw 'Entrypoint must not claim real validation' }
```

Expected: command exits successfully.

- [ ] **Step 7: Commit the core workflow**

```powershell
git add -- 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/expression-grammar.md' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/feature-fields.md' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/output-contracts.md' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/validator-extension.md'
git commit -m "Define feature DAG business workflow"
```

---

### Task 4: Current 21 Operator Semantic Contracts

**Files:**
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/operator-contracts.md`

**Interfaces:**
- Consumes: `DataType`, `ValueShape`, and sequence-length terminology from `feature-fields.md`.
- Produces: a catalog keyed by operator name with arity, input constraints, output inference, scope propagation, length propagation, and runtime-only checks used by DERIVED completion.

- [ ] **Step 1: Build the contract catalog from authoritative repository sources**

Read `InitialBusinessOperators.java` and every independent class under `src/main/java/com/example/featuredag/operator/builtin/`. Document exactly these names and no registry-completeness verdict:

```text
discrete
log_base
slice_by_indices
find_indices
find_indices_any
get_seq_length
count_distinct
zip_concat
concat
list_concat
hit
group_count_concat
calc_delta_seq
to_int
to_bigint
min
max
add
sub
mul
div
```

- [ ] **Step 2: Give every operator the same explicit fields**

For each operator, document:

```text
Signature and arity
Static input type/shape constraints
Output type and shape
Entity-scope propagation
seq_max_length propagation
Runtime-only checks
Configuration-object keys, when present
```

Do not imply stronger compile-time checks than the current engine performs. Where runtime accepts values more broadly than inference proves, distinguish “required for reliable business configuration” from “currently rejected during engine construction.”

- [ ] **Step 3: Encode the non-obvious length rules**

Include at least these length contracts:

- pass-through sequence transforms (`slice_by_indices`, `to_int`, `to_bigint`, `calc_delta_seq`) preserve or upper-bound by the selected input length;
- `find_indices` and `find_indices_any` output at most the searched sequence length;
- `zip_concat` requires equal runtime lengths and has that shared upper bound;
- `list_concat` upper-bounds by the sum of contributing sequence maxima;
- `get_seq_length`, `count_distinct`, arithmetic, extrema, `concat`, and `log_base` produce scalar length 1;
- when an operator's exact maximum cannot be proven from known inputs, ask the business instead of using a guessed number.

- [ ] **Step 4: Verify the catalog contains each standard name exactly once**

Run:

```powershell
$path = 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/operator-contracts.md'
$text = Get-Content -LiteralPath $path -Raw
$names = @('discrete','log_base','slice_by_indices','find_indices','find_indices_any','get_seq_length','count_distinct','zip_concat','concat','list_concat','hit','group_count_concat','calc_delta_seq','to_int','to_bigint','min','max','add','sub','mul','div')
foreach ($name in $names) {
    $count = ([regex]::Matches($text, "(?m)^## ``$([regex]::Escape($name))``$")).Count
    if ($count -ne 1) { throw "$name heading count is $count" }
}
```

Expected: every heading count is 1.

- [ ] **Step 5: Confirm `SKILL.md` routes semantic inference to the catalog**

Run:

```powershell
$entry = Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md' -Raw
if ($entry -notmatch 'references/operator-contracts\.md') { throw 'Missing operator contract routing' }
```

Expected: command exits successfully.

- [ ] **Step 6: Commit the operator catalog**

```powershell
git add -- 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/operator-contracts.md'
git commit -m "Document feature DAG operator contracts"
```

---

### Task 5: GREEN Behavioral Evals and Skill Refinement

**Files:**
- Modify: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md`
- Modify as observed: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references/*.md`
- Create: `plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/with-skill.md`

**Interfaces:**
- Consumes: exact cases from `evals/evals.json`, baseline evidence, and the complete shared skill.
- Produces: assertion-level evidence that the skill improves the observed control failures without inventing engine execution.

- [ ] **Step 1: Run the same four scenarios with the skill in fresh contexts**

Spawn one isolated agent per case with `fork_turns: "none"`. Give it only:

```text
Read and faithfully use the skill at plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md, including only the references it routes you to for the current stage. Complete the scenario below and return the answer to the business user.
```

Construct each agent message by concatenating that instruction with the matching case's same `prompt` value from `evals/evals.json`. The evaluator may read the named skill path, but must not receive the assertions, baseline results, or intended answer.

Do not provide assertions, baseline conclusions, or proposed fixes to the evaluator.

- [ ] **Step 2: Record outputs and assertion evidence**

Create `evals/with-skill.md` with the same literal case headings and evidence sections as `baseline.md`. Add `### Comparison with baseline` under every case, followed by an `Improved:` bullet naming the specific changed behavior and a `Regressed:` bullet naming a regression or the exact text `None observed`.

- [ ] **Step 3: Apply minimal GREEN/REFACTOR corrections**

For each failed assertion, classify the failure before editing:

- wrong output shape: strengthen the positive response recipe in `output-contracts.md`;
- missing required element: add it to the relevant structural template;
- wrong conditional behavior: key the instruction to the observable stage or field state;
- missing domain knowledge: add only the needed rule to the relevant reference;
- entrypoint routing failure: clarify when `SKILL.md` requires that reference.

Rerun only the failing case in a fresh context after each correction. Do not add generic prohibitions unrelated to an observed failure.

- [ ] **Step 4: Verify all skill-enabled assertions pass**

Run:

```powershell
$results = Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/with-skill.md' -Raw
if ($results -match '(?m)^- FAIL') { throw 'Skill-enabled eval still has failures' }
foreach ($id in @('syntax-stop','extract-and-do-not-guess','reachable-only','unknown-operator-incomplete')) {
    if ($results -notmatch [regex]::Escape("## $id")) { throw "Missing eval result $id" }
}
```

Expected: command exits successfully.

- [ ] **Step 5: Commit the verified skill behavior**

```powershell
git add -- 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/SKILL.md' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/references' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/evals/with-skill.md'
git commit -m "Verify feature DAG skill behavior"
```

---

### Task 6: Cross-host Validation, Review, and Repository Verification

**Files:**
- Modify only if validation or review finds a defect: `plugins/feature-dag-business-assistant/**`

**Interfaces:**
- Consumes: complete dual-host plugin and passing behavioral evals.
- Produces: validated package, independent review evidence, and green repository verification.

- [ ] **Step 1: Run the Agent Skills validator**

```powershell
python 'C:\Users\Administrator.SHI--20230219CS\.codex\skills\.system\skill-creator\scripts\quick_validate.py' 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models'
```

Expected: validation succeeds with no invalid frontmatter, naming, or unfinished scaffold markers.

- [ ] **Step 2: Validate both host manifests and the Claude package**

```powershell
$codex = Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/.codex-plugin/plugin.json' -Raw | ConvertFrom-Json
$claude = Get-Content -LiteralPath 'plugins/feature-dag-business-assistant/.claude-plugin/plugin.json' -Raw | ConvertFrom-Json
if ($codex.name -ne $claude.name) { throw 'Plugin names differ' }
if ($codex.version -ne $claude.version) { throw 'Plugin versions differ' }
if ($codex.skills -ne './skills/' -or $claude.skills -ne './skills/') { throw 'Host manifests do not share ./skills/' }
if (Test-Path -LiteralPath 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models/scripts') { throw 'Runtime scripts are forbidden in v1' }
claude plugin validate --strict 'plugins/feature-dag-business-assistant'
```

Expected: PowerShell assertions succeed and Claude strict validation exits 0. If strict validation flags `.codex-plugin` only as an unrecognized cross-host directory, rerun without `--strict`, record that compatibility warning in the final handoff, and require ordinary validation to exit 0.

- [ ] **Step 3: Run placeholder, link, formatting, and boundary checks**

```powershell
$root = 'plugins/feature-dag-business-assistant/skills/configuring-feature-dag-models'
$markers = @('T' + 'BD', 'T' + 'ODO', 'FIX' + 'ME', '真实引擎校验通过')
rg -n ($markers -join '|') $root
if ($LASTEXITCODE -eq 0) { throw 'Found placeholder or forbidden success claim' }
git diff --check
```

Expected: `rg` finds nothing and `git diff --check` reports no whitespace errors.

- [ ] **Step 4: Request independent spec-compliance and quality review**

Use `superpowers:requesting-code-review`. Give the reviewer only the design spec, implementation plan, plugin directory, and eval evidence. Require findings to name an exact file and rule. Fix all confirmed issues, rerun the affected behavioral case and structural validator, and commit the fixes before continuing.

- [ ] **Step 5: Run the mandatory repository self-test**

From PowerShell run the repository Bash script:

```powershell
bash ./scripts/run-self-test.sh
```

Expected: the legacy `java -ea` self-tests and Maven JUnit 4 tests both pass.

- [ ] **Step 6: Run final verification from a clean diff**

Use `superpowers:verification-before-completion`, then run:

```powershell
git status --short
git log -6 --oneline
```

Expected: no uncommitted plugin changes remain; recent commits show RED evidence, plugin shell, workflow, operator contracts, and behavior verification. Report the exact validation and self-test commands in the final handoff.
