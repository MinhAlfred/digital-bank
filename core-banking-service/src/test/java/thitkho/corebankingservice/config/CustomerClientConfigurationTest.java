package thitkho.corebankingservice.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.integration.customer.CustomerGateway;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerClientConfigurationTest {
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(CustomerClientConfiguration.class);

    @Test
    void configuration_bootAutoConfiguredBuilder_createsAdapter() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class, SslAutoConfiguration.class))
                .withUserConfiguration(CustomerClientConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CustomerGateway.class));
    }

    @Test
    void configuration_defaults_createsGatewayWithCustomerPortAndFiniteTimeouts() {
        applicationContextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CustomerGateway.class);
            CustomerServiceProperties properties = context.getBean(CustomerServiceProperties.class);
            assertThat(properties.baseUrl().toString()).isEqualTo("http://localhost:4001");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void configuration_customSettings_bindsDurations() {
        applicationContextRunner.withPropertyValues("app.customer-service.base-url=https://customer.example",
                "app.customer-service.connect-timeout=750ms", "app.customer-service.read-timeout=1500ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CustomerServiceProperties properties = context.getBean(CustomerServiceProperties.class);
                    assertThat(properties.baseUrl().toString()).isEqualTo("https://customer.example");
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(750));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofMillis(1500));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.customer-service.connect-timeout=0ms", "app.customer-service.read-timeout=-1s",
            "app.customer-service.base-url=/relative", "app.customer-service.base-url=file:///tmp/customer",
            "app.customer-service.base-url=http://user:password@customer.example",
            "app.customer-service.base-url=http://customer.example?query=value",
            "app.customer-service.base-url=http://customer.example#fragment"})
    void configuration_invalidSettings_failsAtStartup(String setting) {
        applicationContextRunner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void customerGateway_redirect_doesNotForwardCredentialToAnotherEndpoint() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/customers/me", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpServer.createContext("/redirect-target", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();
        try {
            applicationContextRunner.withPropertyValues("app.customer-service.base-url=http://127.0.0.1:"
                    + httpServer.getAddress().getPort()).run(context -> {
                CustomerGateway customerGateway = context.getBean(CustomerGateway.class);
                assertThatThrownBy(() -> customerGateway.findCurrentCustomer("test-access-token"))
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode()).isEqualTo("INVALID_CUSTOMER_RESPONSE"));
                assertThat(redirectedRequests.get()).isZero();
            });
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void customerGateway_slowResponse_enforcesConfiguredReadTimeoutWithoutRetry() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/customers/me", exchange -> {
            requests.incrementAndGet();
            requestArrived.countDown();
            try {
                releaseResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            applicationContextRunner.withPropertyValues("app.customer-service.base-url=http://127.0.0.1:"
                    + httpServer.getAddress().getPort(), "app.customer-service.read-timeout=500ms")
                    .run(context -> {
                        CustomerGateway customerGateway = context.getBean(CustomerGateway.class);
                        var result = executorService.submit(() -> customerGateway.findCurrentCustomer("test-access-token"));
                        assertThat(requestArrived.await(3, TimeUnit.SECONDS)).isTrue();
                        assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                                .hasCauseInstanceOf(BusinessException.class)
                                .satisfies(exception -> assertThat(((BusinessException) exception.getCause()).getErrorCode())
                                        .isEqualTo("UPSTREAM_TIMEOUT"));
                        assertThat(requests.get()).isEqualTo(1);
                    });
        } finally {
            releaseResponse.countDown();
            httpServer.stop(0);
        }
    }
}
