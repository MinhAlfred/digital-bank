package thitkho.corebankingservice.web;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thitkho.corebankingservice.common.constant.AccountApiConstants;
import thitkho.corebankingservice.common.dto.account.AccountBalanceResponse;
import thitkho.corebankingservice.common.dto.account.AccountResponse;
import thitkho.corebankingservice.common.dto.account.CreateAccountRequest;
import thitkho.corebankingservice.service.AccountService;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(AccountApiConstants.BASE_PATH)
class AccountController {
    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse accountResponse = accountService.openAccount(request.productType(), request.currency(), jwt.getTokenValue());
        return ResponseEntity.created(URI.create(AccountApiConstants.BASE_PATH + "/" + accountResponse.id()))
                .cacheControl(CacheControl.noStore()).body(accountResponse);
    }

    @GetMapping(AccountApiConstants.ACCOUNT_PATH)
    ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(accountService.getOwnedAccount(accountId, jwt.getTokenValue()));
    }

    @GetMapping(AccountApiConstants.BALANCE_PATH)
    ResponseEntity<AccountBalanceResponse> getBalance(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(accountService.getOwnedAccountBalance(accountId, jwt.getTokenValue()));
    }
}
