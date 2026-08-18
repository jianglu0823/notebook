package io.llmnote.world;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 2026-08-18 C 阶段:为每个 AgentEmployee 维护英文 visualDescriptor(视觉描述),
 * 论坛帖子配图/视频都把它拼到 prompt,让同一个 agent 多次出图脸/服装/风格一致。
 *
 * <p>生命周期:
 * <ul>
 *   <li>App 启动时,扫一遍所有 active 居民,缺 visualDescriptor 的就调 LLM 生成一个。</li>
 *   <li>手动可调 {@link #backfillAll()} 兜底(比如想重置风格)。</li>
 * </ul>
 *
 * <p>visualDescriptor 格式:1-2 句英文,例 "a young woman with short black bob,
 * round glasses, blue denim jacket, gentle smile, anime-inspired portrait style"。
 * 不写第一人称/不写动作,只写静态外观 + 风格基线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentVisualDescriptorService {

    private final AgentEmployeeRepository employeeRepo;
    private final ChatModelFactory modelFactory;

    /**
     * 启动时扫一遍缺 visualDescriptor 的居民,逐个 LLM 生成并落库。
     * 失败仅 warn,不影响启动;失败的居民下次启动再重试。
     */
    @PostConstruct
    public void backfillOnStartup() {
        try {
            int n = backfillAll();
            log.info("[visual-descriptor] startup backfill done, generated={}", n);
        } catch (Exception e) {
            log.warn("[visual-descriptor] startup backfill failed: {}", e.getMessage());
        }
    }

    /** 兜底:全表扫描所有 visualDescriptor 为空的居民,L 生成。返回成功条数。 */
    @Transactional
    public int backfillAll() {
        List<AgentEmployee> all = employeeRepo.findAll();
        int ok = 0;
        for (AgentEmployee e : all) {
            if (e.getVisualDescriptor() != null && !e.getVisualDescriptor().isBlank()) continue;
            try {
                String desc = generateDescriptor(e);
                if (desc != null && !desc.isBlank()) {
                    e.setVisualDescriptor(desc);
                    employeeRepo.save(e);
                    ok++;
                }
            } catch (Exception ex) {
                log.warn("[visual-descriptor] agent={} failed: {}", e.getName(), ex.getMessage());
            }
        }
        return ok;
    }

    /** 给单个居民生成 visualDescriptor。LLM 失败抛异常或返回 null。 */
    String generateDescriptor(AgentEmployee e) {
        String name = e.getName() == null ? "此人" : e.getName();
        String title = e.getTitle() == null ? "" : e.getTitle();
        String persona = e.getPersona() == null ? "" : e.getPersona();
        String avatar = e.getAvatar() == null ? "" : e.getAvatar();

        String system = """
                你是一名角色视觉设计师。根据居民的名字、身份、性格、头像 emoji,
                输出一段 1-2 句的英文 visual descriptor(用于文生图 prompt 复用,提升角色一致性)。
                要求:
                - 只写静态外观:性别/年龄感/发型/脸型/服饰/配饰/整体风格
                - 不要写动作、不要写心情、不要写第一人称
                - 长度 20-80 个英文单词
                - 风格基线选一个:photorealistic / anime / 3D cartoon / watercolor / cinematic
                - 严格只输出 visual descriptor 文本本身,不要任何前缀/标号/JSON
                """;

        String user = "姓名:" + name + "\n身份:" + title + "\n头像 emoji:" + avatar
                + "\n人设摘要:" + (persona.length() > 200 ? persona.substring(0, 200) : persona);

        // 用最便宜模型:visualDescriptor 是辅助字段,不需要深度推理。
        try {
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
            List<ChatResponse> responses = modelFactory.streamTextWithFallback("qwen-turbo", messages);
            StringBuilder sb = new StringBuilder();
            if (responses != null) {
                for (ChatResponse r : responses) {
                    if (r.getContent() == null) continue;
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
            }
            String desc = sb.toString().trim();
            // 去掉偶尔出现的引号包裹
            if ((desc.startsWith("\"") && desc.endsWith("\""))
                    || (desc.startsWith("'") && desc.endsWith("'"))) {
                desc = desc.substring(1, desc.length() - 1).trim();
            }
            return desc.isEmpty() ? null : desc;
        } catch (Exception ex) {
            log.warn("[visual-descriptor] LLM call failed for agent={}: {}", name, ex.getMessage());
            return null;
        }
    }
}
