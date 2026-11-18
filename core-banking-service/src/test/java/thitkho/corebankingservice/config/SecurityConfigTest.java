package thitkho.corebankingservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import thitkho.corebankingservice.support.TestTokens;
import thitkho.corebankingservice.common.constant.TestSecurityConstants;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {
    private final JwtDecoder jwtDecoder = new SecurityConfig().jwtDecoder(
            new JwtProperties(TestSecurityConstants.SECRET, TestSecurityConstants.ISSUER, TestSecurityConstants.AUDIENCE));

    @Test
    void decode_validToken_preservesOpaqueSubject() {
        assertThat(jwtDecoder.decode(TestTokens.token("provider|Subject-A")).getSubject())
                .isEqualTo("provider|Subject-A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future", "issuer", "audience", "missing-audience", "missing-expiry",
            "missing-subject", "blank-subject", "long-subject"})
    void decode_invalidClaims_rejectsToken(String condition) {
        JwtClaimsSet.Builder jwtClaimsSetBuilder = TestTokens.claims("provider|subject");
        switch (condition) {
            case "expired" -> jwtClaimsSetBuilder.issuedAt(Instant.now().minusSeconds(600))
                    .expiresAt(Instant.now().minusSeconds(300));
            case "future" -> jwtClaimsSetBuilder.notBefore(Instant.now().plusSeconds(180));
            case "issuer" -> jwtClaimsSetBuilder.issuer("https://untrusted.example.test");
            case "audience" -> jwtClaimsSetBuilder.audience(List.of("other-api"));
            case "missing-audience" -> jwtClaimsSetBuilder.claims(claims -> claims.remove("aud"));
            case "missing-expiry" -> jwtClaimsSetBuilder.claims(claims -> claims.remove("exp"));
            case "missing-subject" -> jwtClaimsSetBuilder.claims(claims -> claims.remove("sub"));
            case "blank-subject" -> jwtClaimsSetBuilder.subject("   ");
            case "long-subject" -> jwtClaimsSetBuilder.subject("s".repeat(101));
            default -> throw new IllegalArgumentException(condition);
        }

        String token = TestTokens.encode(jwtClaimsSetBuilder.build(), TestSecurityConstants.SECRET);

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void decode_wrongSignature_rejectsToken() {
        String token = TestTokens.encode(TestTokens.claims("subject").build(), "different-test-key-32-bytes-minimum");

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void decode_nonHs256_rejectsEvenWithMatchingSecret() {
        String secret = "test-only-long-signing-key-for-algorithm-check-".repeat(2);
        JwtDecoder restrictedJwtDecoder = new SecurityConfig().jwtDecoder(new JwtProperties(secret,
                TestSecurityConstants.ISSUER, TestSecurityConstants.AUDIENCE));
        String token = TestTokens.encode(TestTokens.claims("subject").build(), secret, MacAlgorithm.HS512);

        assertThatThrownBy(() -> restrictedJwtDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void decode_withinClockSkew_acceptsTokenDuringGracePeriod() {
        String token = TestTokens.encode(TestTokens.claims("subject").issuedAt(Instant.now().minusSeconds(90))
                .expiresAt(Instant.now().minusSeconds(30)).build(), TestSecurityConstants.SECRET);

        assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo("subject");
    }
}
