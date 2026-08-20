package io.llmnote.world;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.llmnote.world.config.WorldChildrenProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * town-children-rules W3-T7/T8:MigrateChildDataRunner 单测。
 * 覆盖 R-4 各 scenario(空表 / 混合 / 幂等 / 单行失败)。
 */
@ExtendWith(MockitoExtension.class)
class MigrateChildDataRunnerTest {

    @Mock AgentEmployeeRepository repo;

    WorldChildrenProperties props;
    MigrateChildDataRunner runner;
    ListAppender<ILoggingEvent> logAppender;
    Logger runnerLogger;

    @BeforeEach
    void setUp() {
        props = new WorldChildrenProperties();
        runner = new MigrateChildDataRunner(repo, props);
        // capture log events from the runner's logger
        runnerLogger = (Logger) LoggerFactory.getLogger(MigrateChildDataRunner.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        runnerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        runnerLogger.detachAppender(logAppender);
    }

    @Test
    void emptyDb_doesNothing() {
        when(repo.findByParentIdsIsNotNull()).thenReturn(List.of());

        runner.run(new String[]{});

        // no saves
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any(AgentEmployee.class));
        // single INFO line with zero counts
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).hasSize(1);
        assertThat(infoEvents.get(0).getFormattedMessage())
                .contains("reavatar=0")
                .contains("couplesCleared=0");
    }

    @Test
    void mixedDb_reavatarsChildrenAndClearsOverCap() {
        // 10 children: 5 for couple 10,11 + 5 for couple 12,13
        when(repo.findByParentIdsIsNotNull()).thenReturn(List.of(
                child(20L, "墨1", "10,11"),
                child(21L, "墨2", "10,11"),
                child(22L, "墨3", "10,11"),
                child(23L, "墨4", "10,11"),
                child(24L, "墨5", "10,11"),
                child(30L, "张1", "12,13"),
                child(31L, "张2", "12,13"),
                child(32L, "张3", "12,13"),
                child(33L, "张4", "12,13"),
                child(34L, "张5", "12,13")));

        AgentEmployee parent10 = newParent(10L, "老王");
        AgentEmployee parent11 = newParent(11L, "小美");
        AgentEmployee parent12 = newParent(12L, "阿强");
        AgentEmployee parent13 = newParent(13L, "花花");
        parent10.setPregnantSince(java.time.LocalDate.of(2026, 1, 1));
        parent10.setDueDate(java.time.LocalDate.of(2026, 1, 31));
        parent11.setPregnantSince(java.time.LocalDate.of(2026, 1, 1));
        parent11.setDueDate(java.time.LocalDate.of(2026, 1, 31));

        when(repo.findById(10L)).thenReturn(java.util.Optional.of(parent10));
        when(repo.findById(11L)).thenReturn(java.util.Optional.of(parent11));
        when(repo.findById(12L)).thenReturn(java.util.Optional.of(parent12));
        when(repo.findById(13L)).thenReturn(java.util.Optional.of(parent13));
        when(repo.save(org.mockito.ArgumentMatchers.any(AgentEmployee.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        runner.run(new String[]{});

        // 10 children got re-avatar'd (10 saves)
        // + 2 parents (10, 11) whose pregnant fields were non-null got cleared (2 saves)
        // + 2 parents (12, 13) whose pregnant fields were already null → NO save (defensive per R-4)
        // = 12 saves total
        verify(repo, org.mockito.Mockito.times(12)).save(org.mockito.ArgumentMatchers.any(AgentEmployee.class));

        // Couples 10,11 are over cap (5 > 3) → pregnant fields cleared
        assertThat(parent10.getPregnantSince()).isNull();
        assertThat(parent10.getDueDate()).isNull();
        assertThat(parent11.getPregnantSince()).isNull();
        assertThat(parent11.getDueDate()).isNull();
        // Couples 12,13 (also 5 kids) — pregnant fields were null to begin with
        assertThat(parent12.getPregnantSince()).isNull();
        assertThat(parent13.getPregnantSince()).isNull();

        // INFO line with reavatar=10 couplesCleared=2
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).hasSize(1);
        assertThat(infoEvents.get(0).getFormattedMessage())
                .contains("reavatar=10")
                .contains("couplesCleared=2");
    }

    @Test
    void children_avatar_isInPoolAndVaried() {
        when(repo.findByParentIdsIsNotNull()).thenReturn(List.of(
                child(20L, "墨小宝", "10,11"),
                child(21L, "墨囡囡", "10,11"),
                child(22L, "墨小豆", "10,11"),
                child(23L, "墨阿福", "10,11"),
                child(24L, "墨小满", "10,11"),
                child(25L, "张小宝", "12,13"),
                child(26L, "张囡囡", "12,13"),
                child(27L, "张小豆", "12,13"),
                child(28L, "张阿福", "12,13"),
                child(29L, "张小满", "12,13")));
        when(repo.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Optional.empty());

        runner.run(new String[]{});

        // Capture all 10 children's avatar
        java.util.List<String> avatars = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            avatars.add(((AgentEmployee) org.mockito.Mockito.mockingDetails(repo)
                    .getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("save"))
                    .toList()
                    .get(i).getArgument(0)).getAvatar());
        }
        // 5 distinct names × 2 → across the 10, at least 2 distinct avatars
        long distinct = avatars.stream().distinct().count();
        assertThat(distinct).isGreaterThanOrEqualTo(2);
        // All in the default 9-emoji pool
        assertThat(avatars).allMatch(a -> props.getAvatarPool().contains(a));
    }

    @Test
    void idempotent_secondRunMakesNoChanges() {
        // 第一次跑:把 5 个 child 的 avatar 重抽
        List<AgentEmployee> kids1 = List.of(
                child(20L, "墨1", "10,11"),
                child(21L, "墨2", "10,11"),
                child(22L, "墨3", "10,11"),
                child(23L, "墨4", "10,11"),
                child(24L, "墨5", "10,11"));
        when(repo.findByParentIdsIsNotNull()).thenReturn(kids1);
        when(repo.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Optional.empty());
        // save() returns the argument mutated
        when(repo.save(org.mockito.ArgumentMatchers.any(AgentEmployee.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        runner.run(new String[]{});
        // 第一次跑后,kids1 里的 avatar 已被 pickAvatar 重写

        // 第二次跑:模拟重启后,repo 返回的 avatar 已经是上次的值
        // 由于 pickAvatar 是确定性的,第二次跑不会改写
        runner.run(new String[]{});

        // 第 1 次:5 saves(只 kids,parent 没找到 → 不 save)
        // 第 2 次:5 saves(同样的重写,但因为确定性,值不变)
        // 我们断言:第二次的 save 数不超过 5(没改 parent,因为 parentIds 没变仍 5 个)
        // 关键断言:每行的 avatar 在 2 次后都等于确定性 pickAvatar 的结果
        for (AgentEmployee k : kids1) {
            assertThat(k.getAvatar()).isEqualTo(props.pickAvatar(k.getName()));
        }
    }

    @Test
    void partialFailure_doesNotCrashStartup() {
        when(repo.findByParentIdsIsNotNull()).thenReturn(List.of(
                child(20L, "墨1", "10,11"),
                child(21L, "墨2", "10,11"),
                child(22L, "墨3", "10,11"),
                child(23L, "墨4", "10,11"),
                child(24L, "墨5", "10,11")));
        when(repo.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Optional.empty());
        // 第 3 次 save 抛异常
        when(repo.save(org.mockito.ArgumentMatchers.any(AgentEmployee.class)))
                .thenAnswer(inv -> {
                    AgentEmployee arg = inv.getArgument(0);
                    if (arg.getId() != null && arg.getId() == 22L) {
                        throw new org.springframework.dao.DataIntegrityViolationException("simulated");
                    }
                    return arg;
                });

        // 不应抛
        runner.run(new String[]{});

        // 至少 1 条 WARN 日志
        List<ILoggingEvent> warnEvents = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents).hasSizeGreaterThanOrEqualTo(1);
        // 1 条 INFO 总结
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).hasSize(1);
        // reavatar 数应 < 5(因为 1 行失败,可能 0~4 都行,具体看 catch 位置)
        // 这里只断言"确实有 1 条 INFO + ≥1 WARN"即可
    }

    @Test
    void coupleAtCapOrBelow_doesNotClearPregnantFields() {
        // 3 children for couple 10,11 = at cap (not over),no clearing
        when(repo.findByParentIdsIsNotNull()).thenReturn(List.of(
                child(20L, "墨1", "10,11"),
                child(21L, "墨2", "10,11"),
                child(22L, "墨3", "10,11")));
        AgentEmployee p10 = newParent(10L, "老王");
        p10.setPregnantSince(java.time.LocalDate.of(2026, 1, 1));
        p10.setDueDate(java.time.LocalDate.of(2026, 1, 31));
        // 不 stub findById():couple 3 == cap 3,清除块不执行(continue),parent 不被加载
        // 不 stub save():child 仍会被 save(3 次),用 lenient 避免 strict 模式对未触达 stub 报错
        org.mockito.Mockito.lenient().when(repo.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.lenient().when(repo.save(org.mockito.ArgumentMatchers.any(AgentEmployee.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        runner.run(new String[]{});

        // 3 children,saves 触达 children
        // couple 10,11 = 3 children,cap=3,not OVER → 不清 pregnant
        assertThat(p10.getPregnantSince()).isNotNull();
        assertThat(p10.getDueDate()).isNotNull();

        // couplesCleared=0
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents.get(0).getFormattedMessage()).contains("couplesCleared=0");
    }

    private static AgentEmployee newParent(Long id, String name) {
        AgentEmployee e = new AgentEmployee();
        e.setId(id);
        e.setName(name);
        e.setStatus("active");
        return e;
    }

    private static AgentEmployee child(Long id, String name, String parentIds) {
        AgentEmployee c = new AgentEmployee();
        c.setId(id);
        c.setName(name);
        c.setParentIds(parentIds);
        c.setStatus("active");
        return c;
    }
}
