package thitkho.customerservice.common.dto.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @Pattern(regexp = "^\\+?[0-9]{8,15}$") String phone) {

    public CreateCustomerRequest {
        fullName = fullName == null ? null : fullName.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        phone = phone == null || phone.isBlank() ? null : phone.trim();
    }
}
