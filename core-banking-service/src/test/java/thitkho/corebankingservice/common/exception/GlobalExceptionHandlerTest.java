package thitkho.corebankingservice.common.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @ParameterizedTest
    @MethodSource("businessErrors")
    void handleBusinessException_preservesExistingResponseContract(
            BusinessException exception, String code, int status, String message, String type) {
        ProblemDetail problem = globalExceptionHandler.handleBusinessException(exception);

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo("Business Rule Violation");
        assertThat(problem.getDetail()).isEqualTo(message);
        assertThat(problem.getType()).isEqualTo(URI.create(type));
        assertThat(problem.getProperties()).containsOnlyKeys("errorCode", "timestamp")
                .containsEntry("errorCode", code);
        assertThat(problem.getProperties().get("timestamp")).isInstanceOf(Instant.class);
    }

    private static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE),
                        "CUSTOMER_NOT_ELIGIBLE", 409, "Customer is not active",
                        "https://api.thitkho.com/errors/customer_not_eligible"),
                Arguments.of(new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE, "Customer not found"),
                        "CUSTOMER_NOT_ELIGIBLE", 409, "Customer not found",
                        "https://api.thitkho.com/errors/customer_not_eligible"),
                Arguments.of(new BusinessException(ErrorCode.UNSUPPORTED_CURRENCY, "Unsupported currency: XXX"),
                        "UNSUPPORTED_CURRENCY", 400, "Unsupported currency: XXX",
                        "https://api.thitkho.com/errors/unsupported_currency"),
                Arguments.of(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND),
                        "ACCOUNT_NOT_FOUND", 404, "Account not found",
                        "https://api.thitkho.com/errors/account_not_found"),
                Arguments.of(new BusinessException("CUSTOMER_NOT_ELIGIBLE", "Customer not found", HttpStatus.CONFLICT),
                        "CUSTOMER_NOT_ELIGIBLE", 409, "Customer not found",
                        "https://api.thitkho.com/errors/customer_not_eligible"));
    }

    @Test
    void handleIllegalArgument_preservesExistingMapping() {
        ProblemDetail problem = globalExceptionHandler.handleIllegalArgument(new IllegalArgumentException("Invalid product type"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Invalid Request Data");
        assertThat(problem.getDetail()).isEqualTo("Invalid product type");
        assertThat(problem.getProperties()).containsOnlyKeys("errorCode")
                .containsEntry("errorCode", "INVALID_ARGUMENT");
    }
}
