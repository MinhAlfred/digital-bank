package thitkho.gatewayservice.util;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import thitkho.gatewayservice.common.constant.TestSecurityConstants;
import thitkho.gatewayservice.config.JwtProperties;
import thitkho.gatewayservice.support.TestTokens;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {
    private final Instant now = Instant.parse("2026-11-18T03:00:00Z");
    private final JwtUtil jwtUtil = new JwtUtil(new JwtProperties(TestSecurityConstants.SECRET,
            TestSecurityConstants.ISSUER, TestSecurityConstants.AUDIENCE), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void authenticate_validHs256Token_keepsOpaqueSubjectWithoutRequiringRole() {
        assertThat(jwtUtil.authenticate(TestTokens.sign(TestTokens.claims(now)))).hasValueSatisfying(user -> {
            assertThat(user.subject()).isEqualTo("provider|Subject-A");
            assertThat(user.role()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future", "issuer", "audience", "missing-audience", "missing-expiry",
            "missing-issuer", "missing-subject", "blank-subject", "long-subject", "malformed"})
    void authenticate_invalidClaims_rejectsToken(String scenario) {
        JwtBuilder jwtBuilder = TestTokens.claims(now);
        switch (scenario) {
            case "expired" -> jwtBuilder.expiration(Date.from(now.minusSeconds(300)));
            case "future" -> jwtBuilder.notBefore(Date.from(now.plusSeconds(180)));
            case "issuer" -> jwtBuilder.issuer("https://untrusted.example.test");
            case "audience" -> jwtBuilder.audience().clear().add("other-api").and();
            case "missing-audience" -> jwtBuilder.audience().clear().and();
            case "missing-expiry" -> jwtBuilder.expiration(null);
            case "missing-issuer" -> jwtBuilder.issuer(null);
            case "missing-subject" -> jwtBuilder.subject(null);
            case "blank-subject" -> jwtBuilder.subject("   ");
            case "long-subject" -> jwtBuilder.subject("s".repeat(101));
            default -> { }
        }

        String token = "malformed".equals(scenario) ? "invalid-token" : TestTokens.sign(jwtBuilder);

        assertThat(jwtUtil.authenticate(token)).isEmpty();
    }

    @Test
    void authenticate_wrongSignature_rejectsToken() {
        String token = TestTokens.claims(now).signWith(new SecretKeySpec(
                "different-test-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertThat(jwtUtil.authenticate(token)).isEmpty();
    }

    @Test
    void authenticate_wrongAlgorithm_rejectsEvenWithMatchingSecret() {
        String secret = "test-only-long-signing-key-for-algorithm-check-".repeat(2);
        JwtUtil restrictedJwtUtil = new JwtUtil(new JwtProperties(secret, TestSecurityConstants.ISSUER,
                TestSecurityConstants.AUDIENCE), Clock.fixed(now, ZoneOffset.UTC));
        String token = TestTokens.claims(now).signWith(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA512"), Jwts.SIG.HS512).compact();

        assertThat(restrictedJwtUtil.authenticate(token)).isEmpty();
    }

    @Test
    void authenticate_withinClockSkew_matchesBackendGracePeriod() {
        String token = TestTokens.sign(TestTokens.claims(now).expiration(Date.from(now.minusSeconds(30))));

        assertThat(jwtUtil.authenticate(token)).isPresent();
    }

    @Test
    void extractToken_acceptsAuthorizationSchemeWithoutQueryFallback() {
        assertThat(jwtUtil.extractToken(MockServerHttpRequest.get("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "bearer signed-token").build())).contains("signed-token");
        assertThat(jwtUtil.extractToken(MockServerHttpRequest.get("/api/accounts?token=signed-token").build())).isEmpty();
        assertThat(jwtUtil.extractToken(MockServerHttpRequest.get("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer a", "Bearer b").build())).isEmpty();
    }
}
