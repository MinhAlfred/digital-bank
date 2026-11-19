package thitkho.gatewayservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocumentationTest {
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles({"test", "local"})
    class LocalDocumentation {
        @LocalServerPort
        private int port;
        private WebTestClient webTestClient;

        @BeforeEach
        void setUp() {
            webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }

        @Test
        void specification_describesGatewayWithoutInventingControllerEndpoints() {
            webTestClient.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.info.title").isEqualTo("Gateway API")
                    .jsonPath("$.paths").value(paths -> assertThat((Map<?, ?>) paths).isEmpty());
        }

        @ParameterizedTest
        @ValueSource(strings = {"/swagger-ui/index.html", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"})
        void documentation_isAvailableWithoutToken(String path) {
            webTestClient.get().uri(path).exchange().expectStatus().isOk();
        }

        @Test
        void businessRoute_stillRequiresAuthentication() {
            webTestClient.get().uri("/api/customers/me").exchange().expectStatus().isUnauthorized();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles({"test", "local", "prod"})
    class ProductionDocumentation {
        @LocalServerPort
        private int port;
        private WebTestClient webTestClient;

        @BeforeEach
        void setUp() {
            webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui.html", "/swagger-ui/index.html"})
        void documentation_isDisabledWhenProductionOverridesLocal(String path) {
            webTestClient.get().uri(path).exchange().expectStatus().isNotFound();
        }
    }
}
