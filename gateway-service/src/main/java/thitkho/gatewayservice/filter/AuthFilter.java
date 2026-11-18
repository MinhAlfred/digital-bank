package thitkho.gatewayservice.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import thitkho.gatewayservice.common.constant.GatewaySecurityConstants;
import thitkho.gatewayservice.common.dto.AuthenticatedUser;
import thitkho.gatewayservice.config.GatewayProperties;
import thitkho.gatewayservice.config.PublicEndpointConfig;
import thitkho.gatewayservice.util.GatewayResponseUtil;
import thitkho.gatewayservice.util.JwtUtil;

import java.util.Optional;

@Component
public class AuthFilter extends AbstractGatewayFilterFactory<AuthFilter.Config> {
    private final PublicEndpointConfig publicEndpointConfig;
    private final GatewayProperties gatewayProperties;
    private final JwtUtil jwtUtil;
    private final GatewayResponseUtil gatewayResponseUtil;

    public AuthFilter(PublicEndpointConfig publicEndpointConfig, GatewayProperties gatewayProperties,
                      JwtUtil jwtUtil, GatewayResponseUtil gatewayResponseUtil) {
        super(Config.class);
        this.publicEndpointConfig = publicEndpointConfig;
        this.gatewayProperties = gatewayProperties;
        this.jwtUtil = jwtUtil;
        this.gatewayResponseUtil = gatewayResponseUtil;
    }

    public static class Config {
        private boolean authenticationRequired;

        public Config() {
        }

        public Config(boolean authenticationRequired) {
            this.authenticationRequired = authenticationRequired;
        }

        public boolean isAuthenticationRequired() {
            return authenticationRequired;
        }

        public void setAuthenticationRequired(boolean authenticationRequired) {
            this.authenticationRequired = authenticationRequired;
        }
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new OrderedGatewayFilter((exchange, chain) -> {
            ServerHttpRequest cleanedRequest = exchange.getRequest().mutate().headers(headers -> {
                headers.remove(GatewaySecurityConstants.INTERNAL_SECRET_HEADER);
                headers.remove(GatewaySecurityConstants.USER_ID_HEADER);
                headers.remove(GatewaySecurityConstants.USER_ROLE_HEADER);
            }).build();
            ServerWebExchange cleanedExchange = exchange.mutate().request(cleanedRequest).build();

            if (!config.isAuthenticationRequired() && publicEndpointConfig.isPublic(cleanedRequest.getPath().value())) {
                ServerHttpRequest publicRequest = cleanedRequest.mutate()
                        .header(GatewaySecurityConstants.INTERNAL_SECRET_HEADER, gatewayProperties.internalSecret()).build();
                return chain.filter(cleanedExchange.mutate().request(publicRequest).build());
            }

            Optional<String> token = jwtUtil.extractToken(cleanedRequest);
            if (token.isEmpty()) {
                return gatewayResponseUtil.onError(cleanedExchange, HttpStatus.UNAUTHORIZED, "Missing token");
            }
            Optional<AuthenticatedUser> user = jwtUtil.authenticate(token.get());
            if (user.isEmpty()) {
                return gatewayResponseUtil.onError(cleanedExchange, HttpStatus.UNAUTHORIZED, "Invalid token");
            }

            ServerHttpRequest forwardedRequest = cleanedRequest.mutate().headers(headers -> {
                // These compatibility headers never replace backend JWT validation or ownership checks.
                if (isSafeHeaderValue(user.get().subject())) {
                    headers.set(GatewaySecurityConstants.USER_ID_HEADER, user.get().subject());
                }
                if (isSafeHeaderValue(user.get().role())) {
                    headers.set(GatewaySecurityConstants.USER_ROLE_HEADER, user.get().role());
                }
                headers.set(GatewaySecurityConstants.INTERNAL_SECRET_HEADER, gatewayProperties.internalSecret());
            }).build();
            return chain.filter(cleanedExchange.mutate().request(forwardedRequest).build());
        }, -1);
    }

    private boolean isSafeHeaderValue(String value) {
        return value != null && value.chars().noneMatch(Character::isISOControl);
    }
}
