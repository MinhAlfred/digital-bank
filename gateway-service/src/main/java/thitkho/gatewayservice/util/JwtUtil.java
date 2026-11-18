package thitkho.gatewayservice.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import thitkho.gatewayservice.common.constant.GatewaySecurityConstants;
import thitkho.gatewayservice.common.dto.AuthenticatedUser;
import thitkho.gatewayservice.config.JwtProperties;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Component
public class JwtUtil {
    private final JwtParser jwtParser;

    public JwtUtil(JwtProperties jwtProperties, Clock clock) {
        this.jwtParser = Jwts.parser()
                .verifyWith(new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .requireIssuer(jwtProperties.issuer())
                .requireAudience(jwtProperties.audience())
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(GatewaySecurityConstants.CLOCK_SKEW_SECONDS)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .build();
    }

    public Optional<String> extractToken(ServerHttpRequest request) {
        List<String> authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.size() != 1) {
            return Optional.empty();
        }
        String header = authorization.getFirst();
        if (!header.regionMatches(true, 0, GatewaySecurityConstants.BEARER_PREFIX, 0,
                GatewaySecurityConstants.BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(GatewaySecurityConstants.BEARER_PREFIX.length());
        return StringUtils.hasText(token) ? Optional.of(token) : Optional.empty();
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        try {
            Claims claims = jwtParser.parseSignedClaims(token).getPayload();
            String subject = claims.getSubject();
            if (!StringUtils.hasText(subject) || subject.length() > GatewaySecurityConstants.MAX_SUBJECT_LENGTH
                    || claims.getExpiration() == null) {
                return Optional.empty();
            }
            Object role = claims.get("role");
            return Optional.of(new AuthenticatedUser(subject, role instanceof String value ? value : null));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
