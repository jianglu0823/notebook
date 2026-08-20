package io.llmnote.world;

import io.llmnote.world.config.WorldChildrenProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * town-children-rules W2-T6:AgentEmployeeService.birth() 集成测试。
 * 验证传入的 avatar 参数被忽略,真实写入的是 props.pickAvatar(name) 的结果。
 */
@ExtendWith(MockitoExtension.class)
class AgentEmployeeServiceBirthAvatarTest {

    @Mock AgentEmployeeRepository repo;
    @Mock io.llmnote.world.EmployeeAgentFactory agentFactory;
    @Mock io.llmnote.world.AgentMemoryRepository memoryRepo;
    @Mock io.llmnote.auth.UserRepository userRepo;
    @Mock io.llmnote.llm.ChatModelFactory modelFactory;

    @Test
    void birth_writesPoolAvatar_ignoresCallerParam() {
        WorldChildrenProperties props = new WorldChildrenProperties();
        // 自定义池只 2 个值,断言可预期
        props.setAvatarPool(List.of("🐣", "🐤"));

        AgentEmployeeService svc = new AgentEmployeeService(repo, agentFactory, memoryRepo, userRepo,
                modelFactory, props);
        when(repo.count()).thenReturn(0L);
        when(repo.save(any(AgentEmployee.class))).thenAnswer(inv -> inv.getArgument(0));

        // 调用时传 "硬塞的旧值" 作为 avatar,期望被忽略
        AgentEmployee saved = svc.birth("墨小宝", "👶_IGNORED", "persona text",
                "plaza", 10L, 11L, LocalDate.of(2026, 1, 1));

        // avatar 不应是传入的 IGNORED,应是池中之一
        assertThat(saved.getAvatar()).isIn("🐣", "🐤");
        assertThat(saved.getAvatar()).isNotEqualTo("👶_IGNORED");
    }

    @Test
    void birth_poolAvatar_isDeterministicForSameName() {
        WorldChildrenProperties props = new WorldChildrenProperties();
        props.setAvatarPool(List.of("🐣", "🐤", "🐥"));
        AgentEmployeeService svc = new AgentEmployeeService(repo, agentFactory, memoryRepo, userRepo,
                modelFactory, props);
        when(repo.count()).thenReturn(0L);
        when(repo.save(any(AgentEmployee.class))).thenAnswer(inv -> inv.getArgument(0));

        // 同名 3 次,应得到同一 avatar(确定性)
        String first = svc.birth("同名孩子", "x", "p", "plaza", 1L, 2L, LocalDate.now()).getAvatar();
        String second = svc.birth("同名孩子", "x", "p", "plaza", 1L, 2L, LocalDate.now()).getAvatar();
        String third = svc.birth("同名孩子", "x", "p", "plaza", 1L, 2L, LocalDate.now()).getAvatar();
        assertThat(first).isEqualTo(second).isEqualTo(third);
        assertThat(List.of("🐣", "🐤", "🐥")).contains(first);
    }

    @Test
    void birth_emptyPoolFallsBackToBabyEmoji() {
        WorldChildrenProperties props = new WorldChildrenProperties();
        props.setAvatarPool(List.of());
        AgentEmployeeService svc = new AgentEmployeeService(repo, agentFactory, memoryRepo, userRepo,
                modelFactory, props);
        when(repo.count()).thenReturn(0L);
        when(repo.save(any(AgentEmployee.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentEmployee saved = svc.birth("某孩子", "x", "p", "plaza", 1L, 2L, LocalDate.now());
        assertThat(saved.getAvatar()).isEqualTo("👶");
    }

    @Test
    void birth_savedAgentHasParentIdsSetCorrectly() {
        // 顺手验证 birth() 没把别的字段搞坏(R-1 / R-2 都不要求改 parentIds)
        WorldChildrenProperties props = new WorldChildrenProperties();
        AgentEmployeeService svc = new AgentEmployeeService(repo, agentFactory, memoryRepo, userRepo,
                modelFactory, props);
        when(repo.count()).thenReturn(0L);
        ArgumentCaptor<AgentEmployee> captor = ArgumentCaptor.forClass(AgentEmployee.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        AgentEmployee saved = svc.birth("墨小宝", "x", "p", "plaza", 10L, 11L, LocalDate.of(2026, 1, 1));
        assertThat(saved.getName()).isEqualTo("墨小宝");
        assertThat(saved.getParentIds()).isEqualTo("10,11");
        assertThat(saved.getTitle()).isEqualTo("镇上的孩子");
    }
}
