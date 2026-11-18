package thitkho.corebankingservice.common.dto.account;

import thitkho.corebankingservice.model.enums.AccountStatus;

import java.util.UUID;

public record AccountBalanceResponse(UUID accountId, String currency, AccountStatus status,
                                     String balanceMinor, int minorUnit) {
}
