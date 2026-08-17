/**
 * Independent implementations for the standard operators and reusable business extensions.
 *
 * <p>Each operator owns its metadata, inference and evaluation behavior. The source in this
 * package intentionally uses Java 8-compatible language features and JDK APIs. Standard
 * registration is explicit through
 * {@link com.example.featuredag.operator.builtin.InitialBusinessOperators}; business extensions
 * are registered per engine through {@link com.example.featuredag.api.InitOptions.Builder}.
 */
package com.example.featuredag.operator.builtin;
