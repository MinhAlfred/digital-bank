package thitkho.customerservice.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public final class TestTokens {
    public static final String SECRET = "test-only-bank-key-32-bytes-minimum";
    public static final String ISSUER = "https://issuer.example.test";
    public static final String AUDIENCE = "digital-bank-test";

    private TestTokens() {
    }

    public static JwtClaimsSet.Builder claims(String subject) {
        return JwtClaimsSet.builder().subject(subject).issuer(ISSUER).audience(List.of(AUDIENCE))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    }

    public static String token(String subject) {
        return encode(claims(subject).build(), SECRET);
    }

    public static String encode(JwtClaimsSet jwtClaimsSet, String secret) {
        return encode(jwtClaimsSet, secret, MacAlgorithm.HS256);
    }

    public static String encode(JwtClaimsSet jwtClaimsSet, String secret, MacAlgorithm algorithm) {
        NimbusJwtEncoder jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(algorithm).build(), jwtClaimsSet)).getTokenValue();
    }
}
