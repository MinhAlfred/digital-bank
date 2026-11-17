package thitkho.customerservice.common.web;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import thitkho.customerservice.common.exception.ErrorCode;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;

@Component
public class ProblemDetailFactory {
    private final Clock clock;

    public ProblemDetailFactory(Clock clock) {
        this.clock = clock;
    }

    public ProblemDetail create(ErrorCode errorCode) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), errorCode.getMessage());
        problemDetail.setTitle(switch (errorCode) {
            case VALIDATION_ERROR, MALFORMED_REQUEST -> "Invalid Request Data";
            case UNAUTHENTICATED -> "Unauthorized";
            case ACCESS_DENIED -> "Forbidden";
            case INTERNAL_ERROR -> "Internal Server Error";
            default -> "Business Rule Violation";
        });
        problemDetail.setType(URI.create("https://api.thitkho.com/errors/" + errorCode.name().toLowerCase(Locale.ROOT)));
        problemDetail.setProperty("errorCode", errorCode.name());
        problemDetail.setProperty("timestamp", clock.instant());
        return problemDetail;
    }
}
