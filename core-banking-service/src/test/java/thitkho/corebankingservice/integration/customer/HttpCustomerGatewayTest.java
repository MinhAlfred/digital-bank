package thitkho.corebankingservice.integration.customer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import thitkho.corebankingservice.common.exception.BusinessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCustomerGatewayTest {
    private static final String CUSTOMER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String TOKEN = "test-access-token";
    private MockRestServiceServer mockRestServiceServer;
    private CustomerGateway customerGateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://customer.test:4001");
        mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        customerGateway = new HttpCustomerGateway(restClientBuilder.build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED", "CLOSED"})
    void findCurrentCustomer_validResponse_forwardsTokenAndIgnoresUnneededFields(String status) {
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("""
                        {"id":"%s","status":"%s","fullName":"Example","email":"example@example.com"}
                        """.formatted(CUSTOMER_ID, status), MediaType.APPLICATION_JSON));

        assertThat(customerGateway.findCurrentCustomer(TOKEN)).hasValueSatisfying(customerInfo -> {
            assertThat(customerInfo.id()).isEqualTo(UUID.fromString(CUSTOMER_ID));
            assertThat(customerInfo.status()).isEqualTo(status);
        });
        mockRestServiceServer.verify();
    }

    @Test
    void findCurrentCustomer_notFound_returnsEmpty() {
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(customerGateway.findCurrentCustomer(TOKEN)).isEmpty();
        mockRestServiceServer.verify();
    }

    @ParameterizedTest
    @CsvSource({"401,UNAUTHENTICATED,401", "403,ACCESS_DENIED,403",
            "500,CUSTOMER_SERVICE_UNAVAILABLE,503", "503,CUSTOMER_SERVICE_UNAVAILABLE,503",
            "504,CUSTOMER_SERVICE_UNAVAILABLE,503", "400,INVALID_CUSTOMER_RESPONSE,502",
            "429,INVALID_CUSTOMER_RESPONSE,502", "302,INVALID_CUSTOMER_RESPONSE,502"})
    void findCurrentCustomer_upstreamError_mapsStatusWithoutExposingBody(int upstreamStatus, String code, int status) {
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andRespond(withStatus(HttpStatus.valueOf(upstreamStatus)).body("private upstream details"));

        assertFailure(code, status);
        mockRestServiceServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[]", "not json", "{\"id\":\"invalid\",\"status\":\"ACTIVE\"}",
            "{\"status\":\"ACTIVE\"}", "{\"id\":\"10000000-0000-0000-0000-000000000001\"}",
            "{\"id\":\"10000000-0000-0000-0000-000000000001\",\"status\":null}",
            "{\"id\":\"10000000-0000-0000-0000-000000000001\",\"status\":\"UNKNOWN\"}",
            "{\"id\":\"10000000-0000-0000-0000-000000000001\",\"status\":\"active\"}"})
    void findCurrentCustomer_invalidBody_rejectsResponse(String body) {
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertFailure("INVALID_CUSTOMER_RESPONSE", 502);
        mockRestServiceServer.verify();
    }

    @Test
    void findCurrentCustomer_noContent_rejectsResponse() {
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertFailure("INVALID_CUSTOMER_RESPONSE", 502);
        mockRestServiceServer.verify();
    }

    @Test
    void findCurrentCustomer_connectionFailure_reportsUnavailable() {
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andRespond(withException(new ConnectException("connection refused")));

        assertFailure("CUSTOMER_SERVICE_UNAVAILABLE", 503);
        mockRestServiceServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect", "read", "socket", "nested"})
    void findCurrentCustomer_timeout_reportsGatewayTimeout(String kind) {
        IOException timeout = switch (kind) {
            case "connect" -> new HttpConnectTimeoutException("connect timed out");
            case "socket" -> new SocketTimeoutException("read timed out");
            case "nested" -> new IOException("I/O failure", new HttpTimeoutException("read timed out"));
            default -> new HttpTimeoutException("read timed out");
        };
        mockRestServiceServer.expect(requestTo("http://customer.test:4001/api/customers/me"))
                .andRespond(withException(timeout));

        assertFailure("UPSTREAM_TIMEOUT", 504);
        mockRestServiceServer.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void findCurrentCustomer_missingToken_rejectsBeforeHttp(String token) {
        assertThatThrownBy(() -> customerGateway.findCurrentCustomer(token))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("UNAUTHENTICATED"));
        mockRestServiceServer.verify();
    }

    private void assertFailure(String code, int status) {
        assertThatThrownBy(() -> customerGateway.findCurrentCustomer(TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(code);
                    assertThat(exception.getStatus().value()).isEqualTo(status);
                    assertThat(exception.getMessage()).doesNotContain(TOKEN, "private upstream details");
                });
    }
}
