package thitkho.customerservice.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import thitkho.customerservice.common.dto.customer.CreateCustomerRequest;
import thitkho.customerservice.common.dto.customer.CustomerResponse;
import thitkho.customerservice.common.exception.BusinessException;
import thitkho.customerservice.common.exception.ErrorCode;
import thitkho.customerservice.common.exception.handler.GlobalExceptionHandler;
import thitkho.customerservice.common.web.ProblemDetailFactory;
import thitkho.customerservice.config.ApplicationConfiguration;
import thitkho.customerservice.config.SecurityConfiguration;
import thitkho.customerservice.domain.CustomerStatus;
import thitkho.customerservice.security.handler.SecurityProblemHandler;
import thitkho.customerservice.service.CustomerService;
import thitkho.customerservice.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(CustomerController.class)
@ActiveProfiles("test")
@Import({SecurityConfiguration.class, ApplicationConfiguration.class, SecurityProblemHandler.class,
        ProblemDetailFactory.class, GlobalExceptionHandler.class})
class CustomerControllerTest {
    private static final String SUBJECT = "provider|Subject-A";
    private static final CustomerResponse CUSTOMER = new CustomerResponse(UUID.fromString(
            "10000000-0000-0000-0000-000000000001"), "Nguyen Van A", "a@example.com", null,
            CustomerStatus.ACTIVE, Instant.parse("2026-11-17T03:00:00Z"), Instant.parse("2026-11-17T03:00:00Z"));

    @Autowired private MockMvcTester mockMvcTester;
    @Autowired private JsonMapper jsonMapper;
    @MockitoBean private CustomerService customerService;

    @Test
    void create_validRequest_normalizesAndUsesJwtIdentity() throws Exception {
        given(customerService.create(eq(SUBJECT), any(CreateCustomerRequest.class))).willReturn(CUSTOMER);

        MvcTestResult result = post("""
                {"fullName":"  Nguyen Van A  ","email":" A@EXAMPLE.COM ","phone":"  "}
                """);

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/customers/me");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        Map<String, Object> body = body(result);
        assertThat(body).containsOnlyKeys("id", "fullName", "email", "phone", "status", "createdAt", "updatedAt");
        assertThat(body).containsEntry("id", CUSTOMER.id().toString()).containsEntry("status", "ACTIVE")
                .containsEntry("phone", null);
        ArgumentCaptor<CreateCustomerRequest> request = ArgumentCaptor.forClass(CreateCustomerRequest.class);
        then(customerService).should().create(eq(SUBJECT), request.capture());
        assertThat(request.getValue()).isEqualTo(new CreateCustomerRequest("Nguyen Van A", "a@example.com", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"fullName\":\" \",\"email\":\"a@example.com\"}",
            "{\"fullName\":\"A\",\"email\":\"not-email\"}",
            "{\"fullName\":\"A\",\"email\":\"a@example.com\",\"phone\":\"123\"}",
            "{\"fullName\":null,\"email\":\"a@example.com\"}"})
    void create_invalidFields_returnsValidationErrorsWithoutValues(String payload) throws Exception {
        MvcTestResult result = post(payload);

        assertError(result, 400, "VALIDATION_ERROR");
        assertThat(body(result)).containsKey("errors");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("rejectedValue");
        then(customerService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "customerId", "authSubject", "status", "balance", "balanceMinor", "version", "createdAt", "unknown"})
    void create_ownerOrUnknownField_rejectsRatherThanBinding(String field) throws Exception {
        MvcTestResult result = post("{\"fullName\":\"A\",\"email\":\"a@example.com\",\"" + field + "\":\"forged\"}");

        assertError(result, 400, "MALFORMED_REQUEST");
        then(customerService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"fullName\":42,\"email\":\"a@example.com\"}",
            "{\"fullName\":\"A\",\"email\":true}",
            "{\"fullName\":\"A\",\"email\":\"a@example.com\",\"phone\":123456789}"})
    void create_malformedOrWrongType_returnsSafeError(String payload) throws Exception {
        assertError(post(payload), 400, "MALFORMED_REQUEST");
        then(customerService).shouldHaveNoInteractions();
    }

    @Test
    void create_lengthCheckedAfterTrimming_acceptsBoundary() {
        given(customerService.create(eq(SUBJECT), any(CreateCustomerRequest.class))).willReturn(CUSTOMER);

        assertThat(post("{\"fullName\":\"  " + "A".repeat(200) + "  \",\"email\":\"a@example.com\"}"))
                .hasStatus(201);
    }

    @Test
    void create_nameOverLimit_rejects() throws Exception {
        assertError(post("{\"fullName\":\"" + "A".repeat(201) + "\",\"email\":\"a@example.com\"}"),
                400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER_ALREADY_EXISTS", "CUSTOMER_EMAIL_ALREADY_EXISTS"})
    void create_duplicate_returnsExistingBusinessProblemShape(String code) throws Exception {
        given(customerService.create(eq(SUBJECT), any(CreateCustomerRequest.class)))
                .willThrow(new BusinessException(ErrorCode.valueOf(code)));

        MvcTestResult result = post("{\"fullName\":\"A\",\"email\":\"a@example.com\"}");

        assertError(result, 409, code);
        assertThat(body(result)).containsEntry("title", "Business Rule Violation")
                .containsEntry("type", "https://api.thitkho.com/errors/" + code.toLowerCase(java.util.Locale.ROOT));
        assertThat(body(result)).doesNotContainKeys("code", "authSubject");
    }

    @Test
    void getCurrent_validJwt_usesSubjectAndIgnoresForgedHeaders() throws Exception {
        given(customerService.getCurrent(SUBJECT)).willReturn(CUSTOMER);

        MvcTestResult result = mockMvcTester.get().uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token(SUBJECT))
                .header("X-User-Id", "another-owner").exchange();

        assertThat(result).hasStatus(200);
        assertThat(body(result)).containsEntry("id", CUSTOMER.id().toString());
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        then(customerService).should().getCurrent(SUBJECT);
    }

    @Test
    void getCurrent_missingProfile_returns404() throws Exception {
        given(customerService.getCurrent(SUBJECT)).willThrow(new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));

        assertError(mockMvcTester.get().uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token(SUBJECT)).exchange(),
                404, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void unauthenticatedRequests_cannotUseIdentityHeaders() throws Exception {
        assertError(mockMvcTester.get().uri("/api/customers/me").header("X-User-Id", SUBJECT)
                .header("X-Internal-Secret", "forged").exchange(), 401, "UNAUTHENTICATED");
        assertError(mockMvcTester.post().uri("/api/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange(), 401, "UNAUTHENTICATED");
        then(customerService).shouldHaveNoInteractions();
    }

    @Test
    void malformedToken_returnsGenericSecurityProblem() throws Exception {
        MvcTestResult result = mockMvcTester.get().uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-token").exchange();

        assertError(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("malformed-token");
        then(customerService).shouldHaveNoInteractions();
    }

    private MvcTestResult post(String payload) {
        return mockMvcTester.post().uri("/api/customers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token(SUBJECT))
                .header("X-User-Id", "forged-owner")
                .contentType(MediaType.APPLICATION_JSON).content(payload).exchange();
    }

    private Map<String, Object> body(MvcTestResult result) throws Exception {
        return jsonMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() { });
    }

    private void assertError(MvcTestResult result, int status, String errorCode) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body(result)).containsEntry("errorCode", errorCode).containsKey("timestamp");
    }
}
