package io.llmnote.studio;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 供核查 Agent 注册的联网搜索工具。核查员在 ReAct 循环中自主决定何时、以什么关键词调用它 ——
 * 这是真正的 agentic tool-use。底层复用 qwen 的 enable_search 联网检索返回 grounded 事实。
 *
 * 每次调用会通过 listener(query, resultPreview) 回调,让编排层把「核查员正在搜什么」实时写入
 * 协作事件时间线,前端轮询即可围观智能体的思考过程。因携带 per-run 回调,按项目 new 一个实例
 * (非 Spring 单例)。
 */
@Slf4j
public class WebSearchTool {

    private final ChatModelBase chatModel;
    private final BiConsumer<String, String> listener;

    public WebSearchTool(ChatModelBase chatModel, BiConsumer<String, String> listener) {
        this.chatModel = chatModel;
        this.listener = listener;
    }

    @Tool(name = "web_search",
            description = "联网搜索最新、真实的信息以核实事实性表述。输入一个具体的搜索查询,返回检索到的事实性摘要。"
                    + "当你需要核实文中的数据、时间、人物、事件、专有名词等是否真实准确时调用此工具。")
    public String webSearch(
            @ToolParam(name = "query", description = "具体的搜索查询词,尽量精确、聚焦要核实的单个事实点") String query) {
        String result;
        try {
            String system = "你是联网检索助手。请使用联网搜索,针对用户的查询返回真实、最新的事实性信息,"
                    + "简明扼要列出关键事实与来源要点,严禁编造。若查询涉及数据/时间/人物,请给出可核实的具体值。";
            GenerateOptions options = GenerateOptions.builder()
                    .additionalBodyParams(Map.of("enable_search", true))
                    .build();
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(query).build()).build());
            List<ChatResponse> responses = chatModel.stream(messages, List.of(), options).collectList().block();
            StringBuilder sb = new StringBuilder();
            if (responses != null) {
                for (ChatResponse r : responses) {
                    if (r.getContent() == null) continue;
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
            }
            result = sb.toString().trim();
            if (result.isBlank()) result = "未检索到相关信息。";
            log.info("web_search query='{}' resultChars={}", query, result.length());
        } catch (Exception e) {
            log.warn("web_search failed: query='{}' err={}", query, e.getMessage());
            result = "搜索失败:" + e.getMessage();
        }
        if (listener != null) {
            try { listener.accept(query, result); } catch (Exception ignore) { /* 回调不影响工具返回 */ }
        }
        return result;
    }
}
