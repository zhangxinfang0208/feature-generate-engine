import { readFileSync } from "node:fs";

const [inputFile, scenario] = process.argv.slice(2);
if (!inputFile || !scenario) {
  throw new Error("Usage: node validate-business-dialogue.mjs <output-file> <scenario>");
}

const output = readFileSync(inputFile, "utf8").trim();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function includes(text) {
  assert(output.includes(text), `Missing required text: ${text}`);
}

function jsonBlocks() {
  return [...output.matchAll(/```json\s*([\s\S]*?)```/g)].map((match, index) => {
    try { return JSON.parse(match[1]); }
    catch (error) { throw new Error(`JSON block ${index + 1} is invalid: ${error.message}`); }
  });
}

function validateAdditionArrays() {
  const blocks = jsonBlocks();
  assert(blocks.length > 0, "Expected at least one frontend addition JSON block.");
  for (const [blockIndex, block] of blocks.entries()) {
    assert(Array.isArray(block), `Addition block ${blockIndex + 1} must be an array.`);
    for (const [propertyIndex, property] of block.entries()) {
      const label = `Addition block ${blockIndex + 1} property ${propertyIndex + 1}`;
      const keys = Object.keys(property);
      const expected = ["raw_name", "data_value", "data_type", "default_value", "required"];
      assert(keys.length === expected.length && keys.every((key, index) => key === expected[index]),
        `${label} must use the exact five-key order.`);
      assert(["NUMBER", "LIST", "STRING", "BOOLEAN"].includes(property.data_type), `${label} has unsupported data_type.`);
      assert(property.default_value === "" && property.required === "true", `${label} has invalid fixed values.`);
      if (property.data_type === "NUMBER") assert(typeof property.data_value === "number", `${label} NUMBER must be unquoted.`);
      if (property.data_type === "LIST") {
        assert(typeof property.data_value === "string", `${label} LIST must be a serialized string.`);
        let parsed;
        try { parsed = JSON.parse(property.data_value); } catch { throw new Error(`${label} LIST is not serialized JSON.`); }
        assert(Array.isArray(parsed), `${label} LIST must decode to an array.`);
      }
    }
  }
  return blocks;
}

function requireAddition(properties, rawName, dataType, dataValue) {
  assert(properties.some((property) => property.raw_name === rawName
    && property.data_type === dataType && property.data_value === dataValue),
  `Expected ${rawName}=${JSON.stringify(dataValue)} as ${dataType}.`);
}

assert(output.length > 0, "Output is empty.");
assert(!output.startsWith("{"), "Business output must be concise text, not a protocol JSON envelope.");
assert(!output.startsWith("```"), "The whole business response must not be wrapped in a code fence.");
for (const field of ["protocol_version", "current_stage", "next_request", "stages", "boundary"]) {
  assert(!output.includes(field), `Internal protocol field ${field} must not be shown to the business.`);
}

