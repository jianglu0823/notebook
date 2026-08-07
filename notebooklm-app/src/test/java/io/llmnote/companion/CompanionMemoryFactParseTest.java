package io.llmnote.companion;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionMemoryFactParseTest {

    @Test
    @SuppressWarnings("unchecked")
    void parseFacts_handlesListFormat() throws Exception {
        Method m = CompanionMemoryService.class.getDeclaredMethod("parseFacts", String.class);
        m.setAccessible(true);
        Object out = m.invoke(new CompanionMemoryService(null, null, null, null, null, null, null),
                "- 用户在上海工作。\n- 用户养了一只猫\n- 用户喜欢喝咖啡");
        List<String> facts = (List<String>) out;
        assertThat(facts).hasSize(3);
        assertThat(facts).anyMatch(f -> f.contains("上海"));
        assertThat(facts).anyMatch(f -> f.contains("猫"));
        assertThat(facts).anyMatch(f -> f.contains("咖啡"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseFacts_filtersNONE() throws Exception {
        Method m = CompanionMemoryService.class.getDeclaredMethod("parseFacts", String.class);
        m.setAccessible(true);
        Object out = m.invoke(new CompanionMemoryService(null, null, null, null, null, null, null), "NONE");
        List<String> facts = (List<String>) out;
        assertThat(facts).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseFacts_limitsTo3() throws Exception {
        Method m = CompanionMemoryService.class.getDeclaredMethod("parseFacts", String.class);
        m.setAccessible(true);
        Object out = m.invoke(new CompanionMemoryService(null, null, null, null, null, null, null),
                "- fact 1\n- fact 2\n- fact 3\n- fact 4\n- fact 5");
        List<String> facts = (List<String>) out;
        assertThat(facts).hasSize(3);
    }
}
