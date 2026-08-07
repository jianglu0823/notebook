package io.llmnote.companion.milvus;

import io.llmnote.config.NotebookLmProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 伴侣长期记忆的 Milvus 客户端(v1 API)。
 * - 与 nblm_chunks 共享同一实例(同 host:port),但用独立 collection companion_memories。
 * - 启动时检查/创建 collection + 索引(若不存在),已有则跳过。
 * - 连不上/操作失败时记录 warn,不抛出(允许聊天在无 Milvus 时仍可用)。
 */
@Slf4j
@Component
public class CompanionMilvusClient {

    public static final String COLLECTION = "companion_memories";
    public static final int DIM = 1024;
    public static final String VEC_FIELD = "embedding";
    public static final String ID_FIELD = "id";
    public static final String COMPANION_ID_FIELD = "companion_id";
    public static final String USER_ID_FIELD = "user_id";
    public static final String MEMORY_ID_FIELD = "memory_id";
    public static final String CONTENT_FIELD = "content";

    private final NotebookLmProperties props;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile MilvusServiceClient client;
    private volatile boolean available = false;

    public CompanionMilvusClient(NotebookLmProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            lock.lock();
            NotebookLmProperties.Milvus m = props.getMilvus();
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(m.getHost())
                    .withPort(m.getPort())
                    .build();
            client = new MilvusServiceClient(connectParam);
            ensureCollection();
            available = true;
            log.info("Milvus companion_memories 就绪 @ {}:{}", m.getHost(), m.getPort());
        } catch (Exception e) {
            log.warn("Milvus 不可用,长期记忆功能降级: {}", e.getMessage());
            available = false;
        } finally {
            lock.unlock();
        }
    }

    private void ensureCollection() {
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        if (has.getData() != null && has.getData()) {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION).build());
            return;
        }
        FieldType idF = FieldType.newBuilder()
                .withName(ID_FIELD).withDataType(io.milvus.grpc.DataType.Int64)
                .withPrimaryKey(true).withAutoID(true).build();
        FieldType vec = FieldType.newBuilder()
                .withName(VEC_FIELD).withDataType(io.milvus.grpc.DataType.FloatVector)
                .withDimension(DIM).build();
        FieldType cidF = FieldType.newBuilder()
                .withName(COMPANION_ID_FIELD).withDataType(io.milvus.grpc.DataType.Int64).build();
        FieldType uidF = FieldType.newBuilder()
                .withName(USER_ID_FIELD).withDataType(io.milvus.grpc.DataType.VarChar)
                .withMaxLength(64).build();
        FieldType midF = FieldType.newBuilder()
                .withName(MEMORY_ID_FIELD).withDataType(io.milvus.grpc.DataType.Int64).build();
        FieldType contentF = FieldType.newBuilder()
                .withName(CONTENT_FIELD).withDataType(io.milvus.grpc.DataType.VarChar)
                .withMaxLength(512).build();
        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("AI 伴侣长期记忆(companion-chat)")
                .withShardsNum(2)
                .addFieldType(idF).addFieldType(vec).addFieldType(cidF)
                .addFieldType(uidF).addFieldType(midF).addFieldType(contentF)
                .build();
        client.createCollection(param);
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName(VEC_FIELD)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.IP)
                .withExtraParam("{\"nlist\":128}")
                .build());
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
    }

    public boolean isAvailable() {
        return available;
    }

    /** 插入一条记忆向量 + payload。返回主键 id(失败返回 null)。 */
    public Long insert(long companionId, String userId, long memoryId, String content, List<Float> vector) {
        if (!available || client == null) return null;
        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(COMPANION_ID_FIELD, Arrays.asList(companionId)));
            fields.add(new InsertParam.Field(USER_ID_FIELD, Arrays.asList(userId)));
            fields.add(new InsertParam.Field(MEMORY_ID_FIELD, Arrays.asList(memoryId)));
            fields.add(new InsertParam.Field(CONTENT_FIELD, Arrays.asList(truncate(content, 512))));
            fields.add(new InsertParam.Field(VEC_FIELD, Arrays.asList(vector)));
            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withFields(fields)
                    .build();
            R<io.milvus.grpc.MutationResult> r = client.insert(param);
            if (r.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus insert 失败: {}", r.getMessage());
                return null;
            }
            var ids = r.getData().getIDs().getIntId().getDataList();
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("Milvus insert 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 向量检索 top-K,按 companion_id + user_id 双过滤,返回 content 列表(按相似度降序)。
     * 失败返回空列表。
     */
    public List<String> search(long companionId, String userId, List<Float> queryVector, int topK) {
        if (!available || client == null) return List.of();
        try {
            String expr = COMPANION_ID_FIELD + " == " + companionId + " && " + USER_ID_FIELD + " == \"" + userId + "\"";
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withVectorFieldName(VEC_FIELD)
                    .withMetricType(MetricType.IP)
                    .withOutFields(Arrays.asList(CONTENT_FIELD, MEMORY_ID_FIELD))
                    .withExpr(expr)
                    .withTopK(topK)
                    .withVectors(Arrays.asList(queryVector))
                    .withParams("{\"nprobe\":16}")
                    .build();
            R<io.milvus.grpc.SearchResults> r = client.search(param);
            if (r.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus search 失败: {}", r.getMessage());
                return List.of();
            }
            var data = r.getData().getResults();
            // 解析 FieldsData:每行一个 FieldData(按 outFields 顺序),每行 k 条结果。
            // 为简化,直接读第一个 field (content) 的 strings。
            List<String> out = new ArrayList<>();
            for (io.milvus.grpc.FieldData fd : data.getFieldsDataList()) {
                if (!CONTENT_FIELD.equals(fd.getFieldName())) continue;
                var sc = fd.getScalars();
                if (sc == null) continue;
                var sd = sc.getStringData();
                if (sd == null) continue;
                out.addAll(sd.getDataList());
                break;
            }
            return out;
        } catch (Exception e) {
            log.warn("Milvus search 异常: {}", e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
