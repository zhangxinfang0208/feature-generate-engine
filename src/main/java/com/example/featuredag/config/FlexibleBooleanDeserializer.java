package com.example.featuredag.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.Locale;

public final class FlexibleBooleanDeserializer extends JsonDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) return Boolean.TRUE;
        if (token == JsonToken.VALUE_FALSE) return Boolean.FALSE;
        if (token == JsonToken.VALUE_NULL) return null;
        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getText().trim().toLowerCase(Locale.ROOT);
            // 兼容历史配置用空串表示“未开启”，统一收敛为 false。
            if (value.isEmpty()) return Boolean.FALSE;
            if ("true".equals(value)) return Boolean.TRUE;
            if ("false".equals(value)) return Boolean.FALSE;
        }
        throw JsonMappingException.from(
                parser, "Expected boolean, blank string, or string 'true'/'false'");
    }
}
