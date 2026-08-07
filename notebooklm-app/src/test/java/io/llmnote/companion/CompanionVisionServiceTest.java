package io.llmnote.companion;

import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.llm.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CompanionVisionServiceTest {

    @Mock ChatModelFactory modelFactory;
    CompanionProperties properties = new CompanionProperties();
    CompanionVisionService service = new CompanionVisionService(modelFactory, properties);

    @Test
    void glmDefaultPicksGlmVision() {
        assertThat(service.pickVisionModel("glm-4.7-flash")).isEqualTo("glm-4.6v-flash");
    }

    @Test
    void qwenDefaultPicksQwenVision() {
        assertThat(service.pickVisionModel("qwen-plus")).isEqualTo("qwen-vl-max");
    }

    @Test
    void unknownFallsBackToFirstConfigured() {
        assertThat(service.pickVisionModel("gpt-4")).isEqualTo("glm-4.6v-flash");
    }
}
