package thitkho.frauddetectionservice.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class OpenApiDocumentationTest {
    @Nested
    @SpringBootTest(classes = TestApplication.class)
    @AutoConfigureMockMvc
    @ActiveProfiles({"test", "local"})
    class LocalDocumentation {
        @Autowired
        private MockMvc mockMvc;

        @Test
        void specification_documentsImplementedEndpoints() throws Exception {
            var response = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            String body = response.getContentAsString();
            assertThat((String) JsonPath.read(body, "$.info.title")).isEqualTo("Fraud Detection API");
            Map<String, Object> paths = JsonPath.read(body, "$.paths");
            assertThat(paths).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/swagger-ui/index.html", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"})
        void documentation_isAvailableWithoutToken(String path) throws Exception {
            var response = mockMvc.perform(get(path)).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class)
    @AutoConfigureMockMvc
    @ActiveProfiles({"test", "local", "prod"})
    class ProductionDocumentation {
        @Autowired
        private MockMvc mockMvc;

        @ParameterizedTest
        @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui.html", "/swagger-ui/index.html"})
        void documentation_isDisabledWhenProductionOverridesLocal(String path) throws Exception {
            var response = mockMvc.perform(get(path)).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({OpenApiConfiguration.class})
    static class TestApplication {
    }
}
