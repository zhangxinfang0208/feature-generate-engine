# Deterministic protocol RED evidence

The saved pre-protocol Skill outputs in `with-skill.md` expose the output-shape failure that protocol 1.0 addresses:

- `syntax-stop` returns free text and bullets without any stage envelope.
- `extract-and-do-not-guess` returns Markdown headings for Stages 1 through 4 plus prose outside JSON blocks.
- `reachable-only` returns only Stages 5 and 6.
- `unknown-operator-incomplete` returns only Stages 1 through 4.
- Questions are written as free prose and do not use one stable request object.

Therefore the same Skill did not give different agents or scenarios a mechanically identical outer structure. This is the observed RED baseline for `deterministic-conversation-protocol` in `evals.json`.

An additional fresh-context invocation containing only `请帮我配置一个 Feature DAG 衍生特征。` returned that sentence verbatim. It produced no JSON envelope and no request for an expression. This is the RED baseline for `missing-expression-entry`.
