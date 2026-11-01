package com.shangpivot.automation.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.assertj.core.api.Assertions;

import java.io.InputStream;
import java.util.Set;

/**
 * JSON Schema 契约校验：字段/类型变更会直接让契约场景失败。
 */
public final class SchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaValidator() {
    }

    public static void validate(JsonNode payload, String classpathResource) {
        try (InputStream stream = SchemaValidator.class.getClassLoader().getResourceAsStream(classpathResource)) {
            Assertions.assertThat(stream).as("找不到 Schema 资源: " + classpathResource).isNotNull();
            JsonNode schemaNode = MAPPER.readTree(stream);
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
            Set<ValidationMessage> errors = schema.validate(payload);
            Assertions.assertThat(errors)
                    .as("响应不符合契约 %s: %s", classpathResource, errors)
                    .isEmpty();
        } catch (Exception e) {
            throw new IllegalStateException("Schema 校验失败: " + classpathResource, e);
        }
    }
}
