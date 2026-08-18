package io.llmnote.world;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-18 C 阶段:visualDescriptor backfill 单元测试。
 * 验证:已有 visualDescriptor 的 agent 跳过(节省 LLM);空 visualDescriptor 才调 LLM。
 */
@ExtendWith(MockitoExtension.class)
class AgentVisualDescriptorServiceTest {

    @Mock AgentEmployeeRepository employeeRepo;
    @Mock ChatModelFactory modelFactory;
    @InjectMocks AgentVisualDescriptorService service;

    private static ChatResponse textResp(String s) {
        return ChatResponse.builder()
                .id("test")
                .content(List.of(TextBlock.builder().text(s).build()))
                .build();
    }

    @Test
    void backfillAll_skipsAgentsWithExistingDescriptor() {
        AgentEmployee e1 = new AgentEmployee();
        e1.setId(1L);
        e1.setName("老王");
        e1.setPersona("果断镇长");
        e1.setVisualDescriptor("an old Chinese mayor, white hair, dark suit");
        AgentEmployee e2 = new AgentEmployee();
        e2.setId(2L);
        e2.setName("小美");
        e2.setPersona("杂货铺老板");
        e2.setVisualDescriptor(null);
        when(employeeRepo.findAll()).thenReturn(List.of(e1, e2));
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("a young woman with short bob, denim jacket")));

        int n = service.backfillAll();

        ArgumentCaptor<AgentEmployee> cap = ArgumentCaptor.forClass(AgentEmployee.class);
        verify(employeeRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(2L);
        assertThat(cap.getValue().getVisualDescriptor()).contains("young woman");
        assertThat(n).isEqualTo(1);
    }

    @Test
    void backfillAll_zeroAgentsWithEmptyDescriptors() {
        AgentEmployee e1 = new AgentEmployee();
        e1.setId(1L);
        e1.setName("老王");
        e1.setVisualDescriptor("already has one");
        when(employeeRepo.findAll()).thenReturn(List.of(e1));

        int n = service.backfillAll();

        assertThat(n).isEqualTo(0);
        verify(employeeRepo, never()).save(any());
        verify(modelFactory, never()).streamTextWithFallback(anyString(), any());
    }

    @Test
    void backfillOnStartup_doesNotThrow_whenRepoIsEmpty() {
        when(employeeRepo.findAll()).thenReturn(List.of());
        service.backfillOnStartup();
    }
}

