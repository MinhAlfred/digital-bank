package thitkho.corebankingservice.common.constant;

public final class AccountApiConstants {
    public static final String BASE_PATH = "/api/accounts";
    public static final String ACCOUNT_PATH = "/{accountId}";
    public static final String BALANCE_PATH = "/{accountId}/balance";
    public static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

    private AccountApiConstants() {
    }
}
