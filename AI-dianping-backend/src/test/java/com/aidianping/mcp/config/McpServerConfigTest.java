package com.aidianping.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerConfigTest {

    @Test
    void httpResponsesRetainBootDateTimeSupportWithMcpMapper() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(McpServerConfig.class)
                .withPropertyValues("spring.jackson.default-property-inclusion=non_null")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ObjectMapper mapper = context.getBean("mcpObjectMapper", ObjectMapper.class);
                    MappingJackson2HttpMessageConverter converter = context.getBean(HttpMessageConverters.class)
                            .getConverters().stream()
                            .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                            .map(MappingJackson2HttpMessageConverter.class::cast)
                            .findFirst().orElseThrow();
                    assertThat(converter.getObjectMapper()).isSameAs(mapper);

                    LocalDateTime time = LocalDateTime.of(2026, 10, 29, 15, 24, 57);
                    var response = new LinkedHashMap<String, Object>();
                    response.put("createTime", time);
                    response.put("errorMsg", null);
                    String json = converter.getObjectMapper().writeValueAsString(response);
                    assertThat(mapper.readTree(json).get("createTime").asText())
                            .isEqualTo("2026-10-29T15:24:57");
                    assertThat(mapper.readTree(json).has("errorMsg")).isFalse();
                    assertThat(mapper.readValue("\"2026-10-29T15:24:57\"", LocalDateTime.class))
                            .isEqualTo(time);
                });
    }
}
