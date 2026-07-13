package io.llmnote.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 「对话捏人」——用自然对话逐步引导用户描述一位新居民,AI 追问缺失细节,
 * 信息足够时给出人设草案(draft),前端据此预填新增表单让用户确认落库。
 *
 * <p>不落库、不建 HarnessAgent:每次请求把前端持有的 transcript 一起带上,
 * 用最便宜模型(qwen-turbo)单轮补全,省 token。真正创建仍走 {@code POST /api/world/agents}。
 */
@Slf4j
@RestController
@RequestMapping("/api/world/agent-builder")
@RequiredArgsConstructor
public class AgentBuilderController {

    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    private static final int MAX_TURNS = 12; // 只带最近若干轮,省 token

    private static final String SYSTEM = """
            你是「鹿匠小镇」的居民设计助手,帮用户用聊天的方式捏出一位新居民(智能体)。
            通过自然、简短的对话引导用户,逐步问清这些要素:名字、身份/职业、性格人设(最重要)、
            可选的头像 emoji、主题色、口头禅/心情。一次只问 1~2 个还缺的点,像朋友聊天,别一次抛一堆问题。
            当关键信息(至少 名字 + 身份 + 性格)已具备时,把 ready 置为 true 并给出 draft 草案。
            无论是否 ready,都必须只输出一个 JSON 对象(不要 markdown 围栏、不要多余文字),格式:
            {
              "reply": "你对用户说的一句自然的话(继续追问,或告知草案已就绪请确认)",
              "ready": true 或 false,
              "draft": {
                "name": "名字",
                "title": "身份/职业",
                "persona": "第二人称的性格人设,作为该居民的系统提示,60~150字,鲜活具体",
                "avatar": "一个代表性 emoji",
                "color": "#十六进制主题色",
                "mood": "2~4字心情",
                "moodEmoji": "一个 emoji",
                "birthDate": "YYYY-MM-DD 或 null"
              }
            }
            ready=false 时 draft 可给已知的部分字段(未知留空字符串或省略)。语言:简体中文。
            """;

    @PostMapping("/chat")
    public BuilderResp chat(@RequestBody BuilderReq req) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(SYSTEM).build()).build());

        if (req != null && req.getHistory() != null) {
            List<Turn> hist = req.getHistory();
            int from = Math.max(0, hist.size() - MAX_TURNS);
            for (int i = from; i < hist.size(); i++) {
                Turn t = hist.get(i);
                if (t == null || t.getContent() == null || t.getContent().isBlank()) continue;
                MsgRole role = "assistant".equalsIgnoreCase(t.getRole()) ? MsgRole.ASSISTANT : MsgRole.USER;
                messages.add(Msg.builder().role(role)
                        .content(TextBlock.builder().text(t.getContent()).build()).build());
            }
        }
        String userMsg = req == null || req.getMessage() == null ? "" : req.getMessage().trim();
        if (!userMsg.isBlank()) {
            messages.add(Msg.builder().role(MsgRole.USER)
                    .content(TextBlock.builder().text(userMsg).build()).build());
        }

        String raw;
        try {
            ChatModelBase model = modelFactory.forModel(modelFactory.normalize(modelFactory.defaultTextModel()));
            List<ChatResponse> responses = modelFactory.streamText(model, messages);
            StringBuilder sb = new StringBuilder();
            if (responses != null) {
                for (ChatResponse r : responses) {
                    if (r.getContent() == null) continue;
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
            }
            raw = sb.toString().trim();
        } catch (Exception ex) {
            log.warn("agent-builder chat failed", ex);
            BuilderResp err = new BuilderResp();
            err.setReply("(助手走神了,请再说一次)");
            err.setReady(false);
            return err;
        }
        return toResp(raw);
    }

    private BuilderResp toResp(String raw) {
        BuilderResp resp = new BuilderResp();
        JsonNode j = parse(raw);
        String reply = text(j, "reply", null);
        resp.setReply(reply == null ? (raw.isBlank() ? "(沉默)" : raw) : reply);
        resp.setReady(j.path("ready").asBoolean(false));
        JsonNode d = j.path("draft");
        if (d.isObject()) {
            Draft draft = new Draft();
            draft.setName(text(d, "name", null));
            draft.setTitle(text(d, "title", null));
            draft.setPersona(text(d, "persona", null));
            draft.setAvatar(text(d, "avatar", null));
            draft.setColor(text(d, "color", null));
            draft.setMood(text(d, "mood", null));
            draft.setMoodEmoji(text(d, "moodEmoji", null));
            String birth = text(d, "birthDate", null);
            draft.setBirthDate(birth == null || "null".equalsIgnoreCase(birth) ? null : birth);
            resp.setDraft(draft);
        }
        return resp;
    }

    private JsonNode parse(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            int i = s.indexOf('{'), k = s.lastIndexOf('}');
            if (i >= 0 && k > i) s = s.substring(i, k + 1);
            return objectMapper.readTree(s);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode j, String field, String dflt) {
        JsonNode n = j.path(field);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? dflt : n.asText().trim();
    }

    @Data
    public static class BuilderReq {
        private List<Turn> history;
        private String message;
    }

    @Data
    public static class Turn {
        private String role;    // user | assistant
        private String content;
    }

    @Data
    public static class BuilderResp {
        private String reply;
        private boolean ready;
        private Draft draft;
    }

    @Data
    public static class Draft {
        private String name;
        private String title;
        private String persona;
        private String avatar;
        private String color;
        private String mood;
        private String moodEmoji;
        private String birthDate;
    }
}
