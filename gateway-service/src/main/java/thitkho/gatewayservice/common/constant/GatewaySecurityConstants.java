package thitkho.gatewayservice.common.constant;

public final class GatewaySecurityConstants {
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final int MAX_SUBJECT_LENGTH = 100;
    public static final long CLOCK_SKEW_SECONDS = 60;

    private GatewaySecurityConstants() {
    }
}
