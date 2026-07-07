package io.llmnote.ingest;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 图像理解:qwen-vl 对图片生成结构化描述文本 -> 走统一摄取管线并入 RAG。
 */
@Slf4j
@Service
public class ImageUnderstandingService {

    private final DashScopeChatModel vlChatModel;
    private final SourceRepository sourceRepo;
    private final IngestService ingestService;

    public ImageUnderstandingService(@Qualifier("vlChatModel") DashScopeChatModel vlChatModel,
                                     SourceRepository sourceRepo,
                                     IngestService ingestService) {
        this.vlChatModel = vlChatModel;
        this.sourceRepo = sourceRepo;
        this.ingestService = ingestService;
    }

    /** 识别图片并入库。阻塞式(在异步线程中调用)。 */
    public void describeAndIngest(Source source, Path filePath) {
        try {
            source.setStatus("PARSING");
            sourceRepo.save(source);

            String description = describe(filePath);
            if (description == null || description.isBlank()) {
                source.setStatus("FAILED");
                source.setErrorMsg("图像识别结果为空");
                sourceRepo.save(source);
                return;
            }
            ingestService.ingestText(source, description);
        } catch (Exception e) {
            log.error("image understanding failed: source={}", source.getId(), e);
            source.setStatus("FAILED");
            String msg = e.getMessage();
            source.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
            sourceRepo.save(source);
        }
    }

    private String describe(Path filePath) throws Exception {
        byte[] bytes = Files.readAllBytes(filePath);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String mediaType = mediaTypeOf(filePath.toString());

        ImageBlock image = ImageBlock.builder()
                .source(Base64Source.builder().mediaType(mediaType).data(b64).build())
                .build();
        TextBlock prompt = TextBlock.builder()
                .text("请详细描述这张图片的内容:包含的文字(逐字转写)、图表数据、关键元素与含义。用简体中文输出,尽量完整以便后续检索。")
                .build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(image, prompt)).build();
        List<ChatResponse> responses = vlChatModel.stream(List.of(msg), List.of(), null)
                .collectList().block();

        StringBuilder sb = new StringBuilder();
        if (responses != null) {
            for (ChatResponse r : responses) {
                if (r.getContent() == null) continue;
                r.getContent().forEach(b -> {
                    if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                });
            }
        }
        return sb.toString();
    }

    private String mediaTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
