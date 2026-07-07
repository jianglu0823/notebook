package io.llmnote.ingest;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 音频转写:DashScope Paraformer(paraformer-realtime-v2)本地文件识别 -> 文本走统一摄取管线。
 * Recognition.call(param, File) 是同步阻塞调用,返回整段转写文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioTranscribeService {

    private static final String ASR_MODEL = "paraformer-realtime-v2";

    private final NotebookLmProperties props;
    private final SourceRepository sourceRepo;
    private final IngestService ingestService;

    /** 转写音频文件并入库。阻塞式(在异步线程中调用)。 */
    public void transcribeAndIngest(Source source, Path filePath) {
        try {
            source.setStatus("PARSING");
            sourceRepo.save(source);

            String text = transcribe(filePath);
            if (text == null || text.isBlank()) {
                source.setStatus("FAILED");
                source.setErrorMsg("音频转写结果为空");
                sourceRepo.save(source);
                return;
            }
            ingestService.ingestText(source, text);
        } catch (Exception e) {
            log.error("transcribe failed: source={}", source.getId(), e);
            source.setStatus("FAILED");
            String msg = e.getMessage();
            source.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
            sourceRepo.save(source);
        }
    }

    private String transcribe(Path filePath) {
        String format = detectFormat(filePath.toString());
        RecognitionParam param = RecognitionParam.builder()
                .apiKey(props.getDashscope().getApiKey())
                .model(ASR_MODEL)
                .format(format)
                .sampleRate(16000)
                .build();
        return new Recognition().call(param, filePath.toFile());
    }

    private String detectFormat(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".wav")) return "wav";
        if (lower.endsWith(".mp3")) return "mp3";
        if (lower.endsWith(".m4a")) return "m4a";
        if (lower.endsWith(".flac")) return "flac";
        return "mp3";
    }
}
