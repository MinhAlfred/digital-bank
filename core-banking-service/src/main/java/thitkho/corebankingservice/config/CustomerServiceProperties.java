package thitkho.corebankingservice.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("app.customer-service")
public record CustomerServiceProperties(
        @NotNull @DefaultValue("http://localhost:4001") URI baseUrl,
        @NotNull @DurationMin(millis = 1) @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DurationMin(millis = 1) @DefaultValue("30s") Duration readTimeout) {

    @AssertTrue(message = "base URL must use HTTP(S), have a host, and exclude credentials, query and fragment")
    public boolean isBaseUrlValid() {
        return baseUrl != null && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                && baseUrl.getHost() != null && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null && baseUrl.getFragment() == null;
    }
}
