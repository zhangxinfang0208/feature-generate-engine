import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const defaultFile = fileURLToPath(new URL("protocol-green.raw.jsonl", import.meta.url));
const inputFile = process.argv[2] || defaultFile;
const source = readFileSync(inputFile, "utf8");
const lines = source.endsWith("\n") ? source.slice(0, -1).split(/\r?\n/) : source.split(/\r?\n/);
if (lines.some((line) => line.length === 0)) throw new Error("Raw JSONL contains a blank line.");

const expectedCases = [
  ["EXPRESSION_INPUT", "SYNTAX_INTAKE"],
  ["MODEL_WRAPPER", "FINAL_VALIDATION"],
  ["MODEL_WRAPPER", "FINAL_VALIDATION"],
  ["MODEL_WRAPPER", "FINAL_VALIDATION"],
  ["MODEL_WRAPPER", "FINAL_VALIDATION"],
  ["MODEL_WRAPPER", "FINAL_VALIDATION"],
  ["SYNTAX_CORRECTION", "SYNTAX_INTAKE"],
  ["BASE_METADATA", "BASE_COMPLETION"],
  ["CONFLICT_CORRECTION", "BASE_COMPLETION"],
  ["OPERATOR_CONTRACT", "DERIVED_COMPLETION"],
  ["MODEL_CORRECTION", "VERDICT"],
  ["MODEL_WRAPPER", "FINAL_VALIDATION"]
];

const questions = {
  EXPRESSION_INPUT: "请按 expected_input_format 提供一个完整的特征表达式。",
  SYNTAX_CORRECTION: "请修正表达式语法后重新提交完整表达式；系统不会自动补写或修复。",
  CONFLICT_CORRECTION: "请业务人工修正 conflicts 中的冲突，并按 expected_input_format 返回修正后的特征配置。",
  BASE_METADATA: "请按 expected_input_format 提供 required_features 中 BASE 特征的当前配置。",
  DERIVED_NAME: "请提供当前表达式对应的 DERIVED 特征名称。",
  OPERATOR_CONTRACT: "请提供缺失算子的语义契约，或直接确认 DERIVED 输出字段。",
  DERIVED_METADATA: "请按 pending_facts 确认 DERIVED 特征中无法由算子契约确定的字段。",
  MODEL_CORRECTION: "请按 issues 修正模型并应用 additions（如有），然后按 expected_input_format 重新提交 target_feature 与完整模型包装。",
  MODEL_WRAPPER: "请提供 target_feature 以及完整的 feature_set_name、version、features 模型包装。",
  NONE: "无需补充信息。"
};

const requestStages = {
  EXPRESSION_INPUT: "SYNTAX_INTAKE",
  SYNTAX_CORRECTION: "SYNTAX_INTAKE",
  CONFLICT_CORRECTION: "BASE_COMPLETION",
  BASE_METADATA: "BASE_COMPLETION",
  DERIVED_NAME: "DERIVED_COMPLETION",
  OPERATOR_CONTRACT: "DERIVED_COMPLETION",
  DERIVED_METADATA: "DERIVED_COMPLETION",
  MODEL_CORRECTION: "VERDICT",
  MODEL_WRAPPER: "FINAL_VALIDATION",
  NONE: "VERDICT"
};

const canonicalFields = [
  "definition_type", "expression", "output_policy", "type", "value_shape",
  "entity_scopes", "seq_max_length", "to_use"
];
const currentStages = ["SYNTAX_INTAKE", "BASE_DISCOVERY", "BASE_COMPLETION", "DERIVED_COMPLETION", "FINAL_VALIDATION", "VERDICT"];
const stageStatuses = ["PASS", "FAIL", "PENDING", "INCOMPLETE", "SKIPPED"];

function fail(label, message) {
  throw new Error(`${label}: ${message}`);
}

function assert(condition, label, message) {
  if (!condition) fail(label, message);
}

function exactKeys(value, keys, label) {
  assert(value && typeof value === "object" && !Array.isArray(value), label, "must be an object");
  const actual = Object.keys(value);
  assert(actual.length === keys.length && actual.every((key, index) => key === keys[index]),
    label, `key order ${JSON.stringify(actual)} != ${JSON.stringify(keys)}`);
}

function uniqueStrings(values, label) {
  assert(Array.isArray(values) && values.every((value) => typeof value === "string"), label, "must be a string array");
  assert(new Set(values).size === values.length, label, "must not contain duplicates");
}

function canonicalFieldOrder(values, label) {
  uniqueStrings(values, label);
  let previous = -1;
  for (const value of values) {
    const rank = canonicalFields.indexOf(value);
    assert(rank >= 0 && rank > previous, label, `field ${value} is not in canonical order`);
    previous = rank;
  }
}

