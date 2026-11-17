package thitkho.customerservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(@NotBlank @Size(min = 32) String secret,
                            @NotBlank String issuer, @NotBlank String audience) {
    @Override
    public String toString() {
        return "JwtProperties[redacted]";
    }
}
