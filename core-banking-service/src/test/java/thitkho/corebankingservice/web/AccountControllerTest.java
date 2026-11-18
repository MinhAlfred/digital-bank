package thitkho.corebankingservice.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import thitkho.corebankingservice.common.dto.account.AccountBalanceResponse;
import thitkho.corebankingservice.common.dto.account.AccountResponse;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.common.exception.ErrorCode;
import thitkho.corebankingservice.common.exception.handler.GlobalExceptionHandler;
import thitkho.corebankingservice.common.web.ProblemDetailFactory;
import thitkho.corebankingservice.config.ApplicationConfiguration;
import thitkho.corebankingservice.config.SecurityConfig;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.service.AccountService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, ApplicationConfiguration.class, ProblemDetailFactory.class, GlobalExceptionHandler.class})
class AccountControllerTest {
    private final UUID accountId = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final String accessToken = "test-access-token";
    private final Instant now = Instant.parse("2026-11-18T03:00:00Z");
    private final AccountResponse accountResponse = new AccountResponse(accountId, "880000000019", customerId,
            AccountType.CUSTOMER, ProductType.CHECKING, "VND", AccountStatus.ACTIVE, "0", 0, now, now, null);

    @Autowired private MockMvcTester mockMvcTester;
    @Autowired private JsonMapper jsonMapper;
    @MockitoBean private AccountService accountService;
    @MockitoBean private JwtDecoder jwtDecoder;