function validateIssue(issue, label) {
  exactKeys(issue, ["code", "feature_name", "field", "offset", "message"], label);
  assert(typeof issue.code === "string" && typeof issue.message === "string", label, "code/message must be strings");
  assert(issue.feature_name === null || typeof issue.feature_name === "string", label, "invalid feature_name");
  assert(issue.field === null || typeof issue.field === "string", label, "invalid field");
  assert(issue.offset === null || Number.isInteger(issue.offset), label, "invalid offset");
}

function validateCompletion(stage, label) {
  exactKeys(stage, ["status", "message", "pending_facts", "conflicts", "additions"], label);
  for (const [index, fact] of stage.pending_facts.entries()) {
    exactKeys(fact, ["feature_name", "fields", "reason"], `${label}.pending_facts[${index}]`);
    canonicalFieldOrder(fact.fields, `${label}.pending_facts[${index}].fields`);
  }
  for (const [index, conflict] of stage.conflicts.entries()) {
    exactKeys(conflict, ["feature_name", "field", "existing_value", "required_value", "message"], `${label}.conflicts[${index}]`);
  }
  for (const [groupIndex, group] of stage.additions.entries()) {
    exactKeys(group, ["feature_name", "feature_kind", "properties"], `${label}.additions[${groupIndex}]`);
    assert(group.feature_kind === "BASE" || group.feature_kind === "DERIVED", label, "invalid feature_kind");
    const propertyNames = [];
    for (const [propertyIndex, property] of group.properties.entries()) {
      const propertyLabel = `${label}.additions[${groupIndex}].properties[${propertyIndex}]`;
      exactKeys(property, ["raw_name", "data_value", "data_type", "default_value", "required"], propertyLabel);
      propertyNames.push(property.raw_name);
      assert(["NUMBER", "LIST", "STRING", "BOOLEAN"].includes(property.data_type), propertyLabel, "unsupported data_type");
      assert(property.default_value === "" && property.required === "true", propertyLabel, "invalid fixed values");
      if (property.data_type === "NUMBER") assert(typeof property.data_value === "number", propertyLabel, "NUMBER must be numeric");
      if (property.data_type === "STRING") assert(typeof property.data_value === "string", propertyLabel, "STRING must be a string");
      if (property.data_type === "BOOLEAN") assert(property.data_value === "true" || property.data_value === "false", propertyLabel, "BOOLEAN must be a string boolean");
      if (property.data_type === "LIST") {
        assert(typeof property.data_value === "string", propertyLabel, "LIST must be a serialized string");
        let parsed;
        try { parsed = JSON.parse(property.data_value); } catch { fail(propertyLabel, "LIST is not serialized JSON"); }
        assert(Array.isArray(parsed), propertyLabel, "LIST serialized value must decode to an array");
      }
    }
    canonicalFieldOrder(propertyNames, `${label}.additions[${groupIndex}].properties`);
  }
}

function validateSyntax(stage, label) {
  exactKeys(stage, ["status", "message", "original_input", "normalized_input", "audit", "issues"], label);
  assert(["PASS", "FAIL", "PENDING"].includes(stage.status), label, "invalid syntax status");
  assert(typeof stage.message === "string", label, "message must be a string");
  uniqueStrings(stage.audit, `${label}.audit`);
  if (stage.status === "PENDING") {
    assert(stage.original_input === null && stage.normalized_input === null && stage.audit.length === 0 && stage.issues.length === 0,
      label, "PENDING syntax state must have null inputs and empty evidence");
  } else {
    assert(typeof stage.original_input === "string" && stage.original_input.length > 0
      && typeof stage.normalized_input === "string" && stage.normalized_input.length > 0,
      label, `${stage.status} syntax state requires non-empty string inputs`);
    assert(stage.status === "PASS" ? stage.issues.length === 0 : stage.issues.length > 0,
      label, `${stage.status} syntax issue state is inconsistent`);
  }
}

