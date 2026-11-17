package thitkho.corebankingservice.common.exception;

import org.springframework.http.HttpStatus;

/** Stable business error codes and their existing HTTP mappings. */
public enum ErrorCode {
    ACCOUNT_NOT_FOUND("ANF01", HttpStatus.NOT_FOUND, "Account not found"),
    CUSTOMER_NOT_ELIGIBLE("CNE01", HttpStatus.CONFLICT, "Customer is not active"),
    UNSUPPORTED_CURRENCY("UC01", HttpStatus.BAD_REQUEST, "Unsupported currency");

    private final String code;
    private final HttpStatus status;
    private final String message;

    ErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
