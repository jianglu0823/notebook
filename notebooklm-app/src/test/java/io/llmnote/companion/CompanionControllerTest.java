package io.llmnote.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.auth.ApiExceptionHandler;
import io.llmnote.auth.CurrentPrincipalHolder;
import io.llmnote.auth.ForbiddenException;
import io.llmnote.auth.Principal;
import io.llmnote.auth.PrincipalArgumentResolver;
import io.llmnote.companion.dto.CompanionCreateRequest;
import io.llmnote.companion.dto.CompanionDto;
import io.llmnote.companion.dto.CompanionTemplateDto;
import io.llmnote.companion.dto.MemoryDto;
import io.llmnote.companion.dto.MessageDto;
import io.llmnote.companion.dto.ModelDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CompanionControllerTest {

    @Mock CompanionService companionService;
    @Mock CompanionMessageService messageService;
    @Mock CompanionModelService modelService;
    @Mock CompanionUploadService uploadService;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CompanionController(companionService, messageService, modelService, uploadService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalArgumentResolver())
                .build();
        CurrentPrincipalHolder.set(new Principal("u:1", false, 1L));
    }

    @AfterEach
    void clear() {
        CurrentPrincipalHolder.clear();
    }

    @Test
    void list_returns200WithEmptyArray() throws Exception {
        when(companionService.list("u:1")).thenReturn(List.of());
        mvc.perform(get("/api/companion"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void get_otherUsers_returns404() throws Exception {
        when(companionService.get("u:1", 7L)).thenThrow(new ForbiddenException("not found"));
        mvc.perform(get("/api/companion/7"))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        CompanionDto dto = new CompanionDto(11L, "小米", "FEMALE", 22,
                List.of("GENTLE"), "bio", "SWEET", "INTIMATE", 30, "CASUAL", "story",
                "glm-4.7-flash", "ACTIVE", 0, null, null, null);
        when(companionService.create(eq("u:1"), any(CompanionCreateRequest.class))).thenReturn(dto);

        mvc.perform(post("/api/companion")
                        .contentType("application/json")
                        .content("{\"name\":\"小米\",\"gender\":\"FEMALE\",\"age\":22,\"personalityTags\":[\"GENTLE\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.name").value("小米"));
    }

    @Test
    void create_missingName_returns400() throws Exception {
        when(companionService.create(eq("u:1"), any(CompanionCreateRequest.class)))
                .thenThrow(new IllegalArgumentException("name 不能为空"));
        mvc.perform(post("/api/companion")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_returns204() throws Exception {
        mvc.perform(delete("/api/companion/7"))
                .andExpect(status().isNoContent());
    }

    @Test
    void templates_returnsArray() throws Exception {
        when(companionService.listTemplates()).thenReturn(List.of(
                new CompanionTemplateDto("a", "a", "FEMALE", 20, List.of("GENTLE"), "b",
                        "SWEET", "INTIMATE", 30, "CASUAL", "s", "glm-4.7-flash")
        ));
        mvc.perform(get("/api/companion/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].templateKey").value("a"));
    }

    @Test
    void createFromTemplate_returns201() throws Exception {
        CompanionDto dto = new CompanionDto(8L, "元气少女", "FEMALE", 19,
                List.of("LIVELY"), "bio", "CUTE", "BESTIE", 50, "INTERNET", "s",
                "glm-4.7-flash", "ACTIVE", 0, null, null, null);
        when(companionService.createFromTemplate("u:1", "energetic_girl")).thenReturn(dto);

        mvc.perform(post("/api/companion/templates/energetic_girl"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("元气少女"));
    }

    @Test
    void listMessages_returnsArray() throws Exception {
        when(messageService.listMessages(eq("u:1"), eq(7L), eq(null), eq(null)))
                .thenReturn(List.of(new MessageDto(1L, 7L, "USER", "hi",
                        List.of(), Map.of(), LocalDateTime.now())));
        mvc.perform(get("/api/companion/7/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"));
    }

    @Test
    void patchMessage_delete_returns200() throws Exception {
        MessageDto dto = new MessageDto(1L, 7L, "USER", "x", List.of(), Map.of(), LocalDateTime.now());
        when(messageService.patchMessage(eq("u:1"), eq(7L), eq(1L), any()))
                .thenReturn(dto);
        mvc.perform(patch("/api/companion/7/messages/1")
                        .contentType("application/json")
                        .content("{\"action\":\"delete\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void patchMessage_regenerate_returns501() throws Exception {
        when(messageService.patchMessage(eq("u:1"), eq(7L), eq(1L), any()))
                .thenThrow(new UnsupportedOperationException("regenerate 通过 WS chat.regenerate 触发,尚未在 REST 启用"));
        mvc.perform(patch("/api/companion/7/messages/1")
                        .contentType("application/json")
                        .content("{\"action\":\"regenerate\"}"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void listMemories_returnsArray() throws Exception {
        when(messageService.listMemories("u:1", 7L)).thenReturn(List.of(
                new MemoryDto(1L, 7L, "用户喜欢喝咖啡", null, LocalDateTime.now())
        ));
        mvc.perform(get("/api/companion/7/memories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("用户喜欢喝咖啡"));
    }

    @Test
    void deleteMemory_returns204() throws Exception {
        mvc.perform(delete("/api/companion/7/memories/9"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getIntimacy_returnsDto() throws Exception {
        CompanionIntimacy entity = new CompanionIntimacy();
        entity.setCompanionId(7L);
        entity.setScore(50);
        entity.setDaysCount(3);
        when(messageService.getIntimacy("u:1", 7L)).thenReturn(entity);
        mvc.perform(get("/api/companion/7/intimacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.daysCount").value(3));
    }

    @Test
    void listModels_returnsArray() throws Exception {
        when(modelService.listModels()).thenReturn(List.of(
                new ModelDto("glm-4.7-flash", "智谱免费", 0, 0)
        ));
        mvc.perform(get("/api/companion/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("glm-4.7-flash"));
    }
}
