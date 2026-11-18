package thitkho.gatewayservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(@NotBlank String internalSecret) {
    @Override
    public String toString() {
        return "GatewayProperties[redacted]";
    }
}
