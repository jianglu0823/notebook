package io.llmnote.world.forum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.WorldSettings;
import io.llmnote.world.WorldSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B2 T11~T14 单元测试:ForumGenerationService 编排 LLM 调用 + 持久化。 */
@ExtendWith(MockitoExtension.class)
class ForumGenerationServiceTest {

    @Mock ChatModelFactory modelFactory;
    @Mock AgentEmployeeRepository employeeRepo;
    @Mock WorldSettingsService settingsService;
    @Mock AgentForumPostRepository postRepo;
    @Mock ForumMediaService mediaService;
    ForumGenerationService service;

    private static ChatResponse textResp(String s) {
        return ChatResponse.builder()
                .id("test")
                .content(List.of(TextBlock.builder().text(s).build()))
                .build();
    }

    private ForumGenerationService freshService() {
        return new ForumGenerationService(modelFactory, employeeRepo, settingsService, postRepo, new ObjectMapper(), mediaService);
    }

    @Test
    void generateDailyPosts_skipsWhenForumDisabled() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(false);
        when(settingsService.get()).thenReturn(s);

        service.generateDailyPosts();

        verify(modelFactory, never()).streamTextWithFallback(anyString(), any(), any());
        verify(postRepo, never()).save(any());
    }

    @Test
    void generateDailyPosts_callsLlmOncePerActiveAgent() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(true);
        s.setForumMaxPostsPerAgentPerDay(2);
        when(settingsService.get()).thenReturn(s);

        AgentEmployee a1 = new AgentEmployee(); a1.setId(1L); a1.setName("alice");
        AgentEmployee a2 = new AgentEmployee(); a2.setId(2L); a2.setName("bob");
        when(employeeRepo.findByStatusOrderByIdAsc("active")).thenReturn(List.of(a1, a2));

        when(modelFactory.defaultTextModel()).thenReturn("qwen-plus");
        when(modelFactory.streamTextWithFallback(anyString(), any(), any()))
                .thenReturn(List.of(textResp("[{\"category\":\"joke\",\"content\":\"程序员的日常:写了一行bug,然后改了三行bug,然后又写了一行bug\",\"title\":null}]")));
        when(postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        when(postRepo.save(any(AgentForumPost.class))).thenAnswer(inv -> {
            AgentForumPost p = inv.getArgument(0);
            p.setId(System.nanoTime());
            return p;
        });

        service.generateDailyPosts();

        verify(modelFactory, org.mockito.Mockito.times(2))
                .streamTextWithFallback(anyString(), any(), any());
    }

    @Test
    void parseResponse_dropsInvalidJson() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(true);
        when(settingsService.get()).thenReturn(s);
        AgentEmployee a1 = new AgentEmployee(); a1.setId(1L); a1.setName("alice");
        when(employeeRepo.findByStatusOrderByIdAsc("active")).thenReturn(List.of(a1));

        when(modelFactory.defaultTextModel()).thenReturn("qwen-plus");
        when(modelFactory.streamTextWithFallback(anyString(), any(), any()))
                .thenReturn(List.of(textResp("not json at all")));
        when(postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        service.generateDailyPosts();

        verify(postRepo, never()).save(any());
    }

    @Test
    void buildPrompt_systemContainsAllSixCategoriesAndClicheBlacklist() {
        service = freshService();
        AgentEmployee a = new AgentEmployee();
        a.setId(1L);
        a.setName("alice");
        a.setPersona("程序员 / 内向 / 喜欢猫");

        var pair = service.buildPrompt(a, java.util.Map.of("joke", 5L), 3);

        assertThat(pair.system())
                .contains("joke", "acrostic", "daily", "chicken_soup", "video_series", "utility")
                .contains("个性", "内涵", "自主性")
                .contains("作为一个人工智能", "很高兴为您", "让我们一起");
        assertThat(pair.user()).contains("alice").contains("3");
    }
}
