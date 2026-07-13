package io.llmnote.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Toolkit;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 协同写作编排的异步执行体。独立于 {@link WritingAgentService} 是为了让 {@code @Async} 生效
 * —— Spring 的 @Async 走代理,若在同一个 bean 内自调用会失效(退化成同步),
 * 导致「发起协作」的 HTTP 请求阻塞到整场协作跑完。拆成独立 bean 由 service 注入调用即可修复。
 *
 * 作者⇄核查⇄主编三个 ReActAgent(用本次选定的模型)迭代收敛;每步实时 emit 进 events 时间线;
 * 并累计 token,按模型单价换算费用写库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritingRunner {

    private static final int MAX_AGENT_ITERS = 12;

    private final ObjectMapper objectMapper;
    private final ChatModelFactory modelFactory;
    private final WritingProjectRepository repo;

    @Async
    public void run(Long id) {
        WritingProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        long[] tok = {0L, 0L}; // [inputTokens, outputTokens]
        try {
            p.setStatus("RUNNING");
            repo.save(p);

            String modelName = modelFactory.normalize(p.getModel());
            ChatModelBase model = modelFactory.forModel(modelName);

            ArrayNode eventsArr = objectMapper.createArrayNode();
            emit(p, eventsArr, "start", "system", "多智能体协作启动:作者写稿 → 核查员联网核实 → 主编裁决,最多 "
                    + p.getMaxRounds() + " 轮。模型:" + modelName);

            ReActAgent author = buildAuthor(p.getGenre(), model);
            ReActAgent editor = buildEditor(p.getGenre(), model);
            // 核查员的 web_search 每次调用都实时写入事件时间线(暴露它的「思考」:在核实什么)
            ReActAgent factchecker = buildFactChecker(model, (query, preview) ->
                    emit(p, eventsArr, "tool", "checker",
                            "🔎 联网核查:" + query,
                            preview.length() > 220 ? preview.substring(0, 220) + "…" : preview));

            ArrayNode roundsArr = objectMapper.createArrayNode();

            // 作者初稿
            emit(p, eventsArr, "think", "author", "✒️ 作者正在撰写初稿…");
            String draft = ask(author,
                    "写作主题/需求:「" + p.getTopic() + "」\n体裁:" + genreLabel(p.getGenre())
                            + "\n请完成初稿。只输出稿件正文本身。", tok);
            emit(p, eventsArr, "done", "author", "✒️ 作者完成初稿(" + draft.length() + " 字)。");
            String finalText = draft;
            boolean approved = false;

            for (int round = 1; round <= p.getMaxRounds(); round++) {
                emit(p, eventsArr, "round", "system", "—— 第 " + round + " 轮协作 ——");
                long roundIn = tok[0], roundOut = tok[1];

                // 核查(自主联网)
                emit(p, eventsArr, "think", "checker", "🔎 核查员开始逐条核实事实,将按需联网搜索…");
                String factReport = ask(factchecker,
                        "请核查以下稿件中的事实性表述(数据、时间、人物、事件、专有名词等),"
                                + "对不确定的点使用 web_search 工具联网核实。逐条列出:【问题表述】→【核实结论/建议修正】;"
                                + "若全部属实或无事实性内容,回复「无事实性问题」。\n\n=== 稿件 ===\n" + finalText, tok);
                emit(p, eventsArr, "done", "checker", "🔎 核查员完成事实核查。");

                // 编辑审稿 + 裁决
                emit(p, eventsArr, "think", "editor", "🧑‍⚖️ 主编正在审稿并结合核查报告裁决…");
                String editorOut = ask(editor,
                        "写作主题:「" + p.getTopic() + "」\n请审阅以下稿件,从结构、表达、吸引力、是否切题四方面给出具体修改意见。"
                                + "并参考核查员的事实核查报告。最后必须在末尾单独一行输出裁决:若可定稿写 `VERDICT: APPROVE`,"
                                + "否则写 `VERDICT: REVISE`。\n\n=== 核查报告 ===\n" + factReport
                                + "\n\n=== 稿件 ===\n" + finalText, tok);
                boolean verdictApprove = editorOut.toUpperCase().contains("VERDICT: APPROVE");
                emit(p, eventsArr, "verdict", "editor",
                        verdictApprove ? "🧑‍⚖️ 主编裁决:APPROVE(定稿)" : "🧑‍⚖️ 主编裁决:REVISE(需修改)");

                ObjectNode rn = objectMapper.createObjectNode();
                rn.put("round", round);
                rn.put("draft", finalText);
                rn.put("factcheck", factReport);
                rn.put("review", editorOut);
                rn.put("verdict", verdictApprove ? "APPROVE" : "REVISE");
                roundsArr.add(rn);
                p.setRounds(objectMapper.writeValueAsString(roundsArr));
                repo.save(p);
                long dIn = tok[0] - roundIn, dOut = tok[1] - roundOut;
                emit(p, eventsArr, "usage", "system",
                        "第 " + round + " 轮 token:输入 " + dIn + " / 输出 " + dOut
                                + "(累计 " + tok[0] + "/" + tok[1] + ")");
                log.info("writing round {}/{} id={} verdict={}", round, p.getMaxRounds(), id, verdictApprove ? "APPROVE" : "REVISE");

                if (verdictApprove) { approved = true; break; }
                if (round == p.getMaxRounds()) {
                    emit(p, eventsArr, "note", "system", "已用完 " + p.getMaxRounds() + " 轮,以当前稿件作为终稿。");
                    break;
                }

                // 作者据反馈改稿
                emit(p, eventsArr, "think", "author", "✒️ 作者正在根据主编意见与核查报告改稿…");
                finalText = ask(author,
                        "这是你之前的稿件,请根据编辑意见与核查报告修改,产出改进后的完整稿件。只输出稿件正文本身。\n\n"
                                + "=== 编辑意见 ===\n" + editorOut + "\n\n=== 核查报告 ===\n" + factReport
                                + "\n\n=== 当前稿件 ===\n" + finalText, tok);
                emit(p, eventsArr, "done", "author", "✒️ 作者完成改稿(" + finalText.length() + " 字)。");
            }

            emit(p, eventsArr, "finish", "system", approved ? "✅ 主编已 APPROVE,协作完成。" : "✅ 协作完成(轮数用尽,取最佳稿)。");
            BigDecimal cost = BigDecimal.valueOf(modelFactory.costRmb(modelName, tok[0], tok[1]));
            p.setFinalText(finalText.trim());
            p.setApproved(approved);
            p.setModel(modelName);
            p.setInputTokens(tok[0]);
            p.setOutputTokens(tok[1]);
            p.setCostRmb(cost);
            p.setStatus("DONE");
            repo.save(p);
            log.info("writing done id={} model={} approved={} tokens={}/{} cost={}", id, modelName, approved, tok[0], tok[1], cost);
        } catch (Exception e) {
            log.error("writing failed id={}", id, e);
            fail(p, e.getMessage());
        }
    }

    /** 即时追加一条协作事件并存库(前端轮询围观)。detail 可选。 */
    private void emit(WritingProject p, ArrayNode eventsArr, String type, String role, String text, String... detail) {
        try {
            ObjectNode ev = objectMapper.createObjectNode();
            ev.put("t", System.currentTimeMillis());
            ev.put("type", type);
            ev.put("role", role);
            ev.put("text", text);
            if (detail.length > 0 && detail[0] != null) ev.put("detail", detail[0]);
            eventsArr.add(ev);
            p.setEvents(objectMapper.writeValueAsString(eventsArr));
            repo.save(p);
        } catch (Exception ignore) { /* 事件写入失败不影响主流程 */ }
    }

    // ---- Agent 构建 ----

    private ReActAgent buildAuthor(String genre, ChatModelBase model) {
        return ReActAgent.builder()
                .name("author")
                .sysPrompt("你是一位资深" + genreLabel(genre) + "作者,文笔出色、结构清晰。"
                        + "你根据主题与编辑/核查反馈撰写和打磨稿件。只输出稿件正文,不要解释你的写作过程。")
                .model(model)
                .maxIters(MAX_AGENT_ITERS)
                .build();
    }

    private ReActAgent buildEditor(String genre, ChatModelBase model) {
        return ReActAgent.builder()
                .name("editor")
                .sysPrompt("你是一位严格的资深主编,负责把控" + genreLabel(genre) + "的质量。"
                        + "你审阅稿件、给出具体可执行的修改意见,并结合事实核查报告做出定稿裁决。"
                        + "标准要高但公允:结构完整、表达流畅、切题、无明显事实错误方可 APPROVE。"
                        + "回复末尾必须单独一行给出 `VERDICT: APPROVE` 或 `VERDICT: REVISE`。")
                .model(model)
                .maxIters(MAX_AGENT_ITERS)
                .build();
    }

    private ReActAgent buildFactChecker(ChatModelBase model, java.util.function.BiConsumer<String, String> searchListener) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool(model, searchListener));
        return ReActAgent.builder()
                .name("factchecker")
                .sysPrompt("你是一位严谨的事实核查员。你审查稿件中的事实性表述,"
                        + "对任何不确定的数据、时间、人物、事件、专有名词,主动调用 web_search 工具联网核实,"
                        + "严禁凭记忆下结论。逐条给出核实结论与修正建议。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(MAX_AGENT_ITERS)
                .build();
    }

    /** 阻塞调用一个 Agent 并取其回复文本,并把本次 token 用量累加进 tok[]。 */
    private String ask(ReActAgent agent, String prompt, long[] tok) {
        Msg reply = agent.call(prompt, RuntimeContext.empty()).block();
        if (reply == null) return "";
        ChatUsage u = reply.getChatUsage();
        if (u != null) { tok[0] += u.getInputTokens(); tok[1] += u.getOutputTokens(); }
        String text = reply.getTextContent();
        return text == null ? "" : text.trim();
    }

    private String genreLabel(String genre) {
        return switch (genre == null ? "" : genre) {
            case "STORY" -> "故事/小说";
            case "REVIEW" -> "测评/种草文";
            case "SCRIPT" -> "短视频脚本";
            default -> "文章";
        };
    }

    private void fail(WritingProject p, String msg) {
        if (p == null) return;
        p.setStatus("FAILED");
        p.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        repo.save(p);
    }
}
