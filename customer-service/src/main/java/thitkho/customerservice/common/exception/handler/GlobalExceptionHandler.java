package thitkho.customerservice.common.exception.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import thitkho.customerservice.common.dto.FieldValidationError;
import thitkho.customerservice.common.exception.BusinessException;
import thitkho.customerservice.common.exception.ErrorCode;
import thitkho.customerservice.common.web.ProblemDetailFactory;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException exception) {
        return problemDetailFactory.create(exception.getErrorCode());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.VALIDATION_ERROR);
        problemDetail.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage())).toList());
        return handleExceptionInternal(exception, problemDetail, headers, ErrorCode.VALIDATION_ERROR.getStatus(), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.MALFORMED_REQUEST);
        return handleExceptionInternal(exception, problemDetail, headers, ErrorCode.MALFORMED_REQUEST.getStatus(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException exception) {
        return problemDetailFactory.create(ErrorCode.DATA_CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled customer-service failure", exception);
        return problemDetailFactory.create(ErrorCode.INTERNAL_ERROR);
    }
}
