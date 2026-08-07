package io.llmnote.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.auth.ForbiddenException;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.dto.CompanionCreateRequest;
import io.llmnote.companion.dto.CompanionDto;
import io.llmnote.companion.dto.CompanionPatchRequest;
import io.llmnote.companion.dto.CompanionTemplateDto;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.llm.ChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanionServiceTest {

    @Mock CompanionRepository companionRepo;
    @Mock CompanionMessageRepository messageRepo;
    @Mock CompanionMemoryRepository memoryRepo;
    @Mock CompanionTemplateRepository templateRepo;
    @Mock ChatModelFactory modelFactory;
    @InjectMocks CompanionService service;

    ObjectMapper objectMapper;

    @BeforeEach
    void setupMapper() {
        objectMapper = new ObjectMapper();
        service = new CompanionService(companionRepo, messageRepo, memoryRepo, templateRepo,
                new CompanionMapper(objectMapper), modelFactory,
                new CompanionProperties());
    }

    @Test
    void create_validRequest_persistsAndReturnsDto() {
        when(modelFactory.normalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(companionRepo.save(any(Companion.class))).thenAnswer(inv -> {
            Companion c = inv.getArgument(0);
            c.setId(11L);
            return c;
        });

        CompanionCreateRequest req = new CompanionCreateRequest(
                "小米", "FEMALE", List.of("温柔体贴", "知性优雅"), 22, "咖啡店常客",
                "SWEET", "INTIMATE", 35, "CASUAL", "校园学姐", "glm-4.7-flash");

        CompanionDto dto = service.create("u:1", req);

        assertThat(dto.id()).isEqualTo(11L);
        assertThat(dto.name()).isEqualTo("小米");
        assertThat(dto.gender()).isEqualTo("FEMALE");
        assertThat(dto.personalityTags()).containsExactly("GENTLE", "ELEGANT");
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }

    @Test
    void create_missingName_returns400() {
        CompanionCreateRequest req = new CompanionCreateRequest(
                null, "FEMALE", null, 22, "x", "SWEET", "INTIMATE", 30, "CASUAL", "", "glm-4.7-flash");
        assertThatThrownBy(() -> service.create("u:1", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void get_otherUsersCompanion_returns404() {
        when(companionRepo.findByIdAndUserId(7L, "u:1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("u:1", 7L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void createFromTemplate_unknownKey_returns404() {
        when(templateRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createFromTemplate("u:1", "ghost"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listTemplates_returnsAllInRepoOrder() {
        CompanionTemplate t = new CompanionTemplate();
        t.setTemplateKey("energetic_girl");
        t.setName("元气少女");
        t.setGender(io.llmnote.companion.enums.Gender.FEMALE);
        t.setAge(19);
        t.setPersonalityTags("[\"LIVELY\",\"ENERGETIC\"]");
        t.setVoiceStyle(io.llmnote.companion.enums.VoiceStyle.CUTE);
        t.setRelationshipType(io.llmnote.companion.enums.RelationshipType.BESTIE);
        t.setIntimacyStart(50);
        t.setSpeakingStyle(io.llmnote.companion.enums.SpeakingStyle.INTERNET);
        t.setBackgroundStory("b");
        t.setDefaultModel("glm-4.7-flash");
        t.setBio("bio");
        when(templateRepo.findAllByOrderByNameAsc()).thenReturn(List.of(t));

        List<CompanionTemplateDto> result = service.listTemplates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).templateKey()).isEqualTo("energetic_girl");
        assertThat(result.get(0).personalityTags()).containsExactly("LIVELY", "ENERGETIC");
    }

    @Test
    void softDelete_marksStatusAndCascades() {
        Companion existing = new Companion();
        existing.setId(7L);
        existing.setUserId("u:1");
        existing.setStatus("ACTIVE");
        when(companionRepo.findByIdAndUserId(7L, "u:1")).thenReturn(Optional.of(existing));
        when(messageRepo.softDeleteByCompanionId(7L)).thenReturn(3);
        when(memoryRepo.softDeleteByCompanionId(7L)).thenReturn(2);

        service.softDelete("u:1", 7L);

        assertThat(existing.getStatus()).isEqualTo("DELETED");
        verify(messageRepo).softDeleteByCompanionId(7L);
        verify(memoryRepo).softDeleteByCompanionId(7L);
    }

    @Test
    void patch_normalizesDefaultModel() {
        Companion existing = new Companion();
        existing.setId(7L);
        existing.setUserId("u:1");
        existing.setStatus("ACTIVE");
        existing.setName("小米");
        existing.setGender(io.llmnote.companion.enums.Gender.FEMALE);
        existing.setAge(22);
        existing.setPersonalityTags("[]");
        existing.setBio("b");
        existing.setVoiceStyle(io.llmnote.companion.enums.VoiceStyle.SWEET);
        existing.setRelationshipType(io.llmnote.companion.enums.RelationshipType.INTIMATE);
        existing.setIntimacyStart(30);
        existing.setSpeakingStyle(io.llmnote.companion.enums.SpeakingStyle.CASUAL);
        existing.setBackgroundStory("s");
        existing.setDefaultModel("glm-4.7-flash");
        when(companionRepo.findByIdAndUserId(7L, "u:1")).thenReturn(Optional.of(existing));
        when(modelFactory.normalize("bogus-model")).thenReturn("glm-4.7-flash");

        CompanionPatchRequest req = new CompanionPatchRequest(
                null, null, null, null, null, null, null, null, null, null, "bogus-model");
        CompanionDto dto = service.patch("u:1", 7L, req);

        ArgumentCaptor<Companion> captor = ArgumentCaptor.forClass(Companion.class);
        verify(modelFactory).normalize("bogus-model");
        assertThat(dto.defaultModel()).isEqualTo("glm-4.7-flash");
    }
}
