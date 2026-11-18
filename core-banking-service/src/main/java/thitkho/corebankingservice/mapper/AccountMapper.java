package thitkho.corebankingservice.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import thitkho.corebankingservice.common.dto.account.AccountBalanceResponse;
import thitkho.corebankingservice.common.dto.account.AccountResponse;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.Currency;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AccountMapper {
    @Mapping(target = "currency", source = "account.currency")
    @Mapping(target = "balanceMinor", source = "account.balance")
    @Mapping(target = "minorUnit", source = "currency.minorUnit")
    AccountResponse toResponse(Account account, Currency currency);

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "currency", source = "account.currency")
    @Mapping(target = "balanceMinor", source = "account.balance")
    @Mapping(target = "minorUnit", source = "currency.minorUnit")
    AccountBalanceResponse toBalanceResponse(Account account, Currency currency);
}
