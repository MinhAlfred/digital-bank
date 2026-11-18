package thitkho.corebankingservice.common.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import thitkho.corebankingservice.common.exception.ErrorCode;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ProblemDetailFactory {
    private final Clock clock;

    public ProblemDetail create(ErrorCode errorCode) {
        return create(errorCode.getStatus(), errorCode.name(), errorCode.getMessage(),
                errorCode.getStatus() == HttpStatus.BAD_REQUEST ? "Invalid Request Data" : errorCode.getStatus().getReasonPhrase());
    }

    public ProblemDetail create(HttpStatus status, String errorCode, String detail, String title) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create("https://api.thitkho.com/errors/" + errorCode.toLowerCase(Locale.ROOT)));
        problemDetail.setProperty("errorCode", errorCode);
        problemDetail.setProperty("timestamp", clock.instant());
        return problemDetail;
    }
}
