package thitkho.customerservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import thitkho.customerservice.common.dto.customer.CreateCustomerRequest;
import thitkho.customerservice.common.exception.BusinessException;
import thitkho.customerservice.common.exception.ErrorCode;
import thitkho.customerservice.repository.CustomerRepository;
import thitkho.customerservice.service.CustomerService;
import thitkho.customerservice.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CustomerManagementTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired private MockMvcTester mockMvcTester;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CustomerService customerService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearCustomers() {
        customerRepository.deleteAll();
    }

    @Test
    void createThenRead_resolvesOpaqueJwtSubjectAndIsolatesOtherUsers() throws Exception {
        MvcTestResult created = post("provider|Subject-A", "  A@EXAMPLE.COM  ");
        assertThat(created).hasStatus(201);
        Map<String, Object> createdBody = body(created);
        assertThat(createdBody).containsEntry("email", "a@example.com").containsEntry("status", "ACTIVE")
                .containsEntry("fullName", "Nguyen Van A").containsEntry("phone", null);
        assertThat(createdBody.get("id")).isNotNull();
        assertThat(customerRepository.findByAuthSubject("provider|Subject-A")).isPresent();

        MvcTestResult own = getCurrent("provider|Subject-A");
        assertThat(own).hasStatus(200);
        assertThat(body(own)).containsEntry("id", createdBody.get("id"));
        assertThat(getCurrent("provider|subject-a")).hasStatus(404);
        assertThat(customerRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicateSubjectAndCaseInsensitiveEmail_returnDistinctConflicts() throws Exception {
        assertThat(post("subject-a", "a@example.com")).hasStatus(201);

        MvcTestResult sameSubject = post("subject-a", "a@example.com");
        MvcTestResult sameEmail = post("subject-b", "A@EXAMPLE.COM");

        assertThat(sameSubject).hasStatus(409);
        assertThat(body(sameSubject)).containsEntry("errorCode", "CUSTOMER_ALREADY_EXISTS");
        assertThat(sameEmail).hasStatus(409);
        assertThat(body(sameEmail)).containsEntry("errorCode", "CUSTOMER_EMAIL_ALREADY_EXISTS");
        assertThat(customerRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "CLOSED"})
    void getCurrent_inactiveCustomer_canReadOwnStatus(String status) throws Exception {
        assertThat(post("subject-a", "a@example.com")).hasStatus(201);
        jdbcTemplate.update("update customers set status = ? where auth_subject = ?", status, "subject-a");

        MvcTestResult result = getCurrent("subject-a");

        assertThat(result).hasStatus(200);
        assertThat(body(result)).containsEntry("status", status);
    }

    @Test
    void forgedOwnerField_rejectsWithoutPersisting() throws Exception {
        MvcTestResult result = mockMvcTester.post().uri("/api/customers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token("real-subject"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"A\",\"email\":\"a@example.com\",\"authSubject\":\"victim\"}").exchange();

        assertThat(result).hasStatus(400);
        assertThat(body(result)).containsEntry("errorCode", "MALFORMED_REQUEST");
        assertThat(customerRepository.count()).isZero();
    }

    @Test
    void databaseUniqueEmail_rejectsDifferentCaseEvenWithoutApplicationNormalization() {
        customerService.create("subject-a", new CreateCustomerRequest("A", "a@example.com", null));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into customers (auth_subject, full_name, email) values (?, ?, ?)",
                "subject-b", "B", "A@EXAMPLE.COM")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(customerRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentSameSubject_createsOneCustomerAndReturnsSubjectConflict() throws Exception {
        assertConcurrentOutcome("subject-a", "a@example.com", "subject-a", "b@example.com",
                ErrorCode.CUSTOMER_ALREADY_EXISTS);
    }

    @Test
    void concurrentSameEmail_createsOneCustomerAndReturnsEmailConflict() throws Exception {
        assertConcurrentOutcome("subject-a", "a@example.com", "subject-b", "A@EXAMPLE.COM",
                ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
    }

    private void assertConcurrentOutcome(String firstSubject, String firstEmail, String secondSubject,
                                         String secondEmail, ErrorCode expectedFailure) throws Exception {
        CyclicBarrier cyclicBarrier = new CyclicBarrier(2);
        try (var executorService = Executors.newFixedThreadPool(2)) {
            List<Callable<String>> requests = List.of(
                    () -> createAfterBarrier(cyclicBarrier, firstSubject, firstEmail),
                    () -> createAfterBarrier(cyclicBarrier, secondSubject, secondEmail));
            var futures = executorService.invokeAll(requests, 20, TimeUnit.SECONDS);
            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactlyInAnyOrder("CREATED", expectedFailure.name());
        }
        assertThat(customerRepository.count()).isEqualTo(1);
    }

    private String createAfterBarrier(CyclicBarrier cyclicBarrier, String subject, String email) throws Exception {
        cyclicBarrier.await(10, TimeUnit.SECONDS);
        try {
            customerService.create(subject, new CreateCustomerRequest("A", email, null));
            return "CREATED";
        } catch (BusinessException exception) {
            return exception.getErrorCode().name();
        }
    }

    private MvcTestResult post(String subject, String email) {
        return mockMvcTester.post().uri("/api/customers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new CreateCustomerRequest("  Nguyen Van A  ", email, "  "))).exchange();
    }

    private MvcTestResult getCurrent(String subject) {
        return mockMvcTester.get().uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token(subject)).exchange();
    }

    private Map<String, Object> body(MvcTestResult result) throws Exception {
        return jsonMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() { });
    }
}
