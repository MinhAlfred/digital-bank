package thitkho.gatewayservice.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import thitkho.gatewayservice.common.constant.TestSecurityConstants;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

public final class TestTokens {
    private TestTokens() {
    }

    public static JwtBuilder claims(Instant now) {
        return Jwts.builder().subject("provider|Subject-A").issuer(TestSecurityConstants.ISSUER)
                .audience().add(TestSecurityConstants.AUDIENCE).and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(300)));
    }

    public static String sign(JwtBuilder jwtBuilder) {
        return jwtBuilder.signWith(new SecretKeySpec(TestSecurityConstants.SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"), Jwts.SIG.HS256).compact();
    }

    public static String token() {
        return sign(claims(Instant.now()));
    }
}