function validateExpectedInput(request, label) {
  const input = request.expected_input_format;
  if (request.request_type === "EXPRESSION_INPUT" || request.request_type === "SYNTAX_CORRECTION") {
    exactKeys(input, ["expression"], label);
    assert(input.expression === "...", label, "expression placeholder must be literal ...");
  } else if (request.request_type === "BASE_METADATA" || request.request_type === "CONFLICT_CORRECTION") {
    exactKeys(input, ["features"], label);
    assert(Array.isArray(input.features) && input.features.length === 0, label, "features must be []");
  } else if (request.request_type === "DERIVED_NAME") {
    exactKeys(input, ["derived_feature_name"], label);
    assert(input.derived_feature_name === "...", label, "derived name placeholder must be literal ...");
  } else if (request.request_type === "OPERATOR_CONTRACT" || request.request_type === "DERIVED_METADATA") {
    exactKeys(input, ["feature_name", "fields"], label);
    exactKeys(input.fields, [], `${label}.fields`);
  } else if (request.request_type === "MODEL_WRAPPER" || request.request_type === "MODEL_CORRECTION") {
    exactKeys(input, ["target_feature", "feature_set"], label);
    exactKeys(input.feature_set, ["feature_set_name", "version", "features"], `${label}.feature_set`);
    assert(Array.isArray(input.feature_set.features) && input.feature_set.features.length === 0, label, "features must be []");
    if (request.request_type === "MODEL_WRAPPER") {
      assert(input.feature_set.feature_set_name === "..." && input.feature_set.version === "...", label, "wrapper placeholders must be literal ...");
    }
  } else {
    exactKeys(input, [], label);
  }
}

function validateResponse(response, index, expectedCase = expectedCases[index]) {
  const label = index >= 0 ? `line ${index + 1}` : "negative fixture";
  exactKeys(response, ["protocol_version", "current_stage", "stages", "next_request"], label);
  assert(response.protocol_version === "1.0", label, "invalid protocol_version");
  assert(currentStages.includes(response.current_stage), label, "invalid current_stage");
  exactKeys(response.stages, ["syntax_intake", "base_discovery", "base_completion", "derived_completion", "final_validation", "verdict"], `${label}.stages`);
  validateSyntax(response.stages.syntax_intake, `${label}.syntax_intake`);
  exactKeys(response.stages.base_discovery, ["status", "message", "base_references", "issues"], `${label}.base_discovery`);
  validateCompletion(response.stages.base_completion, `${label}.base_completion`);
  validateCompletion(response.stages.derived_completion, `${label}.derived_completion`);
  exactKeys(response.stages.final_validation, ["status", "message", "mode", "target_feature", "reachable_features", "issues"], `${label}.final_validation`);
  exactKeys(response.stages.verdict, ["status", "value", "message", "boundary"], `${label}.verdict`);
  exactKeys(response.next_request, ["request_type", "question", "required_features", "required_fields", "expected_input_format"], `${label}.next_request`);

  for (const [stageName, stage] of Object.entries(response.stages)) {
    assert(stageStatuses.includes(stage.status), `${label}.${stageName}`, "invalid stage status");
  }

  uniqueStrings(response.stages.base_discovery.base_references, `${label}.base_references`);
  uniqueStrings(response.stages.final_validation.reachable_features, `${label}.reachable_features`);
  response.stages.syntax_intake.issues.forEach((issue, issueIndex) => validateIssue(issue, `${label}.syntax_issue[${issueIndex}]`));
  response.stages.base_discovery.issues.forEach((issue, issueIndex) => validateIssue(issue, `${label}.base_issue[${issueIndex}]`));
  response.stages.final_validation.issues.forEach((issue, issueIndex) => validateIssue(issue, `${label}.validation_issue[${issueIndex}]`));

  const request = response.next_request;
  assert(questions[request.request_type] === request.question, label, "request question is not the fixed text");
  uniqueStrings(request.required_features, `${label}.required_features`);
  canonicalFieldOrder(request.required_fields, `${label}.required_fields`);
  validateExpectedInput(request, `${label}.expected_input_format`);
  assert(response.current_stage === requestStages[request.request_type], label, `request ${request.request_type} is inconsistent with current_stage`);
  if (expectedCase) {
    assert(request.request_type === expectedCase[0], label, `expected request ${expectedCase[0]}`);
    assert(response.current_stage === expectedCase[1], label, `expected stage ${expectedCase[1]}`);
  }

  const validation = response.stages.final_validation;
  const verdict = response.stages.verdict;
  if (validation.mode === "NOT_RUN") {
    assert(validation.status === "SKIPPED" && validation.target_feature === null && validation.reachable_features.length === 0 && validation.issues.length === 0,
      label, "NOT_RUN validation state is inconsistent");
  } else {
    assert(["PASS", "FAIL", "INCOMPLETE"].includes(validation.status) && typeof validation.target_feature === "string", label, "run validation state is inconsistent");
  }
  if (response.current_stage === "VERDICT") {
    assert(validation.mode !== "NOT_RUN" && verdict.status === verdict.value && ["PASS", "FAIL", "INCOMPLETE"].includes(verdict.status), label, "final verdict state is inconsistent");
    assert(validation.status === verdict.status, label, "final validation status and verdict differ");
    assert(verdict.boundary === "REMOTE_VALIDATOR_RESULT" || verdict.boundary === "RULE_VALIDATION_ONLY", label, "missing verdict boundary");
    assert((validation.mode === "REMOTE" && verdict.boundary === "REMOTE_VALIDATOR_RESULT")
      || (validation.mode === "RULE_FALLBACK" && verdict.boundary === "RULE_VALIDATION_ONLY"),
      label, "validation mode and verdict boundary differ");
  } else {
    assert(verdict.status === "SKIPPED" && verdict.value === null && verdict.boundary === null, label, "early verdict must be skipped");
  }
  if (request.request_type === "EXPRESSION_INPUT") {
    assert(response.stages.syntax_intake.status === "PENDING" && response.stages.syntax_intake.original_input === null && response.stages.syntax_intake.normalized_input === null,
      label, "missing-expression state is inconsistent");
  }
  if (request.request_type === "SYNTAX_CORRECTION") assert(response.stages.syntax_intake.status === "FAIL", label, "syntax correction requires FAIL");
  if (request.request_type === "EXPRESSION_INPUT" || request.request_type === "SYNTAX_CORRECTION") {
    assert(response.stages.base_discovery.status === "SKIPPED"
      && response.stages.base_completion.status === "SKIPPED"
      && response.stages.derived_completion.status === "SKIPPED"
      && response.stages.final_validation.status === "SKIPPED"
      && response.stages.final_validation.mode === "NOT_RUN",
      label, "syntax intake stop must skip every later stage");
  }
  if (request.request_type === "MODEL_CORRECTION") assert(verdict.value === "FAIL", label, "model correction requires FAIL");
  if (request.request_type === "NONE") assert(verdict.value === "PASS", label, "NONE requires PASS");
}

