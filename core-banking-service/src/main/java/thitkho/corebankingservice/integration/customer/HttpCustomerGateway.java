package thitkho.corebankingservice.integration.customer;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import thitkho.corebankingservice.common.constant.CustomerConstants;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.common.exception.ErrorCode;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

public class HttpCustomerGateway implements CustomerGateway {
    private final RestClient restClient;

    public HttpCustomerGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<CustomerInfo> findCurrentCustomer(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            ResponseEntity<CustomerInfo> response = restClient.get().uri("/api/customers/me")
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().toEntity(CustomerInfo.class);
            CustomerInfo customerInfo = response.getBody();
            if (response.getStatusCode().value() != 200 || customerInfo == null || customerInfo.id() == null
                    || customerInfo.status() == null || !CustomerConstants.CUSTOMER_STATUSES.contains(customerInfo.status())) {
                throw new BusinessException(ErrorCode.INVALID_CUSTOMER_RESPONSE);
            }
            return Optional.of(customerInfo);
        } catch (RestClientResponseException exception) {
            return switch (exception.getStatusCode().value()) {
                case 404 -> Optional.empty();
                case 401 -> throw failure(ErrorCode.UNAUTHENTICATED, exception);
                case 403 -> throw failure(ErrorCode.ACCESS_DENIED, exception);
                default -> throw failure(exception.getStatusCode().is5xxServerError()
                        ? ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE : ErrorCode.INVALID_CUSTOMER_RESPONSE, exception);
            };
        } catch (ResourceAccessException exception) {
            throw failure(isTimeout(exception) ? ErrorCode.UPSTREAM_TIMEOUT
                    : ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE, exception);
        } catch (RestClientException exception) {
            throw failure(ErrorCode.INVALID_CUSTOMER_RESPONSE, exception);
        }
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException failure(ErrorCode errorCode, Exception cause) {
        BusinessException exception = new BusinessException(errorCode);
        exception.initCause(cause);
        return exception;
    }
}
