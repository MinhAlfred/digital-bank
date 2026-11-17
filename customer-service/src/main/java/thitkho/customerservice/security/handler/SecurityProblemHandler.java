package thitkho.customerservice.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import thitkho.customerservice.common.exception.ErrorCode;
import thitkho.customerservice.common.web.ProblemDetailFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ProblemDetailFactory problemDetailFactory;
    private final JsonMapper jsonMapper;

    public SecurityProblemHandler(ProblemDetailFactory problemDetailFactory, JsonMapper jsonMapper) {
        this.problemDetailFactory = problemDetailFactory;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, ErrorCode.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(response, ErrorCode.ACCESS_DENIED);
    }

    private void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problemDetailFactory.create(errorCode));
    }
}
