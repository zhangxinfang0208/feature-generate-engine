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

Its result verdict is one of:

- `PASS`: the target and every recursively reachable dependency satisfy the validator's applicable rules.
- `FAIL`: a syntax, declaration, reachability, cycle, or contract violation is established.
- `INCOMPLETE`: a required fact or semantic contract is unavailable, so no pass/fail claim is justified.

Return each issue as an object with `stage` and `message`, plus `feature`, `field`, and `offset` only when applicable:

```json
{
  "verdict": "INCOMPLETE",
  "issues": [
    {
      "stage": "DERIVED completion",
      "feature": "derived_feature_name",
      "field": "type",
      "message": "Operator semantic contract is unavailable."
    }
  ]
}
```

If no compatible validator is available, or it fails to run, say which fallback occurred and run the rule-validation workflow instead. Label the result as rule validation; never relabel fallback success as remote-validator success. Scope either route to the named target and recursively reachable dependencies only.
