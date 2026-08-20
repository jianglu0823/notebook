package io.llmnote.world;

import io.llmnote.world.config.WorldChildrenProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * town-children-rules W2-T5:WorldSimEngine.coupleAtCap 的单元测试。
 * 验证 R-2 各 scenario(cap 后不再怀上 / 不足 cap 正常 / cap=1 立即生效 / 单亲)。
 */
@ExtendWith(MockitoExtension.class)
class WorldSimEngineCoupleCapTest {

    @Mock AgentEmployeeRepository employeeRepo;

    WorldSimEngine engine;
    WorldChildrenProperties props;

    @BeforeEach
    void setUp() {
        props = new WorldChildrenProperties();
        props.setMaxPerCouple(3);
        // engine 只用 coupleAtCap(employeeRepo + childrenProps),其它依赖传 null
        // 14 final 字段:employeeRepo, productRepo, reportRepo, memoryService, modelFactory,
        // objectMapper, relationshipRepo, employeeService, mediaClient, mediaRunner,
        // commentRepo, economyService, stockMarketService, childrenProps
        engine = new WorldSimEngine(employeeRepo, null, null, null, null, null, null, null, null,
                null, null, null, null, props);
    }

    @Test
    void coupleAtCap_doesNotConceive() {
        AgentEmployee a = newResident(10L, "老王");
        AgentEmployee b = newResident(11L, "小美");
        // 已有 3 个孩子,parent_ids 都同时含 10 和 11
        when(employeeRepo.findByParentIdsContaining("10"))
                .thenReturn(List.of(child(20L, "10,11"), child(21L, "11,10"), child(22L, "10,11")));
        assertThat(engine.coupleAtCap(a, b)).isTrue();
    }

    @Test
    void coupleAtCap_belowCap_conceivesAsBefore() {
        AgentEmployee a = newResident(10L, "老王");
        AgentEmployee b = newResident(11L, "小美");
        when(employeeRepo.findByParentIdsContaining("10"))
                .thenReturn(List.of(child(20L, "10,11"), child(21L, "10,11"))); // 仅 2 个
        assertThat(engine.coupleAtCap(a, b)).isFalse();
    }

    @Test
    void coupleAtCap_capOne_takesEffectImmediately() {
        props.setMaxPerCouple(1);
        AgentEmployee a = newResident(10L, "老王");
        AgentEmployee b = newResident(11L, "小美");
        when(employeeRepo.findByParentIdsContaining("10"))
                .thenReturn(List.of(child(20L, "10,11"))); // 1 个就到 cap
        assertThat(engine.coupleAtCap(a, b)).isTrue();
    }

    @Test
    void coupleAtCap_singleParentCountsTowardCap() {
        // R-2 "Single parent counts toward cap":parent_ids="a,a" 也算 a 的孩子
        AgentEmployee a = newResident(10L, "老王");
        AgentEmployee b = newResident(10L, "老王"); // 同 id 模拟单亲
        when(employeeRepo.findByParentIdsContaining("10"))
                .thenReturn(List.of(child(20L, "10,10"), child(21L, "10,10"), child(22L, "10,10")));
        assertThat(engine.coupleAtCap(a, b)).isTrue();
    }

    @Test
    void coupleAtCap_handlesTrailingCommaInParentIds() {
        // birth() 写入的是 a + "," + b,可能产生 "10,11,"(虽然实测不产生,但 split 需健壮)
        AgentEmployee a = newResident(10L, "老王");
        AgentEmployee b = newResident(11L, "小美");
        when(employeeRepo.findByParentIdsContaining("10"))
                .thenReturn(List.of(child(20L, "10,11,"), child(21L, ",10,11"), child(22L, "10,11")));
        // 3 个都同时含 10,11(过滤空串后)
        assertThat(engine.coupleAtCap(a, b)).isTrue();
    }

    @Test
    void coupleAtCap_ignoresChildrenFromOtherCouples() {
        // 一些孩子是其他夫妇的(只有 a,没有 b),不应算入这对夫妇的 cap
        AgentEmployee a = newResident(10L, "老王");
        AgentEmployee b = newResident(11L, "小美");
        when(employeeRepo.findByParentIdsContaining("10"))
                .thenReturn(List.of(
                        child(20L, "10,99"),  // 含 a 但不含 b → 跳过
                        child(21L, "11,99"),  // 都不含 → 跳过
                        child(22L, "10,11"),  // 1 个匹配
                        child(23L, "10,11"))); // 1 个匹配
        // 仅 2 个匹配,cap=3 未达
        assertThat(engine.coupleAtCap(a, b)).isFalse();
    }

    private static AgentEmployee newResident(Long id, String name) {
        AgentEmployee e = new AgentEmployee();
        e.setId(id);
        e.setName(name);
        return e;
    }

    private static AgentEmployee child(Long id, String parentIds) {
        AgentEmployee c = new AgentEmployee();
        c.setId(id);
        c.setParentIds(parentIds);
        return c;
    }
}
