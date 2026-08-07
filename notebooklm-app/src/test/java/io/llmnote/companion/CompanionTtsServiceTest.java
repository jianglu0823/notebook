package io.llmnote.companion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionTtsServiceTest {

    @Test
    void split_chinesePeriods() {
        List<String> s = CompanionTtsService.split("你好。今天天气真好!我们去散步吧?");
        assertThat(s).containsExactly("你好。", "今天天气真好!", "我们去散步吧?");
    }

    @Test
    void split_englishPunctuation() {
        List<String> s = CompanionTtsService.split("Hello! How are you? Fine.");
        assertThat(s).containsExactly("Hello!", "How are you?", "Fine.");
    }

    @Test
    void split_newlinesTreatedAsBoundary() {
        List<String> s = CompanionTtsService.split("第一行\n第二行\n第三行");
        assertThat(s).containsExactly("第一行", "第二行", "第三行");
    }

    @Test
    void split_empty() {
        assertThat(CompanionTtsService.split("")).isEmpty();
        assertThat(CompanionTtsService.split(null)).isEmpty();
    }
}
