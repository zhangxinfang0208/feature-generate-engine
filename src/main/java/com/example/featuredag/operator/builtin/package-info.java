/**
 * Independent implementations for the standard operators: the eight operators in the
 * initial release plus group_count_concat, the to_int / to_bigint / min / max numeric
 * operators and the add / sub / mul / div arithmetic operators.
 *
 * <p>Each operator owns its metadata, inference and evaluation behavior. The source in this
 * package intentionally uses Java 8-compatible language features and JDK APIs. Registration is
 * explicit through {@link com.example.featuredag.operator.builtin.InitialBusinessOperators}.
 */
package com.example.featuredag.operator.builtin;
