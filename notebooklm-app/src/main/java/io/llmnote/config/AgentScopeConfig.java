package io.llmnote.config;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.MilvusStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentScopeConfig {

    @Bean
    public EmbeddingModel embeddingModel(NotebookLmProperties props) {
        return DashScopeTextEmbedding.builder()
                .apiKey(props.getDashscope().getApiKey())
                .modelName(props.getDashscope().getEmbeddingModel())
                .dimensions(props.getMilvus().getDim())
                .build();
    }

    @Bean(destroyMethod = "close")
    public MilvusStore milvusStore(NotebookLmProperties props) throws io.agentscope.core.rag.exception.VectorStoreException {
        NotebookLmProperties.Milvus m = props.getMilvus();
        return MilvusStore.builder()
                .uri("http://" + m.getHost() + ":" + m.getPort())
                .collectionName(m.getCollection())
                .dimensions(m.getDim())
                .build();
    }

    @Bean
    public SimpleKnowledge knowledge(EmbeddingModel embeddingModel, VDBStoreBase store) {
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
    }

    /** 文本对话模型(qwen-plus),流式开启供 SSE 使用 */
    @Bean
    @Primary
    public DashScopeChatModel chatModel(NotebookLmProperties props) {
        return DashScopeChatModel.builder()
                .apiKey(props.getDashscope().getApiKey())
                .modelName(props.getDashscope().getChatModel())
                .stream(true)
                .build();
    }

    /** 视觉理解模型(qwen-vl-max),用于图像描述 */
    @Bean
    public DashScopeChatModel vlChatModel(NotebookLmProperties props) {
        return DashScopeChatModel.builder()
                .apiKey(props.getDashscope().getApiKey())
                .modelName(props.getDashscope().getVlModel())
                .stream(true)
                .build();
    }
}
