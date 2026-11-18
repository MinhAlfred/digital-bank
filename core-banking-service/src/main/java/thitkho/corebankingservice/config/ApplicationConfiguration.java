package thitkho.corebankingservice.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapperBuilderCustomizer strictRequestInputs() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .withCoercionConfig(LogicalType.Textual, coercion -> {
                    coercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    coercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                });
    }
}