if (scenario === "MISSING_EXPRESSION") {
  includes("请提供需要校验的特征表达式。");
  includes("回复样例：");
  includes("表达式：zip_concat(...)");
  assert(!output.includes("BASE"), "Expression intake must ask only for the expression.");
} else if (scenario === "BASE_CONFIG_REQUEST") {
  includes("表达式语法校验通过。");
  includes("发现以下 BASE 特征：");
  includes("- user_score");
  includes("请从前台复制以上 BASE 特征的当前配置。");
  includes("请保留前台已有字段，不要先手工补充或改写。");
  includes("回复样例：");
  includes('"features"');
  assert(!/^type\s*:/m.test(output), "Must not request type before checking current BASE configuration.");
  assert(!/^entity(?:_scopes)?\s*:/m.test(output), "Must not request entity before checking current BASE configuration.");
  assert(!/^seq_max_length\s*:/m.test(output), "Must not request seq_max_length before checking current BASE configuration.");
} else if (scenario === "BASE_FACT_REQUEST") {
  includes("BASE 特征信息校验完成，以下信息需要业务补充：");
  includes("特征 user_seq：");
  includes("- type");
  includes("- value_shape");
  includes("- entity_scopes");
  includes("请按下面的样例一次性回复：");
  includes("特征 user_seq：");
  includes("type: STRING");
  includes("value_shape: SEQUENCE");
  includes("entity: USER");
  assert(!output.includes("unrelated_broken"), "Unrelated full-model entries must be ignored.");
  assert(!output.includes("特征 other_seq："), "Complete referenced BASE entries must not be requested again.");
  assert(!output.includes("protocol_version"), "Fact request must remain business-facing text.");
} else if (scenario === "ASSIGNMENT_BASE_CONFIG") {
  includes("表达式语法校验通过。");
  includes("衍生特征：ecpm_ctr_ratio_with_floor");
  includes("- slot_avg_click_bid_ecpm_3d");
  includes("- auid_slot_ctr_90d");
  includes("请从前台复制以上 BASE 特征的当前配置。");
  assert(output.indexOf("slot_avg_click_bid_ecpm_3d") < output.indexOf("auid_slot_ctr_90d"),
    "BASE feature order must follow first appearance in the expression.");
} else if (scenario === "SYNTAX_ERROR") {
  includes("表达式语法校验失败。");
  includes("缺少右括号");
  includes("位置：输入末尾");
  assert(!output.includes("发现以下 BASE 特征"), "Syntax failure must stop before BASE discovery output.");
} else if (scenario === "REACHABLE_FAIL") {
  includes("最终校验：FAIL");
  includes("目标特征：target_score");
  includes("校验边界：规则校验结果（未调用远程校验器）");
  assert(!output.includes("unrelated_broken"), "Unreachable malformed features must be ignored.");
  const blocks = validateAdditionArrays();
  const properties = blocks.flat();
  assert(properties.some((property) => property.raw_name === "seq_max_length"
    && property.data_type === "NUMBER" && property.data_value === 1),
    "Reachable add result must include numeric seq_max_length=1.");
} else if (scenario === "SEQUENCE_ADD") {
  includes("最终校验：FAIL");
  includes("目标特征：target_seq");
  const properties = validateAdditionArrays().flat();
  requireAddition(properties, "type", "STRING", "INT");
  requireAddition(properties, "value_shape", "STRING", "SEQUENCE");
  requireAddition(properties, "entity_scopes", "LIST", '["USER"]');
  requireAddition(properties, "seq_max_length", "NUMBER", 3);
  assert(!properties.some((property) => property.raw_name === "seq_max_length"
    && property.data_value === 1), "Sequence add must not reuse scalar length 1.");
} else if (scenario === "APPEND_CONTRACT") {
  includes("最终校验：FAIL");
  includes("目标特征：appended");
  assert(!output.includes("缺少可用语义契约"), "append must have an available contract.");
  const properties = validateAdditionArrays().flat();
  requireAddition(properties, "type", "STRING", "STRING");
  requireAddition(properties, "value_shape", "STRING", "SEQUENCE");
  requireAddition(properties, "entity_scopes", "LIST", '["USER","SCENE"]');
  requireAddition(properties, "seq_max_length", "NUMBER", 3);
} else if (scenario === "JOIN_CONTRACT") {
  includes("最终校验：FAIL");
  includes("目标特征：joined");
  assert(!output.includes("缺少可用语义契约"), "join must have an available contract.");
  const properties = validateAdditionArrays().flat();
  requireAddition(properties, "type", "STRING", "STRING");
  requireAddition(properties, "value_shape", "STRING", "SCALAR");
  requireAddition(properties, "entity_scopes", "LIST", '["USER"]');
  requireAddition(properties, "seq_max_length", "NUMBER", 1);
} else if (scenario === "SEQUENCE_DISCRETE_LOG") {
  includes("最终校验：FAIL");
  includes("目标特征：transformed");
  const properties = validateAdditionArrays().flat();
  requireAddition(properties, "type", "STRING", "DOUBLE");
  requireAddition(properties, "value_shape", "STRING", "SEQUENCE");
  requireAddition(properties, "entity_scopes", "LIST", '["USER","SCENE"]');
  requireAddition(properties, "seq_max_length", "NUMBER", 5);
} else if (scenario === "UNKNOWN_OPERATOR") {
  includes("配置补充项已生成，请按特征复制到前台。");
  includes("DERIVED 特征 future_output：");
  includes("算子 future_transform 缺少可用语义契约");
  includes("特征 future_output：");
  for (const field of ["type", "value_shape", "entity_scopes", "seq_max_length"]) includes(`- ${field}`);
  assert(!output.includes("最终校验："), "Unknown operator facts must not receive an early final verdict.");
  const blocks = validateAdditionArrays();
  const properties = blocks.flat();
  assert(properties.some((property) => property.raw_name === "definition_type"
    && property.data_type === "STRING" && property.data_value === "DERIVED"),
    "Unknown operator must still include deterministic definition_type=DERIVED.");
  assert(properties.some((property) => property.raw_name === "expression"
    && property.data_type === "STRING" && property.data_value === "future_transform(base_a)"),
    "Unknown operator must still include the submitted expression.");
  assert(properties.some((property) => property.raw_name === "output_policy"
    && property.data_type === "STRING" && property.data_value === "OUTPUT"),
    "Unknown operator must still include deterministic output_policy=OUTPUT.");
  assert(properties.some((property) => property.raw_name === "to_use"
    && property.data_type === "BOOLEAN" && property.data_value === "true"),
    "Unknown operator must still include deterministic to_use=true.");
  assert(!properties.some((property) => ["type", "value_shape", "entity_scopes", "seq_max_length"].includes(property.raw_name)),
    "Unknown operator must not invent unresolved semantic fields.");
} else if (scenario === "MISSING_BASE_FILTER") {
  const expected = [
    "auid_hwdsp_clk_crtv_clstid_seq_time_365d",
    "auid_hwdsp_clk_slotid_seq_time_365d",
    "timestamp_s"
  ];
  for (const feature of expected) includes(`- ${feature}`);
  assert(expected.every((feature, index) => index === 0 || output.indexOf(feature) > output.indexOf(expected[index - 1])),
    "Missing BASE features must retain AST first-seen order.");
  assert(!output.includes("- auid_hwdsp_clk_norm_tag1id_seq_time_365d"), "Already supplied BASE must not be requested.");
  assert(!output.includes("- normalized_tag1_id_h"), "Already supplied BASE must not be requested.");
  assert(!output.includes("- auid_hwdsp_clk_ts_seq_time_365d"), "Already supplied BASE must not be requested.");
} else if (scenario === "ADDITIONS_FORMAT") {
  const blocks = validateAdditionArrays();
  const properties = blocks.flat();
  assert(properties.some((property) => property.raw_name === "entity_scopes" && property.data_type === "LIST"
    && property.data_value === '["USER"]'), "Expected serialized entity_scopes LIST.");
  assert(properties.some((property) => property.raw_name === "seq_max_length" && property.data_type === "NUMBER"
    && property.data_value === 365), "Expected unquoted numeric seq_max_length.");
} else if (scenario === "CROSS_HOST") {
  includes("表达式语法校验通过。");
  includes("发现以下 BASE 特征：");
  includes("请从前台复制以上 BASE 特征的当前配置。");
  includes("回复样例：");
  assert(!/^type\s*:/m.test(output), "Cross-host template must not request facts before current configuration.");
  assert(!output.includes("校验器"), "Cross-host template must not expose validator implementation details.");
} else {
  throw new Error(`Unknown scenario: ${scenario}`);
}

console.log(`${scenario} dialogue is valid.`);
