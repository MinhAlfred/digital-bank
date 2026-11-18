package thitkho.gatewayservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import thitkho.gatewayservice.common.constant.TestSecurityConstants;
import thitkho.gatewayservice.util.JwtUtil;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayConfigurationTest {
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayConfiguration.class).withBean(JwtUtil.class)
            .withPropertyValues("app.security.jwt.secret=" + TestSecurityConstants.SECRET,
                    "app.security.jwt.issuer=" + TestSecurityConstants.ISSUER,
                    "app.security.jwt.audience=" + TestSecurityConstants.AUDIENCE,
                    "gateway.internal-secret=test-only-internal-secret");

    @Test
    void configuration_validSettings_registersJwtValidatorAndRedactsSecrets() {
        applicationContextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtUtil.class);
            assertThat(context.getBean(JwtProperties.class).toString()).doesNotContain(TestSecurityConstants.SECRET);
            assertThat(context.getBean(GatewayProperties.class).toString()).doesNotContain("test-only-internal-secret");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.security.jwt.secret=short", "app.security.jwt.issuer=", "app.security.jwt.audience=",
            "gateway.internal-secret="})
    void configuration_invalidSecuritySettings_failsAtStartup(String setting) {
        applicationContextRunner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }
}
