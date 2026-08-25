package com.example.featuredag.expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free parser for function-style feature expressions.
 * Supported examples:
 *   discrete(price, [0, 100, 1000])
 *   zip_concat(item_ids, category_ids)
 */
public final class ExpressionParser {
    private static final int ERROR_EXCERPT_RADIUS = 80;
    private static final int MAX_NESTING_DEPTH = 200;

    /**
     * L0 解析入口：表达式字符串 → AST，是逻辑层构建（C3）的第一步；
     * AST 仅为临时中间表示，构建完成后即丢弃，不进入持久化计划模型。
     */
    public AstNode parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionParseException("Expression must not be blank");
        }
        Parser parser = new Parser(expression);
        AstNode result = parser.parseExpression();
        parser.expect(TokenType.EOF);
        return result;
    }

    private enum TokenType {
        IDENTIFIER,
        NUMBER,
        STRING,
        LPAREN,
        RPAREN,
        LBRACE,
        RBRACE,
        LBRACKET,
        RBRACKET,
        COMMA,
        COLON,
        EQUAL,
        EOF
    }

    private record Token(TokenType type, String text, int start, int end) {}

    private static final class Lexer {
        private final String source;
        private int index;

        private Lexer(String source) {
            this.source = source;
        }

        Token next() {
            skipWhitespace();
            if (index >= source.length()) {
                return new Token(TokenType.EOF, "", index, index);
            }
            int start = index;
            char ch = source.charAt(index);
            return switch (ch) {
                case '(' -> single(TokenType.LPAREN);
                case ')' -> single(TokenType.RPAREN);
                case '{' -> single(TokenType.LBRACE);
                case '}' -> single(TokenType.RBRACE);
                case '[' -> single(TokenType.LBRACKET);
                case ']' -> single(TokenType.RBRACKET);
                case ',' -> single(TokenType.COMMA);
                case ':' -> single(TokenType.COLON);
                case '=' -> single(TokenType.EQUAL);
                case '"', '\'' -> stringToken(ch);
                default -> {
                    if (isIdentifierStart(ch)) yield identifierToken();
                    if (Character.isDigit(ch) && digitsAreFollowedByLeftParenthesis()) {
                        yield identifierToken();
                    }
                    if (Character.isDigit(ch) || (ch == '-' && index + 1 < source.length()
                            && Character.isDigit(source.charAt(index + 1)))) {
                        yield numberToken();
                    }
                    throw error("Unexpected character '" + ch + "'", start);
                }
            };
        }

        private Token single(TokenType type) {
            int start = index++;
            return new Token(type, source.substring(start, index), start, index);
        }

        private Token identifierToken() {
            int start = index++;
            while (index < source.length()) {
                char ch = source.charAt(index);
                if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') break;
                index++;
            }
            return new Token(TokenType.IDENTIFIER, source.substring(start, index), start, index);
        }

        private boolean digitsAreFollowedByLeftParenthesis() {
            int end = index;
            while (end < source.length() && Character.isDigit(source.charAt(end))) end++;
            return end < source.length() && source.charAt(end) == '(';
        }

        private Token numberToken() {
            int start = index;
            if (source.charAt(index) == '-') index++;
            boolean dotSeen = false;
            while (index < source.length()) {
                char ch = source.charAt(index);
                if (Character.isDigit(ch)) {
                    index++;
                } else if (ch == '.' && !dotSeen) {
                    dotSeen = true;
                    index++;
                } else {
                    break;
                }
            }
            return new Token(TokenType.NUMBER, source.substring(start, index), start, index);
        }

        private Token stringToken(char quote) {
            int start = index++;
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char ch = source.charAt(index++);
                if (ch == quote) {
                    return new Token(TokenType.STRING, value.toString(), start, index);
                }
                if (ch == '\\') {
                    if (index >= source.length()) throw error("Unterminated escape sequence", index);
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case '\\' -> value.append('\\');
                        case '"' -> value.append('"');
                        case '\'' -> value.append('\'');
                        default -> throw error(
                                "Unknown escape sequence '\\" + escaped + "'", index - 1);
                    }
                } else {
                    value.append(ch);
                }
            }
            throw error("Unterminated string literal", start);
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private static boolean isIdentifierStart(char ch) {
            return Character.isLetter(ch) || ch == '_';
        }

        private ExpressionParseException error(String message, int position) {
            return new ExpressionParseException(
                    message + " at offset " + position + " near: " + sourceExcerpt(source, position));
        }
    }

    private static final class Parser {
        private final String source;
        private final Lexer lexer;
        private Token current;
        private int depth;

        private Parser(String source) {
            this.source = source;
            this.lexer = new Lexer(source);
            this.current = lexer.next();
        }

        private AstNode parseExpression() {
            // 嵌套深度上限：深嵌套表达式在无限递归下会以 StackOverflowError（Error）
            // 逃逸引擎的 RuntimeException 错误边界，改为显式深度限制并抛出可定位异常
            if (depth >= MAX_NESTING_DEPTH) {
                throw error("Expression nesting exceeds limit " + MAX_NESTING_DEPTH);
            }
            depth++;
            try {
                return switch (current.type()) {
                    case IDENTIFIER -> parseIdentifierOrCall();
                    case NUMBER -> parseNumber();
                    case STRING -> parseString();
                    case LBRACE -> parseObject();
                    case LBRACKET -> parseArray();
                    default -> throw error("Expected expression but found " + current.type());
                };
            } finally {
                depth--;
            }
        }

        private AstNode parseIdentifierOrCall() {
            Token identifier = consume(TokenType.IDENTIFIER);
            List<AstNode> arguments = new ArrayList<>();
            Map<Integer, String> argumentNames = new LinkedHashMap<>();
            Token end = identifier;
            boolean hasCall = false;
            boolean namedArgumentSeen = false;
            int invocationCount = 0;
            while (current.type() == TokenType.LPAREN) {
                hasCall = true;
                invocationCount++;
                consume(TokenType.LPAREN);
                if (current.type() != TokenType.RPAREN) {
                    do {
                        AstNode argument = parseExpression();
                        if (current.type() == TokenType.EQUAL) {
                            if (!(argument instanceof AstFeatureRef parameterRef)) {
                                throw error("Named argument must start with an identifier");
                            }
                            consume(TokenType.EQUAL);
                            argumentNames.put(arguments.size(), parameterRef.featureName());
                            argument = parseExpression();
                            namedArgumentSeen = true;
                        } else if (namedArgumentSeen) {
                            throw error("Positional argument must not follow a named argument");
                        }
                        arguments.add(argument);
                        if (current.type() != TokenType.COMMA) break;
                        consume(TokenType.COMMA);
                    } while (true);
                }
                end = consume(TokenType.RPAREN);
            }
            if (hasCall) {
                return new AstCall(
                        identifier.text(),
                        arguments,
                        argumentNames,
                        invocationCount,
                        new SourceSpan(identifier.start(), end.end()));
            }
            return switch (identifier.text()) {
                case "true" -> new AstLiteral(Boolean.TRUE, new SourceSpan(identifier.start(), identifier.end()));
                case "false" -> new AstLiteral(Boolean.FALSE, new SourceSpan(identifier.start(), identifier.end()));
                case "null" -> new AstLiteral(null, new SourceSpan(identifier.start(), identifier.end()));
                default -> new AstFeatureRef(identifier.text(), new SourceSpan(identifier.start(), identifier.end()));
            };
        }

        private AstNode parseNumber() {
            Token token = consume(TokenType.NUMBER);
            Object value;
            try {
                if (token.text().contains(".")) {
                    value = Double.parseDouble(token.text());
                } else {
                    try {
                        value = Integer.parseInt(token.text());
                    } catch (NumberFormatException intOverflow) {
                        // 整数字面量先保持 32 位兼容；超过 int 后提升为独立 BIGINT/Long。
                        value = Long.parseLong(token.text());
                    }
                }
            } catch (NumberFormatException ex) {
                throw error("Invalid numeric literal '" + token.text() + "'", token.start());
            }
            return new AstLiteral(value, new SourceSpan(token.start(), token.end()));
        }

        private AstNode parseString() {
            Token token = consume(TokenType.STRING);
            return new AstLiteral(token.text(), new SourceSpan(token.start(), token.end()));
        }

        private AstNode parseObject() {
            Token start = consume(TokenType.LBRACE);
            Map<String, AstNode> fields = new LinkedHashMap<>();
            if (current.type() != TokenType.RBRACE) {
                do {
                    Token key;
                    if (current.type() == TokenType.STRING) {
                        key = consume(TokenType.STRING);
                    } else if (current.type() == TokenType.IDENTIFIER) {
                        key = consume(TokenType.IDENTIFIER);
                    } else {
                        throw error("Expected object key");
                    }
                    consume(TokenType.COLON);
                    AstNode fieldValue = parseExpression();
                    if (fields.putIfAbsent(key.text(), fieldValue) != null) {
                        throw error("Duplicate object key '" + key.text() + "'");
                    }
                    if (current.type() != TokenType.COMMA) break;
                    consume(TokenType.COMMA);
                } while (true);
            }
            Token end = consume(TokenType.RBRACE);
            return new AstObjectLiteral(fields, new SourceSpan(start.start(), end.end()));
        }

        private AstNode parseArray() {
            Token start = consume(TokenType.LBRACKET);
            List<AstNode> elements = new ArrayList<>();
            if (current.type() != TokenType.RBRACKET) {
                do {
                    elements.add(parseExpression());
                    if (current.type() != TokenType.COMMA) break;
                    consume(TokenType.COMMA);
                } while (true);
            }
            Token end = consume(TokenType.RBRACKET);
            return new AstArrayLiteral(elements, new SourceSpan(start.start(), end.end()));
        }

        private Token consume(TokenType expected) {
            if (current.type() != expected) {
                throw error("Expected " + expected + " but found " + current.type());
            }
            Token token = current;
            current = lexer.next();
            return token;
        }

        private void expect(TokenType expected) {
            if (current.type() != expected) {
                throw error("Expected " + expected + " but found " + current.type());
            }
        }

        private ExpressionParseException error(String message) {
            return error(message, current.start());
        }

        private ExpressionParseException error(String message, int position) {
            return new ExpressionParseException(
                    message + " at offset " + position
                            + " near: " + sourceExcerpt(source, position));
        }
    }

    private static String sourceExcerpt(String source, int position) {
        int start = Math.max(0, position - ERROR_EXCERPT_RADIUS);
        int end = Math.min(source.length(), position + ERROR_EXCERPT_RADIUS);
        String prefix = start == 0 ? "" : "...";
        String suffix = end == source.length() ? "" : "...";
        return prefix + source.substring(start, end) + suffix;
    }
}
