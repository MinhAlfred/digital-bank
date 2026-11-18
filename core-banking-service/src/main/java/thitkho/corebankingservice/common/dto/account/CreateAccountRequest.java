package thitkho.corebankingservice.common.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import thitkho.corebankingservice.common.constant.AccountApiConstants;
import thitkho.corebankingservice.model.enums.ProductType;

public record CreateAccountRequest(
        @NotNull ProductType productType,
        @NotBlank @Pattern(regexp = AccountApiConstants.CURRENCY_PATTERN) String currency) {
}
