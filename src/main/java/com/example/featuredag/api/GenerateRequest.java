package com.example.featuredag.api;

public sealed interface GenerateRequest permits OfflineGenerateRequest, OnlineGenerateRequest {
    String executionId();
}
