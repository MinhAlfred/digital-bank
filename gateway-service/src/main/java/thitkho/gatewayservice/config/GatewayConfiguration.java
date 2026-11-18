package thitkho.gatewayservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, GatewayProperties.class})
public class GatewayConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