assert(lines.length === expectedCases.length, "file", `expected ${expectedCases.length} raw outputs, got ${lines.length}`);
assert(lines.slice(1, 6).every((line) => line === lines[1]), "repetitions", "five Codex outputs are not byte-for-byte identical");
assert(lines[11] === lines[1], "cross-host repetition", "Claude Code output differs from the Codex canonical output");

for (const [index, line] of lines.entries()) {
  let response;
  try { response = JSON.parse(line); } catch (error) { fail(`line ${index + 1}`, `not standalone JSON: ${error.message}`); }
  validateResponse(response, index);
}

const correction = JSON.parse(lines[10]);
assert(correction.next_request.required_features.join(",") === "target_score", "MODEL_CORRECTION", "affected feature mismatch");
assert(correction.next_request.required_fields.join(",") === "seq_max_length", "MODEL_CORRECTION", "affected field mismatch");
assert(correction.next_request.expected_input_format.target_feature === "target_score", "MODEL_CORRECTION", "target identity not preserved");
assert(correction.next_request.expected_input_format.feature_set.feature_set_name === "reachability_case", "MODEL_CORRECTION", "feature-set identity not preserved");
assert(correction.next_request.expected_input_format.feature_set.version === "1", "MODEL_CORRECTION", "version identity not preserved");

function expectRejected(name, sourceLine, mutate) {
  const fixture = JSON.parse(sourceLine);
  mutate(fixture);
  let rejected = false;
  try { validateResponse(fixture, -1, null); } catch { rejected = true; }
  assert(rejected, `negative fixture ${name}`, "validator accepted an invalid state");
}

expectRejected("syntax-stop later stage", lines[6], (fixture) => {
  fixture.stages.base_discovery.status = "PASS";
});
expectRejected("validation-verdict mismatch", lines[10], (fixture) => {
  fixture.stages.final_validation.status = "PASS";
});
expectRejected("mode-boundary mismatch", lines[10], (fixture) => {
  fixture.stages.final_validation.mode = "REMOTE";
});
expectRejected("request-stage mismatch", lines[7], (fixture) => {
  fixture.current_stage = "DERIVED_COMPLETION";
});
expectRejected("unknown stage status", lines[1], (fixture) => {
  fixture.stages.base_completion.status = "DONE";
});
expectRejected("syntax payload mismatch", lines[1], (fixture) => {
  fixture.stages.syntax_intake.normalized_input = null;
});

console.log(`Validated ${lines.length} raw protocol outputs.`);
console.log("Five Codex repetitions and one Claude Code repetition are byte-for-byte identical.");
console.log("Canonical key order, state invariants, fixed questions, additions, and contextual correction identities are valid.");
console.log("Six negative state fixtures were rejected.");
