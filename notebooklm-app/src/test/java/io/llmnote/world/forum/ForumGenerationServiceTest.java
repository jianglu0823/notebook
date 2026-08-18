package io.llmnote.world.forum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import io.llmnote.world.AgentAction;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.WorldSettings;
import io.llmnote.world.WorldSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ForumGenerationService 单测:验证由行动触发(而非 cron)后的核心路径。 */
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
        return new ForumGenerationService(modelFactory, employeeRepo, settingsService,
                postRepo, new ObjectMapper(), mediaService);
    }

    private static AgentAction sampleAction(AgentEmployee actor) {
        AgentAction a = new AgentAction();
        a.setId(99L);
        a.setAgentId(actor.getId());
        a.setType("goto");
        a.setContent("走到咖啡馆坐下,要了杯拿铁");
        a.setPlace("cafe");
        a.setScene("咖啡馆小憩");
        return a;
    }

    private static AgentEmployee sampleEmployee() {
        AgentEmployee a = new AgentEmployee();
        a.setId(1L);
        a.setName("alice");
        a.setMood("惬意");
        a.setPersona("程序员 / 内向 / 喜欢猫");
        return a;
    }

    @Test
    void generateFromAction_skipsWhenForumDisabled() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(false);
        when(settingsService.get()).thenReturn(s);

        service.generateFromAction(sampleEmployee(), sampleAction(sampleEmployee()));

        verify(modelFactory, never()).streamTextWithFallback(anyString(), any(), any());
        verify(postRepo, never()).save(any());
    }

    @Test
    void generateFromAction_skipsWhenDailyCapReached() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(true);
        s.setForumMaxPostsPerAgentPerDay(2);
        when(settingsService.get()).thenReturn(s);
        AgentForumPost p1 = new AgentForumPost(); p1.setCreatedAt(LocalDateTime.now());
        AgentForumPost p2 = new AgentForumPost(); p2.setCreatedAt(LocalDateTime.now());
        when(postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(p1, p2));

        service.generateFromAction(sampleEmployee(), sampleAction(sampleEmployee()));

        verify(modelFactory, never()).streamTextWithFallback(anyString(), any(), any());
        verify(postRepo, never()).save(any());
    }

    @Test
    void generateFromAction_callsLlmAndPersistsPost() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(true);
        s.setForumMaxPostsPerAgentPerDay(2);
        s.setModel("qwen-turbo");
        when(settingsService.get()).thenReturn(s);
        when(postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(modelFactory.streamTextWithFallback(anyString(), any(), any()))
                .thenReturn(List.of(textResp("[{\"category\":\"joke\",\"title\":null,\"content\":\"程序员的日常:写了一行bug,然后改了三行bug,然后又写了一行bug\"}]")));
        when(postRepo.save(any(AgentForumPost.class))).thenAnswer(inv -> {
            AgentForumPost p = inv.getArgument(0);
            p.setId(System.nanoTime());
            return p;
        });

        service.generateFromAction(sampleEmployee(), sampleAction(sampleEmployee()));

        verify(modelFactory, org.mockito.Mockito.times(1))
                .streamTextWithFallback(org.mockito.ArgumentMatchers.eq("qwen-turbo"), any(), any());
        verify(postRepo, org.mockito.Mockito.times(1)).save(any(AgentForumPost.class));
        verify(mediaService, org.mockito.Mockito.times(1)).generateAttachments(any(AgentForumPost.class));
    }

    @Test
    void generateFromAction_emptyArrayResponse_persistsNothing() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(true);
        s.setForumMaxPostsPerAgentPerDay(2);
        s.setModel("qwen-turbo");
        when(settingsService.get()).thenReturn(s);
        when(postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(modelFactory.streamTextWithFallback(anyString(), any(), any()))
                .thenReturn(List.of(textResp("[]")));

        service.generateFromAction(sampleEmployee(), sampleAction(sampleEmployee()));

        verify(postRepo, never()).save(any());
        verify(mediaService, never()).generateAttachments(any());
    }

    @Test
    void generateFromAction_invalidJsonResponse_persistsNothing() {
        service = freshService();
        WorldSettings s = new WorldSettings();
        s.setForumEnabled(true);
        s.setForumMaxPostsPerAgentPerDay(2);
        s.setModel("qwen-turbo");
        when(settingsService.get()).thenReturn(s);
        when(postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(modelFactory.streamTextWithFallback(anyString(), any(), any()))
                .thenReturn(List.of(textResp("not json at all")));

        service.generateFromAction(sampleEmployee(), sampleAction(sampleEmployee()));

        verify(postRepo, never()).save(any());
    }

    @Test
    void buildActionPrompt_includesActionContextPlaceAndMood() {
        service = freshService();
        AgentEmployee a = sampleEmployee();
        AgentAction act = sampleAction(a);
        act.setType("goto");
        act.setPlace("cafe");

        var pair = service.buildActionPrompt(a, act, java.util.Map.of("joke", 5L), 1);

        assertThat(pair.system())
                .contains("joke", "acrostic", "daily", "chicken_soup", "video_series", "utility")
                .contains("个性", "内涵", "自主性", "行动一致性")
                .contains("作为一个人工智能", "很高兴为您", "让我们一起")
                .contains("alice"); // agent name 在 system 段(人设)
        assertThat(pair.user())
                .contains("咖啡馆")
                .contains("走到咖啡馆坐下,要了杯拿铁")
                .contains("惬意")
                .contains("咖啡馆小憩");
    }

    @Test
    void buildActionPrompt_talkActionIncludesTargetAgent() {
        service = freshService();
        AgentEmployee a = sampleEmployee();
        AgentAction act = sampleAction(a);
        act.setType("talk");
        act.setPlace("plaza");
        act.setTargetAgentId(2L);
        AgentEmployee bob = new AgentEmployee();
        bob.setId(2L);
        bob.setName("bob");
        when(employeeRepo.findById(2L)).thenReturn(java.util.Optional.of(bob));

        var pair = service.buildActionPrompt(a, act, java.util.Map.of(), 1);

        assertThat(pair.user())
                .contains("bob")
                .contains("古井广场");
    }
}
