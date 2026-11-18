package thitkho.gatewayservice.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class GatewayBackendTestConfiguration {
    @Bean
    TestDownstreamServer customerBackend() {
        return new TestDownstreamServer("customer-backend");
    }

    @Bean
    TestDownstreamServer coreBackend() {
        return new TestDownstreamServer("core-backend");
    }

    @Bean
    DynamicPropertyRegistrar downstreamUrls(@Qualifier("customerBackend") TestDownstreamServer customerBackend,
                                           @Qualifier("coreBackend") TestDownstreamServer coreBackend) {
        return registry -> {
            registry.add("CUSTOMER_SERVICE_URI", () -> customerBackend.uri().toString());
            registry.add("CORE_BANKING_SERVICE_URI", () -> coreBackend.uri().toString());
        };
    }
}
