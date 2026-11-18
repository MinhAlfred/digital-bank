package thitkho.gatewayservice.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import thitkho.gatewayservice.common.constant.TestSecurityConstants;
import thitkho.gatewayservice.config.GatewayProperties;
import thitkho.gatewayservice.config.JwtProperties;
import thitkho.gatewayservice.config.PublicEndpointConfig;
import thitkho.gatewayservice.support.TestTokens;
import thitkho.gatewayservice.util.GatewayResponseUtil;
import thitkho.gatewayservice.util.JwtUtil;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {
    private final PublicEndpointConfig publicEndpointConfig = new PublicEndpointConfig();
    private final AuthFilter authFilter = new AuthFilter(publicEndpointConfig,
            new GatewayProperties("test-only-internal-secret"), new JwtUtil(new JwtProperties(TestSecurityConstants.SECRET,
            TestSecurityConstants.ISSUER, TestSecurityConstants.AUDIENCE), Clock.systemUTC()), new GatewayResponseUtil());

    @Test
    void publicRoute_cleansIdentityHeadersBeforeForwarding() {
        publicEndpointConfig.setEndpoints(List.of("/api/public"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/public/status")
                .header("X-User-Id", "forged-owner").header("X-User-Role", "forged-role")
                .header("X-Internal-Secret", "forged-secret"));
        AtomicBoolean forwarded = new AtomicBoolean();

        StepVerifier.create(authFilter.apply(new AuthFilter.Config()).filter(exchange, downstream -> {
            forwarded.set(true);
            assertThat(downstream.getRequest().getHeaders().getFirst("X-Internal-Secret")).isEqualTo("test-only-internal-secret");
            assertThat(downstream.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            assertThat(downstream.getRequest().getHeaders().getFirst("X-User-Role")).isNull();
            return Mono.empty();
        })).verifyComplete();
        assertThat(forwarded.get()).isTrue();
    }

    @Test
    void publicPrefix_requiresPathSegmentBoundary() {
        publicEndpointConfig.setEndpoints(List.of("/api/public"));

        assertThat(publicEndpointConfig.isPublic("/api/public/status")).isTrue();
        assertThat(publicEndpointConfig.isPublic("/api/public-admin")).isFalse();
    }

    @Test
    void authenticatedRoute_forwardsOnlyVerifiedRole() {
        String token = TestTokens.sign(TestTokens.claims(java.time.Instant.now()).claim("role", "CUSTOMER"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts")
                .header("Authorization", "Bearer " + token).header("X-User-Role", "ADMIN"));
        AtomicBoolean forwarded = new AtomicBoolean();

        StepVerifier.create(authFilter.apply(new AuthFilter.Config(true)).filter(exchange, downstream -> {
            forwarded.set(true);
            assertThat(downstream.getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("CUSTOMER");
            return Mono.empty();
        })).verifyComplete();
        assertThat(forwarded.get()).isTrue();
    }
}
