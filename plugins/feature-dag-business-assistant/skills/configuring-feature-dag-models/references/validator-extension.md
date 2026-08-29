# Optional Remote Validator

When a compatible available validator accepts this request envelope, use it first:

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

Do not construct or send this envelope until the business has supplied a named target and a complete `{feature_set_name, version, features}` wrapper. A partial feature list belongs to BASE/DERIVED completion and must not receive a fabricated validator or fallback verdict.

Its result verdict is one of:

- `PASS`: the target and every recursively reachable dependency satisfy the validator's applicable rules.
- `FAIL`: a syntax, declaration, reachability, cycle, or contract violation is established.
- `INCOMPLETE`: a required fact or semantic contract is unavailable, so no pass/fail claim is justified.

Place the result inside the deterministic conversation protocol. Return each issue with all five protocol keys; use JSON `null` when feature, field, or offset does not apply:

```json
{
  "code": "OPERATOR_CONTRACT_UNAVAILABLE",
  "feature_name": "derived_feature_name",
  "field": "type",
  "offset": null,
  "message": "Operator semantic contract is unavailable."
}
```

If no compatible validator is available, or it fails to run, record that fact in `final_validation.message`, then run the rule-validation workflow with `mode=RULE_FALLBACK` and `boundary=RULE_VALIDATION_ONLY`. Never relabel fallback success as a remote-validator result. Scope either route to the named target and recursively reachable dependencies only.
