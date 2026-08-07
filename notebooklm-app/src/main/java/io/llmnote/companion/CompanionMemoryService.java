package io.llmnote.companion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.milvus.CompanionMilvusClient;
import io.llmnote.llm.ChatModelFactory;
import io.llmnote.llm.ChatCompletion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 伴侣长期记忆管理。
 * - 抽取:每轮聊天后异步调 reasoning model(GLM-z1-flash),从最近 user/assistant 消息中提炼 0~3 条事实;
 *   返回 NONE / [] / 仅含 NONE 的字符串时落库跳过(C5 / R15)。
 * - 检索:每次注入 LLM 前,先 embedding 当前 user 消息,在 Milvus 检索 top-K 注入 system prompt。
 * - 软删:companion_message/companion_memory/milvus 三处一致。
 * - 失败降级:任何 Milvus/LLM 异常不阻断聊天,仅记 warn。
 */
@Slf4j
@Service
public class CompanionMemoryService {

    private static final String EXTRACT_PROMPT_TEMPLATE = """
            你是一个事实抽取助手。下面是用户和 AI 伴侣最近的一组对话,请你提炼出 0~3 条关于"用户本人"的稳定事实/偏好/经历。
            要求:
            - 只写关于"用户"的事实(用户姓名/职业/爱好/家人/偏好/重要事件等),不要写关于 AI 自己的事。
            - 每条 10~60 字,独立成行,以"-"开头。
            - 若对话里没有任何用户事实,只输出一行:NONE
            - 不要编造,不要重复相同事实,不要写推测/不确定的话。

            对话:
            %s
            """;

    private final CompanionMemoryRepository memoryRepository;
    private final CompanionMilvusClient milvusClient;
    private final ChatCompletion chatCompletion;
    private final ChatModelFactory modelFactory;
    private final EmbeddingModel embeddingModel;
    private final CompanionProperties properties;
    private final ObjectMapper objectMapper;

    public CompanionMemoryService(CompanionMemoryRepository memoryRepository,
                                  CompanionMilvusClient milvusClient,
                                  ChatCompletion chatCompletion,
                                  ChatModelFactory modelFactory,
                                  EmbeddingModel embeddingModel,
                                  CompanionProperties properties,
                                  ObjectMapper objectMapper) {
        this.memoryRepository = memoryRepository;
        this.milvusClient = milvusClient;
        this.chatCompletion = chatCompletion;
        this.modelFactory = modelFactory;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 每轮聊天后异步调用,失败不抛出。 */
    @Async
    public CompletableFuture<Void> extractAndStoreAsync(String userId, Long companionId, Long sourceMessageId,
                                                        List<String> recentUserMessages,
                                                        List<String> recentAssistantMessages) {
        try {
            extractAndStore(userId, companionId, sourceMessageId, recentUserMessages, recentAssistantMessages);
        } catch (Exception e) {
            log.warn("记忆抽取失败(已忽略): {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Transactional
    public void extractAndStore(String userId, Long companionId, Long sourceMessageId,
                                List<String> recentUserMessages,
                                List<String> recentAssistantMessages) {
        StringBuilder convo = new StringBuilder();
        int max = Math.max(recentUserMessages.size(), recentAssistantMessages.size());
        for (int i = 0; i < max; i++) {
            if (i < recentUserMessages.size()) convo.append("用户: ").append(recentUserMessages.get(i)).append('\n');
            if (i < recentAssistantMessages.size()) convo.append("助手: ").append(recentAssistantMessages.get(i)).append('\n');
        }
        if (convo.length() == 0) return;
        String system = "你只输出关于用户的事实列表或 NONE。";
        String user = String.format(EXTRACT_PROMPT_TEMPLATE, convo.toString());
        String raw;
        try {
            raw = chatCompletion.complete(system, user);
        } catch (Exception e) {
            log.warn("LLM 记忆抽取失败: {}", e.getMessage());
            return;
        }
        if (raw == null) return;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("NONE") || trimmed.contains("NONE") && trimmed.split("\n").length == 1) {
            return; // R15 NONE 不写
        }
        List<String> facts = parseFacts(trimmed);
        if (facts.isEmpty()) return;
        for (String fact : facts) {
            storeOne(userId, companionId, sourceMessageId, fact);
        }
    }

    private void storeOne(String userId, Long companionId, Long sourceMessageId, String content) {
        try {
            CompanionMemory mem = new CompanionMemory();
            mem.setUserId(userId);
            mem.setCompanionId(companionId);
            mem.setContent(content);
            mem.setSourceMessageId(sourceMessageId);
            mem.setDeleted(false);
            CompanionMemory saved = memoryRepository.save(mem);
            if (milvusClient.isAvailable()) {
                try {
                    double[] emb = embeddingModel.embed(io.agentscope.core.message.TextBlock.builder().text(content).build()).block();
                    List<Float> vec = toFloatList(emb);
                    milvusClient.insert(companionId, userId, saved.getId(), content, vec);
                } catch (Exception e) {
                    log.warn("Milvus insert 失败(已落 MySQL): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("记忆写入失败: {}", e.getMessage());
        }
    }

    /** 当前轮 LLM 注入前调用:embedding 当前 user 文本,top-K 检索。失败返回空。 */
    public List<String> search(String userId, Long companionId, String currentText) {
        if (currentText == null || currentText.isBlank() || !milvusClient.isAvailable()) return List.of();
        try {
            double[] emb = embeddingModel.embed(io.agentscope.core.message.TextBlock.builder().text(currentText).build()).block();
            List<Float> vec = toFloatList(emb);
            return milvusClient.search(companionId, userId, vec, properties.getMemorySearchK());
        } catch (Exception e) {
            log.warn("Milvus 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Transactional
    public void deleteFromMilvus(long companionId, long memoryId) {
        if (!milvusClient.isAvailable()) return;
        try {
            io.milvus.param.dml.DeleteParam param = io.milvus.param.dml.DeleteParam.newBuilder()
                    .withCollectionName(CompanionMilvusClient.COLLECTION)
                    .withExpr(CompanionMilvusClient.COMPANION_ID_FIELD + " == " + companionId
                            + " && " + CompanionMilvusClient.MEMORY_ID_FIELD + " == " + memoryId)
                    .build();
            milvusClient.getClass(); // 静态访问无需
            io.milvus.client.MilvusServiceClient client = milvusClientAccessor();
            if (client != null) client.delete(param);
        } catch (Exception e) {
            log.warn("Milvus delete 失败(已落 MySQL): {}", e.getMessage());
        }
    }

    /**
     * 内部桥:CompanionMilvusClient 的 client 是 private。
     * 这里反射读取;不暴露给上层。
     */
    private io.milvus.client.MilvusServiceClient milvusClientAccessor() {
        try {
            java.lang.reflect.Field f = CompanionMilvusClient.class.getDeclaredField("client");
            f.setAccessible(true);
            return (io.milvus.client.MilvusServiceClient) f.get(milvusClient);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseFacts(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase("NONE") || t.toUpperCase().startsWith("NONE")) continue;
            if (t.startsWith("-") || t.startsWith("•") || t.startsWith("·")) {
                t = t.substring(1).trim();
            }
            if (t.isEmpty()) continue;
            if (t.length() > 200) t = t.substring(0, 200);
            out.add(t);
            if (out.size() >= 3) break;
        }
        return out;
    }

    private List<Float> toFloatList(double[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (double d : arr) list.add((float) d);
        return list;
    }
}
