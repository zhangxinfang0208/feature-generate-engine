# Expression Grammar

Use this grammar before any metadata work. Parse the complete input; a syntax error stops the workflow.

## Lexical rules

- An identifier normally starts with `Character.isLetter(ch)` or `_`, and continues with `Character.isLetterOrDigit(ch)`, `_`, or `.`. It is case-sensitive; these Java `Character` methods accept Unicode letters and digits, not only ASCII. A digit run immediately followed by `(` is the parser's call-identifier exception: it is tokenized as an identifier for that call.
- Before tokenizing, preserve the submitted expression verbatim as `original_input`. Normalize each `\_` outside a quoted string to `_` only in a separate `normalized_input`, record `已将字符串外的 \_ 按 _ 规范化。` in `message` or `audit`, and use that copy for parsing. If no eligible `\_` occurs, `original_input` and `normalized_input` must be byte-for-byte identical. Never append a missing token or repair the expression. `<EOF>` is an audit boundary only and must never be included in either expression.
- Strings use either `'` or `"`. Their only escapes are `\n`, `\r`, `\t`, `\\`, `\"`, and `\'`; an unrecognized escape is invalid.
- A number is `-?[0-9]+` (integer, including leading-zero forms such as `01`) or `-?[0-9]+\.[0-9]*` (decimal, including a trailing decimal point). A signed 32-bit integer is `INT`; a remaining signed 64-bit integer is `BIGINT`; a decimal is `DOUBLE`. A non-decimal integer outside the signed 64-bit range is invalid.
- `true`, `false`, and `null` are boolean and null literals. They are not feature references.

## Syntax

```text
expression  := call | identifier | literal | array | object
call        := identifier invocation-list+
invocation-list := "(" arguments? ")"
arguments   := positional-list ("," named-list)? | named-list
positional-list := positional ("," positional)*
named-list  := named ("," named)*
positional  := expression
named       := identifier "=" expression
array       := "[" (expression ("," expression)*)? "]"
object      := "{" (object-key ":" expression ("," object-key ":" expression)*)? "}"
object-key  := identifier | string
literal     := string | number | "true" | "false" | "null"
```

Nested calls, arrays, and objects are allowed to a maximum nesting depth of 200. A call may have successive invocation lists, such as `operator(first)(second)` or `operator()()`: the parser records one call with its invocation count and aggregates arguments in source order. Positional arguments after a named argument (including in a later invocation list), duplicate object keys, trailing commas, missing delimiters, unmatched quotes/brackets, and any trailing token after one expression are invalid. `\_` inside a string is not normalized and is invalid because it is not a permitted string escape.

## AST reference extraction

After a complete successful parse, walk the expression AST depth-first from left to right. Add a bare identifier expression once, at its first encounter. Do not add a call's operator identifier, a named-argument label, an object key, or a string/literal. Continue into every call argument, array element, and object value.

For the approved long expression, the extracted BASE references are exactly:

```text
auid_hwdsp_clk_crtv_clstid_seq_time_365d
auid_hwdsp_clk_norm_tag1id_seq_time_365d
normalized_tag1_id_h
auid_hwdsp_clk_slotid_seq_time_365d
auid_hwdsp_clk_ts_seq_time_365d
timestamp_s
```
