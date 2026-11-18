package thitkho.gatewayservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import thitkho.gatewayservice.support.GatewayBackendTestConfiguration;
import thitkho.gatewayservice.support.TestDownstreamServer;
import thitkho.gatewayservice.support.TestTokens;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"public.endpoints[0]=/api/accounts", "public.endpoints[1]=/api/customers"})
@ActiveProfiles("test")
@Import(GatewayBackendTestConfiguration.class)
class BankRoutesTest {
    @LocalServerPort private int port;
    @Autowired @Qualifier("customerBackend") private TestDownstreamServer customerBackend;
    @Autowired @Qualifier("coreBackend") private TestDownstreamServer coreBackend;
    @Autowired private GatewayProperties gatewayProperties;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        customerBackend.clear();
        coreBackend.clear();
    }

    @Test
    void yamlRoutes_keepProtectedBankPoliciesAndLegacyServices() {
        assertThat(gatewayProperties.getRoutes()).extracting(route -> route.getId())
                .containsExactlyInAnyOrder("customer-service", "core-banking-service", "notification-service",
                        "fraud-detection-service", "analysis-service");
        gatewayProperties.getRoutes().stream()
                .filter(route -> route.getId().equals("customer-service") || route.getId().equals("core-banking-service"))
                .forEach(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("lb://" + route.getId());
                    assertThat(route.getOrder()).isEqualTo(-100);
                    assertThat(((Number) route.getMetadata().get("connect-timeout")).intValue()).isEqualTo(2000);
                    assertThat(((Number) route.getMetadata().get("response-timeout")).longValue()).isEqualTo(35000);
                    assertThat(route.getFilters()).anySatisfy(filter -> {
                        assertThat(filter.getName()).isEqualTo("AuthFilter");
                        assertThat(filter.getArgs()).containsEntry("authenticationRequired", "true");
                    });
                });
    }

    @Test
    void legacyCorePrefix_stillRoutesToCoreWithAuthentication() {
        webTestClient.get().uri("/api/core/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token())
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("core-backend");

        assertThat(coreBackend.takeRequest().uri()).isEqualTo("/api/core/status");
        assertThat(customerBackend.hasRequests()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/customers,customer", "GET,/api/customers/me,customer", "POST,/api/accounts,core",
            "GET,/api/accounts/20000000-0000-0000-0000-000000000001,core",
            "GET,/api/accounts/20000000-0000-0000-0000-000000000001/balance,core"})
    void bankRoute_validToken_forwardsToCorrectServiceWithoutTrustingClientHeaders(String method, String path, String backend) {
        String token = TestTokens.token();
        boolean customerRoute = "customer".equals(backend);

        webTestClient.method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "forged-owner").header("X-User-Role", "forged-role")
                .header("X-Internal-Secret", "forged-secret")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("POST".equals(method) ? "{}" : "")
                .exchange().expectStatus().isOk().expectBody(String.class)
                .isEqualTo(customerRoute ? "customer-backend" : "core-backend");

        var forwarded = (customerRoute ? customerBackend : coreBackend).takeRequest();
        assertThat(forwarded.method()).isEqualTo(method);
        assertThat(forwarded.uri()).isEqualTo(path);
        assertThat(forwarded.headers().get("authorization")).containsExactly("Bearer " + token);
        assertThat(forwarded.headers().get("x-user-id")).containsExactly("provider|Subject-A");
        assertThat(forwarded.headers()).doesNotContainKey("x-user-role");
        assertThat(forwarded.headers().get("x-internal-secret")).containsExactly("test-only-internal-secret");
        assertThat(forwarded.body()).isEqualTo("POST".equals(method) ? "{}" : "");
        assertThat((customerRoute ? coreBackend : customerBackend).hasRequests()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/accounts", "/api/accounts/id/balance", "/api/customers", "/api/customers/me"})
    void bankRoute_missingToken_rejectsEvenWhenConfiguredAsPublic(String path) {
        webTestClient.get().uri(path).header("X-User-Id", "forged-owner").header("X-Internal-Secret", "forged-secret")
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(401).jsonPath("$.message").isEqualTo("Missing token");

        assertThat(customerBackend.hasRequests()).isFalse();
        assertThat(coreBackend.hasRequests()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future", "issuer", "audience", "malformed"})
    void bankRoute_invalidToken_doesNotReachBackend(String scenario) {
        var claims = TestTokens.claims(Instant.now());
        switch (scenario) {
            case "expired" -> claims.expiration(Date.from(Instant.now().minusSeconds(300)));
            case "future" -> claims.notBefore(Date.from(Instant.now().plusSeconds(180)));
            case "issuer" -> claims.issuer("https://untrusted.example.test");
            case "audience" -> claims.audience().clear().add("other-api").and();
            default -> { }
        }
        String token = "malformed".equals(scenario) ? "invalid-token" : TestTokens.sign(claims);

        webTestClient.get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isUnauthorized().expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.status").isEqualTo(401).jsonPath("$.message").isEqualTo("Invalid token");

        assertThat(customerBackend.hasRequests()).isFalse();
        assertThat(coreBackend.hasRequests()).isFalse();
    }

    @Test
    void bankRoute_queryToken_isNotAcceptedAsAuthentication() {
        webTestClient.get().uri(uri -> uri.path("/api/accounts").queryParam("token", TestTokens.token()).build())
                .exchange().expectStatus().isUnauthorized();

        assertThat(coreBackend.hasRequests()).isFalse();
    }
}
