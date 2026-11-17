package thitkho.customerservice.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {
    @Test
    void codes_areNonBlankAndUnique() {
        assertThat(ErrorCode.values()).extracting(ErrorCode::getCode)
                .doesNotHaveDuplicates().allSatisfy(code -> assertThat(code).isNotBlank());
    }
}
