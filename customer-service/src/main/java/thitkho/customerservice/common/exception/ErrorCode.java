package thitkho.customerservice.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    CUSTOMER_NOT_FOUND("CNF01", HttpStatus.NOT_FOUND, "Customer not found"),
    CUSTOMER_ALREADY_EXISTS("CAE01", HttpStatus.CONFLICT, "Customer already exists"),
    CUSTOMER_EMAIL_ALREADY_EXISTS("CEA01", HttpStatus.CONFLICT, "Customer email already exists"),
    VALIDATION_ERROR("VE01", HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST("MR01", HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHENTICATED("UA01", HttpStatus.UNAUTHORIZED, "Authentication required"),
    ACCESS_DENIED("AD01", HttpStatus.FORBIDDEN, "Access denied"),
    DATA_CONFLICT("DC01", HttpStatus.CONFLICT, "The request conflicts with existing data"),
    INTERNAL_ERROR("IE01", HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final String code;
    private final HttpStatus status;
    private final String message;

    ErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }
}