    @Test
    void create_validRequest_usesVerifiedTokenAndReturnsCreatedLocation() throws Exception {
        given(accountService.openAccount(ProductType.CHECKING, "VND", accessToken)).willReturn(accountResponse);

        MvcTestResult result = mockMvcTester.post().uri("/api/accounts")
                .with(jwt().jwt(builder -> builder.tokenValue(accessToken).subject("provider|opaque-subject")))
                .header("X-User-Id", "forged-owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productType\":\"CHECKING\",\"currency\":\"VND\"}").exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/accounts/" + accountId);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertAccountBody(body(result));
        then(accountService).should().openAccount(ProductType.CHECKING, "VND", accessToken);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"productType\":null,\"currency\":\"VND\"}",
            "{\"productType\":\"CHECKING\"}", "{\"productType\":\"CHECKING\",\"currency\":null}",
            "{\"productType\":\"CHECKING\",\"currency\":\"\"}",
            "{\"productType\":\"CHECKING\",\"currency\":\"usd\"}",
            "{\"productType\":\"CHECKING\",\"currency\":\" VND \"}",
            "{\"productType\":\"CHECKING\",\"currency\":\"123\"}"})
    void create_invalidFields_returnsValidationErrorWithoutCallingService(String payload) throws Exception {
        MvcTestResult result = post(payload);

        assertError(result, 400, "VALIDATION_ERROR");
        assertThat(body(result)).containsKey("errors");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("rejectedValue");
        then(accountService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "customerId", "accountNumber", "status", "accountType", "balance", "balanceMinor",
            "version", "createdAt", "updatedAt", "unknown"})
    void create_serverOwnedOrUnknownField_rejectsRequest(String field) throws Exception {
        MvcTestResult result = post("{\"productType\":\"CHECKING\",\"currency\":\"VND\",\"" + field + "\":\"forged\"}");

        assertError(result, 400, "MALFORMED_REQUEST");
        then(accountService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"productType\":\"UNKNOWN\",\"currency\":\"VND\"}",
            "{\"productType\":\"checking\",\"currency\":\"VND\"}",
            "{\"productType\":0,\"currency\":\"VND\"}",
            "{\"productType\":\"CHECKING\",\"currency\":123}",
            "{\"productType\":\"CHECKING\",\"currency\":true}"})
    void create_malformedBodyOrWrongTypes_returnsSafeProblem(String payload) throws Exception {
        assertError(post(payload), 400, "MALFORMED_REQUEST");
        then(accountService).shouldHaveNoInteractions();
    }

    @Test
    void getAccount_ownedAccount_returnsExactContractAndNoStore() throws Exception {
        given(accountService.getOwnedAccount(accountId, accessToken)).willReturn(accountResponse);

        MvcTestResult result = get("/api/accounts/" + accountId);

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertAccountBody(body(result));
    }

    @Test
    void getBalance_largeBalance_returnsStringAndMinorUnitWithoutInternalFields() throws Exception {
        given(accountService.getOwnedAccountBalance(accountId, accessToken)).willReturn(new AccountBalanceResponse(
                accountId, "USD", AccountStatus.FROZEN, "9223372036854775807", 2));

        MvcTestResult result = get("/api/accounts/" + accountId + "/balance");

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(body(result)).containsOnlyKeys("accountId", "currency", "status", "balanceMinor", "minorUnit")
                .containsEntry("balanceMinor", "9223372036854775807").containsEntry("minorUnit", 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/accounts/not-a-uuid", "/api/accounts/not-a-uuid/balance"})
    void get_invalidId_returnsMalformedRequest(String path) throws Exception {
        assertError(get(path), 400, "MALFORMED_REQUEST");
        then(accountService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNSUPPORTED_CURRENCY", "CUSTOMER_NOT_ELIGIBLE",
            "UPSTREAM_TIMEOUT", "CUSTOMER_SERVICE_UNAVAILABLE", "INVALID_CUSTOMER_RESPONSE"})
    void create_serviceFailure_preservesBusinessContract(ErrorCode errorCode) throws Exception {
        given(accountService.openAccount(ProductType.CHECKING, "VND", accessToken)).willThrow(new BusinessException(errorCode));

        assertError(post("{\"productType\":\"CHECKING\",\"currency\":\"VND\"}"),
                errorCode.getStatus().value(), errorCode.name());
    }

    @Test
    void getAccountAndBalance_hiddenAccount_returnSameNotFound() throws Exception {
        given(accountService.getOwnedAccount(accountId, accessToken)).willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        given(accountService.getOwnedAccountBalance(accountId, accessToken)).willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        assertError(get("/api/accounts/" + accountId), 404, "ACCOUNT_NOT_FOUND");
        assertError(get("/api/accounts/" + accountId + "/balance"), 404, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void allEndpoints_missingToken_rejectEvenWithForgedIdentityHeaders() {
        assertThat(mockMvcTester.post().uri("/api/accounts").header("X-User-Id", customerId.toString())
                .header("X-Internal-Secret", "forged").contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(401);
        assertThat(mockMvcTester.get().uri("/api/accounts/" + accountId).exchange()).hasStatus(401);
        assertThat(mockMvcTester.get().uri("/api/accounts/" + accountId + "/balance").exchange()).hasStatus(401);
        then(accountService).shouldHaveNoInteractions();
    }

    @Test
    void getAccount_invalidToken_rejectsBeforeService() {
        given(jwtDecoder.decode("invalid-token")).willThrow(new BadJwtException("Invalid token"));

        assertThat(mockMvcTester.get().uri("/api/accounts/" + accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token").exchange()).hasStatus(401);
        then(accountService).shouldHaveNoInteractions();
    }

    private MvcTestResult post(String body) {
        return mockMvcTester.post().uri("/api/accounts")
                .with(jwt().jwt(builder -> builder.tokenValue(accessToken).subject("provider|opaque-subject")))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult get(String path) {
        return mockMvcTester.get().uri(path)
                .with(jwt().jwt(builder -> builder.tokenValue(accessToken).subject("provider|opaque-subject"))).exchange();
    }

    private Map<String, Object> body(MvcTestResult result) throws Exception {
        return jsonMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private void assertAccountBody(Map<String, Object> body) {
        assertThat(body).containsOnlyKeys("id", "accountNumber", "customerId", "accountType", "productType", "currency",
                        "status", "balanceMinor", "minorUnit", "createdAt", "updatedAt", "closedAt")
                .containsEntry("id", accountId.toString()).containsEntry("customerId", customerId.toString())
                .containsEntry("balanceMinor", "0").containsEntry("closedAt", null);
    }

    private void assertError(MvcTestResult result, int status, String errorCode) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body(result)).containsEntry("errorCode", errorCode).containsEntry("status", status).containsKey("timestamp");
    }
}
