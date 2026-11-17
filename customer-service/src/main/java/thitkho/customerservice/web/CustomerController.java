package thitkho.customerservice.web;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thitkho.customerservice.common.dto.customer.CreateCustomerRequest;
import thitkho.customerservice.common.dto.customer.CustomerResponse;
import thitkho.customerservice.service.CustomerService;

import java.net.URI;

@RestController
@RequestMapping("/api/customers")
class CustomerController {
    private final CustomerService customerService;

    CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    ResponseEntity<CustomerResponse> create(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody CreateCustomerRequest request) {
        return ResponseEntity.created(URI.create("/api/customers/me")).cacheControl(CacheControl.noStore())
                .body(customerService.create(jwt.getSubject(), request));
    }

    @GetMapping("/me")
    ResponseEntity<CustomerResponse> getCurrent(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(customerService.getCurrent(jwt.getSubject()));
    }
}
