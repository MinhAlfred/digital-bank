package thitkho.corebankingservice.web;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import thitkho.corebankingservice.common.web.ProblemDetailFactory;
import thitkho.corebankingservice.config.ApplicationConfiguration;
import thitkho.corebankingservice.config.OpenApiConfiguration;
import thitkho.corebankingservice.config.SecurityConfig;
import thitkho.corebankingservice.security.handler.SecurityProblemHandler;
import thitkho.corebankingservice.service.AccountService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class OpenApiDocumentationTest {
    @Nested
    @SpringBootTest(classes = TestApplication.class)
    @AutoConfigureMockMvc
    @ActiveProfiles({"test", "local"})
    class LocalDocumentation {
        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AccountService accountService;

        @Test
        void specification_documentsImplementedEndpoints() throws Exception {
            var response = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            String body = response.getContentAsString();
            assertThat((String) JsonPath.read(body, "$.info.title")).isEqualTo("Core Banking API");

            Map<String, Object> paths = JsonPath.read(body, "$.paths");
            assertThat(paths).containsOnlyKeys("/api/accounts", "/api/accounts/{accountId}", "/api/accounts/{accountId}/balance");
            assertThat((String) JsonPath.read(body, "$.components.securitySchemes.bearerAuth.scheme"))
                    .isEqualTo("bearer");
            assertThat((List<?>) JsonPath.read(body, "$.security[0].bearerAuth")).isEmpty();
            Map<String, Object> responses = JsonPath.read(body, "$.paths['/api/accounts'].post.responses");
            assertThat(responses).containsKey("201").doesNotContainKey("200");
            assertThat((List<?>) JsonPath.read(body, "$..parameters[?(@.name == 'jwt')]")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/swagger-ui/index.html", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"})
        void documentation_isAvailableWithoutToken(String path) throws Exception {
            var response = mockMvc.perform(get(path)).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        void businessApi_stillRequiresAuthentication() throws Exception {
            var response = mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001")).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class)
    @AutoConfigureMockMvc
    @ActiveProfiles({"test", "local", "prod"})
    class ProductionDocumentation {
        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AccountService accountService;

        @ParameterizedTest
        @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui.html", "/swagger-ui/index.html"})
        void documentation_isDisabledWhenProductionOverridesLocal(String path) throws Exception {
            var response = mockMvc.perform(get(path).with(jwt())).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({OpenApiConfiguration.class, AccountController.class, SecurityConfig.class,
            ApplicationConfiguration.class, ProblemDetailFactory.class, SecurityProblemHandler.class})
    static class TestApplication {
    }
}
