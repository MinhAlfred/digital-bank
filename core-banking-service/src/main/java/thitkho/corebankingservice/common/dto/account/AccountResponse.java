package thitkho.corebankingservice.common.dto.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccountResponse(UUID id, String accountNumber, UUID customerId, AccountType accountType,
                              ProductType productType, String currency, AccountStatus status, String balanceMinor,
                              int minorUnit, Instant createdAt, Instant updatedAt, Instant closedAt) {
}
