package thitkho.corebankingservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.integration.customer.HttpCustomerGateway;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerServiceProperties.class)
public class CustomerClientConfiguration {
    @Bean
    CustomerGateway customerGateway(RestClient.Builder restClientBuilder,
                                    CustomerServiceProperties customerServiceProperties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(customerServiceProperties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(customerServiceProperties.readTimeout());
        return new HttpCustomerGateway(restClientBuilder
                .baseUrl(customerServiceProperties.baseUrl().toString())
                .requestFactory(requestFactory).build());
    }
}
