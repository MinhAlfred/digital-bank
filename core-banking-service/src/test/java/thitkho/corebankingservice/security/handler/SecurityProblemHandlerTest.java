package thitkho.corebankingservice.security.handler;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import thitkho.corebankingservice.common.web.ProblemDetailFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityProblemHandlerTest {
    @Test
    void accessDenied_returnsGeneric403WithoutSensitiveExceptionDetail() throws Exception {
        SecurityProblemHandler securityProblemHandler = new SecurityProblemHandler(
                new ProblemDetailFactory(Clock.systemUTC()), JsonMapper.builder().build());
        MockHttpServletResponse mockHttpServletResponse = new MockHttpServletResponse();

        securityProblemHandler.handle(new MockHttpServletRequest(), mockHttpServletResponse,
                new AccessDeniedException("private authorization detail"));

        assertThat(mockHttpServletResponse.getStatus()).isEqualTo(403);
        assertThat(mockHttpServletResponse.getContentAsString()).contains("ACCESS_DENIED", "Forbidden")
                .doesNotContain("private authorization detail");
    }
}
