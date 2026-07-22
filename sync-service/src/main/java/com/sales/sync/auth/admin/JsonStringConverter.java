package com.sales.sync.auth.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists String fields into JSONB columns. We treat the {@code String}
 * value as a JSON document (not a Java string), so on write we serialize
 * it as a JSON object with a single {@code value} key and on read we
 * unwrap it. This lets callers keep using plain {@code String} snapshots
 * of entity state (e.g. {@code "active=true,locked=false"}) without
 * having to manually build valid JSON in every audit log call site.
 */
@Converter(autoApply = false)
public class JsonStringConverter implements AttributeConverter<String, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(new Wrapper(attribute));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize audit snapshot to JSON: " + attribute, e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, Wrapper.class).value();
        } catch (JsonProcessingException e) {
            // Be lenient on read: if the column was previously written with
            // the buggy plain-text path, return it as-is so reads don't
            // break historical rows.
            return dbData;
        }
    }

    private record Wrapper(String value) {}
}
