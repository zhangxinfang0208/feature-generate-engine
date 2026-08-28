#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""特征表达式静态校验脚本。

词法与语法镜像引擎 ExpressionParser（src/main/java/com/example/featuredag/expression/ExpressionParser.java），
并附带算子名、参数个数、命名参数、配置对象键的静态检查，以及 base 特征（裸标识符引用）提取。

用法:
  python check_expression.py "zip_concat(a, b, {\"delimiter\":\"#\"})"
  python check_expression.py --file expression.txt

输出: stdout 单个 JSON 对象；退出码 0 = 通过，1 = 存在错误。
"""

import difflib
import json
import sys

INF = float("inf")
MAX_NESTING_DEPTH = 200
EXCERPT_RADIUS = 40

# name -> (min_args, max_args)
OPERATORS = {
    "discrete": (2, 2),
    "log_base": (3, 3),
    "slice_by_indices": (2, 2),
    "find_indices": (2, 2),
    "find_indices_any": (2, 2),
    "get_seq_length": (1, 1),
    "count_distinct": (1, 1),
    "zip_concat": (2, INF),
    "concat": (2, INF),
    "list_concat": (2, 3),
    "hit": (2, 2),
    "group_count_concat": (1, 2),
    "calc_delta_seq": (2, 3),
    "to_int": (1, 1),
    "to_bigint": (1, 1),
    "min": (2, INF),
    "max": (2, INF),
    "add": (2, 2),
    "sub": (2, 2),
    "mul": (2, 2),
    "div": (2, 2),
}

# 支持命名参数的算子及其形参名（与源码 parameterNames 一致）
NAMED_PARAMS = {
    "add": ["value", "addend"],
    "sub": ["value", "margin"],
    "mul": ["value", "multiplier"],
    "div": ["value", "divisor"],
    "hit": ["seq_kv", "seq_key"],
    "find_indices_any": ["sequence", "targets"],
    "group_count_concat": ["sequence", "config"],
}

# 配置对象（对象字面量参数）的合法位置与合法键
# "last"  表示只能作为最后一个参数（zip_concat/concat 宽松消费）
# 集合值  表示只能出现在这些下标（从 0 计）
CONFIG_RULES = {
    "zip_concat": ("last", {"delimiter"}),
    "concat": ("last", {"delimiter"}),
    "list_concat": ({2}, {"delimiter"}),
    "calc_delta_seq": ({2}, {"direction", "divisor", "need_ceil"}),
    "group_count_concat": ({1}, {"delimiter", "order"}),
}
# 引擎对未知配置键严格报错的算子（其余算子仅忽略未知键）
STRICT_OPTION_KEYS = {"calc_delta_seq"}

FULLWIDTH_HINTS = {
    "，": ",", "（": "(", "）": ")", "：": ":", "＝": "=",
    "【": "[", "】": "]", "｛": "{", "｝": "}", "、": ",",
    "“": '"', "”": '"', "‘": "'", "’": "'",
}

DIGITS = "0123456789"

LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, COLON, EQUAL, IDENTIFIER, NUMBER, STRING, EOF = range(13)
TOKEN_LABELS = {
    LPAREN: "'('", RPAREN: "')'", LBRACE: "'{'", RBRACE: "'}'",
    LBRACKET: "'['", RBRACKET: "']'", COMMA: "','", COLON: "':'",
    EQUAL: "'='", IDENTIFIER: "标识符", NUMBER: "数字", STRING: "字符串", EOF: "结尾",
}

ESCAPES = {"n": "\n", "r": "\r", "t": "\t", "\\": "\\", '"': '"', "'": "'"}


class CheckError(Exception):
    def __init__(self, message, offset):
        super().__init__(message)
        self.message = message
        self.offset = offset


class Token(object):
    def __init__(self, token_type, text, start, end):
        self.type = token_type
        self.text = text
        self.start = start
        self.end = end


def is_identifier_start(ch):
    return ch.isalpha() or ch == "_"


def is_identifier_part(ch):
    return ch.isalnum() or ch == "_" or ch == "."


def excerpt(source, position):
    start = max(0, position - EXCERPT_RADIUS)
    end = min(len(source), position + EXCERPT_RADIUS)
    prefix = "" if start == 0 else "..."
    suffix = "" if end == len(source) else "..."
    return prefix + source[start:end] + suffix


def digits_followed_by_lparen(source, index):
    end = index
    while end < len(source) and source[end] in DIGITS:
        end += 1
    return end < len(source) and source[end] == "("


def lex(source):
    tokens = []
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        if ch.isspace():
            i += 1
            continue
        if ch in "()[]{}:,=":
            mapping = {"(": LPAREN, ")": RPAREN, "[": LBRACKET, "]": RBRACKET,
                       "{": LBRACE, "}": RBRACE, ",": COMMA, ":": COLON, "=": EQUAL}
            tokens.append(Token(mapping[ch], ch, i, i + 1))
            i += 1
            continue
        if ch in "\"'":
            token, i = lex_string(source, i)
            tokens.append(token)
            continue
        if ch in DIGITS and digits_followed_by_lparen(source, i):
            token, i = lex_identifier(source, i)
            tokens.append(token)
            continue
        if ch in DIGITS or (ch == "-" and i + 1 < n and source[i + 1] in DIGITS):
            token, i = lex_number(source, i)
            tokens.append(token)
            continue
        if is_identifier_start(ch):
            token, i = lex_identifier(source, i)
            tokens.append(token)
            continue
        if ch in FULLWIDTH_HINTS:
            raise CheckError(
                "使用了全角字符 %r（通常是中文输入法所致），请改用半角 %r"
                % (ch, FULLWIDTH_HINTS[ch]), i)
        raise CheckError("无法识别的字符 %r" % ch, i)
    tokens.append(Token(EOF, "", n, n))
    return tokens


def lex_identifier(source, start):
    i = start + 1
    while i < len(source) and is_identifier_part(source[i]):
        i += 1
    return Token(IDENTIFIER, source[start:i], start, i), i


def lex_number(source, start):
    i = start
    n = len(source)
    if source[i] == "-":
        i += 1
    dot_seen = False
    while i < n:
        ch = source[i]
        if ch in DIGITS:
            i += 1
        elif ch == "." and not dot_seen:
            dot_seen = True
            i += 1
        else:
            break
    return Token(NUMBER, source[start:i], start, i), i


def lex_string(source, start):
    quote = source[start]
    i = start + 1
    n = len(source)
    while i < n:
        ch = source[i]
        i += 1
        if ch == quote:
            return Token(STRING, source[start + 1:i - 1], start, i), i
        if ch == "\\":
            if i >= n:
                raise CheckError("转义符 '\\' 后没有字符（字符串未闭合）", i)
            escaped = source[i]
            i += 1
            if escaped not in ESCAPES:
                raise CheckError("不支持的转义序列 '\\%s'（仅支持 \\n \\r \\t \\ \\\" \\'）" % escaped, i - 1)
    raise CheckError("字符串字面量未闭合（缺少成对的 %r）" % quote, start)


class Parser(object):
    """节点结构:
    ("ref", name, offset) / ("lit", value, offset) / ("array", [nodes], offset)
    ("object", {key: node}, offset)
    ("call", name, [arg_nodes], {index: param_name}, invocation_count, offset)
    """

    def __init__(self, source, tokens):
        self.source = source
        self.tokens = tokens
        self.pos = 0
        self.depth = 0
        self.current = tokens[0]

    def advance(self):
        token = self.current
        self.pos += 1
        if self.pos < len(self.tokens):
            self.current = self.tokens[self.pos]
        else:
            self.current = self.tokens[-1]
        return token

    def consume(self, expected):
        if self.current.type != expected:
            raise CheckError(
                "期望 %s 但遇到 %s" % (TOKEN_LABELS[expected], TOKEN_LABELS[self.current.type]),
                self.current.start)
        return self.advance()

    def parse_expression(self):
        if self.depth >= MAX_NESTING_DEPTH:
            raise CheckError("表达式嵌套超过上限 %d 层" % MAX_NESTING_DEPTH, self.current.start)
        self.depth += 1
        try:
            token_type = self.current.type
            if token_type == IDENTIFIER:
                return self.parse_identifier_or_call()
            if token_type == NUMBER:
                token = self.consume(NUMBER)
                return ("lit", parse_number_value(token.text), token.start)
            if token_type == STRING:
                token = self.consume(STRING)
                return ("lit", token.text, token.start)
            if token_type == LBRACE:
                return self.parse_object()
            if token_type == LBRACKET:
                return self.parse_array()
            raise CheckError(
                "期望表达式但遇到 %s" % TOKEN_LABELS[token_type], self.current.start)
        finally:
            self.depth -= 1

    def parse_identifier_or_call(self):
        identifier = self.consume(IDENTIFIER)
        arguments = []
        argument_names = {}
        has_call = False
        named_seen = False
        invocation_count = 0
        while self.current.type == LPAREN:
            has_call = True
            invocation_count += 1
            self.consume(LPAREN)
            if self.current.type != RPAREN:
                while True:
                    argument = self.parse_expression()
                    if self.current.type == EQUAL:
                        if argument[0] != "ref":
                            raise CheckError("命名参数必须以标识符开头", self.current.start)
                        self.consume(EQUAL)
                        argument_names[len(arguments)] = argument[1]
                        argument = self.parse_expression()
                        named_seen = True
                    elif named_seen:
                        raise CheckError("位置参数不能出现在命名参数之后", self.current.start)
                    arguments.append(argument)
                    if self.current.type != COMMA:
                        break
                    self.consume(COMMA)
            self.consume(RPAREN)
        if has_call:
            return ("call", identifier.text, arguments, argument_names, invocation_count, identifier.start)
        if identifier.text == "true":
            return ("lit", True, identifier.start)
        if identifier.text == "false":
            return ("lit", False, identifier.start)
        if identifier.text == "null":
            return ("lit", None, identifier.start)
        return ("ref", identifier.text, identifier.start)

    def parse_object(self):
        start = self.consume(LBRACE)
        fields = {}
        if self.current.type != RBRACE:
            while True:
                if self.current.type == STRING:
                    key = self.advance().text
                elif self.current.type == IDENTIFIER:
                    key = self.advance().text
                else:
                    raise CheckError("期望对象键（字符串或标识符）但遇到 %s"
                                     % TOKEN_LABELS[self.current.type], self.current.start)
                self.consume(COLON)
                value = self.parse_expression()
                if key in fields:
                    raise CheckError("对象键 '%s' 重复" % key, self.current.start)
                fields[key] = value
                if self.current.type != COMMA:
                    break
                self.consume(COMMA)
        self.consume(RBRACE)
        return ("object", fields, start.start)

    def parse_array(self):
        start = self.consume(LBRACKET)
        elements = []
        if self.current.type != RBRACKET:
            while True:
                elements.append(self.parse_expression())
                if self.current.type != COMMA:
                    break
                self.consume(COMMA)
        self.consume(RBRACKET)
        return ("array", elements, start.start)


def parse_number_value(text):
    if "." in text:
        return float(text)
    value = int(text)
    return value


def parse(source):
    tokens = lex(source)
    parser = Parser(source, tokens)
    node = parser.parse_expression()
    if parser.current.type != EOF:
        raise CheckError("表达式结束后还有多余内容（遇到 %s）"
                         % TOKEN_LABELS[parser.current.type], parser.current.start)
    return node


def suggest(name):
    matches = difflib.get_close_matches(name, sorted(OPERATORS), n=1, cutoff=0.6)
    if matches:
        return "，是否想写 '%s'？" % matches[0]
    return ""


def literal_value(node):
    if node[0] == "lit":
        return node[1]
    return None


def check_options(op, config_node, errors, warnings):
    _, fields, offset = config_node
    if op not in CONFIG_RULES:
        return
    _, allowed_keys = CONFIG_RULES[op]
    for key, value_node in fields.items():
        if key not in allowed_keys:
            message = "配置键 '%s' 不被算子 %s 使用（合法键: %s）" % (key, op, ", ".join(sorted(allowed_keys)))
            if op in STRICT_OPTION_KEYS:
                errors.append({"message": message, "offset": offset})
            else:
                warnings.append({"message": message + "，引擎会忽略它", "offset": offset})
            continue
        value = literal_value(value_node)
        if op == "calc_delta_seq":
            if key == "direction":
                if value_node[0] != "lit":
                    warnings.append({"message": "direction 的值不是字面量，无法静态验证", "offset": offset})
                elif value not in ("ELEMENT_MINUS_BASE", "BASE_MINUS_ELEMENT"):
                    errors.append({"message": "direction 只能是 ELEMENT_MINUS_BASE 或 BASE_MINUS_ELEMENT，实际为 %r" % value, "offset": offset})
            elif key == "divisor":
                if value_node[0] != "lit":
                    warnings.append({"message": "divisor 的值不是字面量，无法静态验证", "offset": offset})
                elif not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
                    errors.append({"message": "divisor 必须是大于 0 的数字，实际为 %r" % value, "offset": offset})
            elif key == "need_ceil":
                if value_node[0] != "lit":
                    warnings.append({"message": "need_ceil 的值不是字面量，无法静态验证", "offset": offset})
                elif value not in (0, 1):
                    errors.append({"message": "need_ceil 只能是 0 或 1，实际为 %r" % value, "offset": offset})
        elif key == "order" and op == "group_count_concat":
            if value_node[0] != "lit":
                warnings.append({"message": "order 的值不是字面量，无法静态验证", "offset": offset})
            elif value not in ("FIRST_OCCURRENCE", "COUNT_DESC"):
                errors.append({"message": "order 只能是 FIRST_OCCURRENCE 或 COUNT_DESC，实际为 %r" % value, "offset": offset})
        elif key == "delimiter":
            if value_node[0] == "lit" and not isinstance(value, str):
                errors.append({"message": "delimiter 必须是字符串，实际为 %r" % value, "offset": offset})
            elif value_node[0] != "lit":
                warnings.append({"message": "delimiter 的值不是字面量，无法静态验证", "offset": offset})


def check_call(node, errors, warnings, calls, depth_info):
    _, name, arguments, argument_names, invocation_count, offset = node
    for argument in arguments:
        walk(argument, errors, warnings, calls, depth_info)
    calls.append({
        "name": name,
        "argc": len(arguments),
        "named_args": dict(argument_names),
        "offset": offset,
    })
    if name not in OPERATORS:
        errors.append({
            "message": "未知算子 '%s'%s（标准算子共 21 个，见 operator-signatures.md）" % (name, suggest(name)),
            "offset": offset,
        })
        return
    min_args, max_args = OPERATORS[name]
    if invocation_count > 1:
        errors.append({
            "message": "链式调用 %s(...)(...) 不被引擎支持，请合并为一层参数" % name,
            "offset": offset,
        })
    if not (min_args <= len(arguments) <= max_args):
        expected = str(min_args) if max_args == min_args else (
            "%d..%d" % (min_args, max_args) if max_args != INF else "至少 %d" % min_args)
        errors.append({
            "message": "算子 %s 期望 %s 个参数，实际 %d 个" % (name, expected, len(arguments)),
            "offset": offset,
        })
    if argument_names:
        if name not in NAMED_PARAMS:
            errors.append({
                "message": "算子 %s 不支持命名参数（仅 %s 支持）"
                           % (name, ", ".join(sorted(NAMED_PARAMS))),
                "offset": offset,
            })
        else:
            legal = NAMED_PARAMS[name]
            seen = set()
            for index, param in sorted(argument_names.items()):
                if param not in legal:
                    errors.append({
                        "message": "算子 %s 没有参数名 '%s'（可用: %s）" % (name, param, ", ".join(legal)),
                        "offset": offset,
                    })
                elif param in seen:
                    errors.append({"message": "命名参数 '%s' 重复" % param, "offset": offset})
                seen.add(param)
    value_argc = len(arguments)
    config_positions = set()
    if name in CONFIG_RULES:
        position_spec, _ = CONFIG_RULES[name]
        config_positions = {"last"} if position_spec == "last" else set(position_spec)
        if position_spec == "last":
            last_index = len(arguments) - 1
            if arguments and arguments[-1][0] == "object":
                value_argc -= 1
                check_options(name, arguments[-1], errors, warnings)
    for index, argument in enumerate(arguments):
        if argument[0] != "object":
            continue
        if name in CONFIG_RULES:
            position_spec, _ = CONFIG_RULES[name]
            is_config_position = (position_spec == "last" and index == len(arguments) - 1) or (
                position_spec != "last" and index in position_spec)
            if is_config_position:
                if position_spec != "last":
                    check_options(name, argument, errors, warnings)
                continue
            errors.append({
                "message": "对象字面量只能作为算子 %s 的%s参数，当前位置不合法"
                           % (name, "最后一个" if position_spec == "last" else "第 %d 个（从 0 计）" % list(position_spec)[0]),
                "offset": argument[2],
            })
        else:
            errors.append({
                "message": "算子 %s 不接受对象字面量参数" % name,
                "offset": argument[2],
            })
    if name in ("zip_concat", "concat") and value_argc < min_args:
        errors.append({
            "message": "算子 %s 去掉末尾配置对象后只剩 %d 个值参数，不满足最少 %d 个" % (name, value_argc, min_args),
            "offset": offset,
        })
    if name == "discrete" and len(arguments) >= 2:
        boundaries = arguments[1]
        if boundaries[0] == "array":
            values = [literal_value(element) for element in boundaries[1]]
            numeric = [v for v in values if isinstance(v, (int, float)) and not isinstance(v, bool)]
            if len(numeric) != len(values):
                warnings.append({"message": "discrete 边界数组包含非数字字面量，无法静态验证递增", "offset": offset})
            elif any(numeric[i] >= numeric[i + 1] for i in range(len(numeric) - 1)):
                errors.append({
                    "message": "discrete 的边界数组必须严格递增，实际为 %s" % numeric,
                    "offset": offset,
                })
        else:
            warnings.append({"message": "discrete 第二个参数不是数组字面量，无法静态验证边界递增", "offset": offset})


def walk(node, errors, warnings, calls, depth_info):
    kind = node[0]
    depth_info[0] = max(depth_info[0], len(calls))
    if kind == "call":
        check_call(node, errors, warnings, calls, depth_info)
    elif kind == "array":
        for element in node[1]:
            walk(element, errors, warnings, calls, depth_info)
    elif kind == "object":
        for value in node[1].values():
            walk(value, errors, warnings, calls, depth_info)


def collect_features(node, counter):
    kind = node[0]
    if kind == "ref":
        counter[node[1]] = counter.get(node[1], 0) + 1
    elif kind == "call":
        for argument in node[2]:
            collect_features(argument, counter)
    elif kind == "array":
        for element in node[1]:
            collect_features(element, counter)
    elif kind == "object":
        for value in node[1].values():
            collect_features(value, counter)


def compute_depth(node):
    kind = node[0]
    if kind == "call":
        return 1 + max([compute_depth(a) for a in node[2]] or [0])
    if kind == "array":
        return 1 + max([compute_depth(e) for e in node[1]] or [0])
    if kind == "object":
        return 1 + max([compute_depth(v) for v in node[1].values()] or [0])
    return 0


def analyze(source):
    result_errors = []
    result_warnings = []
    calls = []
    try:
        node = parse(source)
    except CheckError as exc:
        result_errors.append({"message": exc.message, "offset": exc.offset})
        stripped = source.strip()
        if len(stripped) >= 2 and stripped[0] == stripped[-1] and stripped[0] in "\"'":
            try:
                parse(stripped[1:-1])
                result_errors.append({
                    "message": "提示：表达式整体被引号包裹，去掉首尾的 %r 后即可通过校验" % stripped[0],
                    "offset": 0,
                })
            except CheckError:
                pass
        return build_result(source, result_errors, result_warnings, calls, {}, 0)
    depth_info = [0]
    walk(node, result_errors, result_warnings, calls, depth_info)
    if node[0] != "call":
        result_warnings.append({
            "message": "表达式顶层不是算子调用（是 %s），请确认是否漏贴了外层算子" % (
                "字面量" if node[0] == "lit" else ("数组/对象字面量" if node[0] in ("array", "object") else "裸特征引用")),
            "offset": 0,
        })
    counter = {}
    collect_features(node, counter)
    features = [{"name": name, "ref_count": count} for name, count in counter.items()]
    return build_result(source, result_errors, result_warnings, calls, features, compute_depth(node))


def build_result(source, errors, warnings, calls, features, depth):
    errors = sorted(errors, key=lambda item: item.get("offset", 0))
    for item in errors:
        item["near"] = excerpt(source, item.get("offset", 0))
    for item in warnings:
        item["near"] = excerpt(source, item.get("offset", 0))
    return {
        "ok": not errors,
        "error_count": len(errors),
        "warning_count": len(warnings),
        "errors": errors,
        "warnings": warnings,
        "operators": [
            {"name": c["name"], "argc": c["argc"], "named_args": c["named_args"]} for c in calls
        ],
        "base_features": features,
        "max_depth": depth,
        "char_count": len(source),
    }


def main():
    if "--file" in sys.argv:
        path = sys.argv[sys.argv.index("--file") + 1]
        with open(path, "r", encoding="utf-8-sig") as handle:
            source = handle.read()
    elif len(sys.argv) >= 2:
        source = sys.argv[1]
    else:
        source = sys.stdin.read()
    source = source.strip()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    result = analyze(source)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
