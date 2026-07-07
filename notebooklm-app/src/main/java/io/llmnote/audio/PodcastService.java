package io.llmnote.audio;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.llm.ChatCompletion;
import io.llmnote.notebook.Chunk;
import io.llmnote.notebook.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 音频概览播客:qwen 生成双主持人对话脚本 -> CosyVoice 按角色分配音色逐句合成 ->
 * 拼接为一段 MP3(MP3 帧可直接顺序拼接)-> 落盘,podcast 表记录状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodcastService {

    private static final int MAX_CONTEXT_CHARS = 16_000;
    private static final SpeechSynthesisAudioFormat FMT = SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS;

    private final NotebookLmProperties props;
    private final ChatCompletion chat;
    private final ChunkRepository chunkRepo;
    private final PodcastRepository podcastRepo;
    private final ObjectMapper objectMapper;

    /** 创建 PENDING 记录并异步生成脚本+音频。noteIds 为空则基于整本笔记本。 */
    public Podcast submit(Long notebookId, List<Long> noteIds) {
        Podcast p = new Podcast();
        p.setNotebookId(notebookId);
        p.setStatus("PENDING");
        p = podcastRepo.save(p);
        generateAsync(p.getId(), noteIds);
        return p;
    }

    @Async
    public void generateAsync(Long podcastId, List<Long> noteIds) {
        Podcast p = podcastRepo.findById(podcastId).orElse(null);
        if (p == null) return;
        try {
            String context = buildContext(p.getNotebookId(), noteIds);
            if (context.isBlank()) {
                fail(p, "该笔记本暂无资料,无法生成播客。");
                return;
            }

            // 1) 生成脚本
            p.setStatus("SCRIPTING");
            podcastRepo.save(p);
            PodcastScript script = generateScript(context);
            p.setTitle(script.getTitle());
            p.setScript(objectMapper.writeValueAsString(script));
            podcastRepo.save(p);

            // 2) 合成音频
            p.setStatus("SYNTHESIZING");
            podcastRepo.save(p);
            Path audioPath = synthesize(p.getNotebookId(), podcastId, script);
            p.setAudioPath(audioPath.toString());
            p.setStatus("DONE");
            podcastRepo.save(p);
            log.info("podcast done: id={} turns={} file={}", podcastId, script.getTurns().size(), audioPath);
        } catch (Exception e) {
            log.error("podcast failed: id={}", podcastId, e);
            fail(p, e.getMessage());
        }
    }

    private PodcastScript generateScript(String context) throws Exception {
        String system = "你是一档知识类播客的编剧。请把资料改写成两位主持人(A 与 B)的自然、口语化对话,"
                + "A 偏向抛出问题与引导,B 偏向讲解与补充,内容忠于资料、不编造。";
        String user = "请基于以下资料生成一期约 8-14 轮的双人对话播客脚本。"
                + "严格输出 JSON,格式为:{\"title\":\"标题\",\"turns\":[{\"speaker\":\"A\",\"text\":\"...\"},{\"speaker\":\"B\",\"text\":\"...\"}]}。"
                + "speaker 只能是 A 或 B,text 为该主持人这轮要说的话(简体中文,口语化,不含旁白与括号)。"
                + "不要输出 JSON 以外的任何内容,不要用 markdown 代码块包裹。\n\n=== 资料内容 ===\n" + context;
        String raw = chat.complete(system, user);
        String json = extractJson(raw);
        return objectMapper.readValue(json, PodcastScript.class);
    }

    /** 逐句合成并拼接为一个 MP3 文件。 */
    private Path synthesize(Long notebookId, Long podcastId, PodcastScript script) throws Exception {
        Path dir = Paths.get(props.getStorage().getAudioDir(), String.valueOf(notebookId))
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path out = dir.resolve("podcast_" + podcastId + ".mp3");

        String voiceA = props.getDashscope().getTtsVoiceA();
        String voiceB = props.getDashscope().getTtsVoiceB();

        try (OutputStream os = Files.newOutputStream(out)) {
            for (PodcastScript.Turn turn : script.getTurns()) {
                if (turn.getText() == null || turn.getText().isBlank()) continue;
                String voice = "B".equalsIgnoreCase(turn.getSpeaker()) ? voiceB : voiceA;
                ByteBuffer audio = tts(turn.getText(), voice);
                if (audio != null && audio.hasRemaining()) {
                    byte[] bytes = new byte[audio.remaining()];
                    audio.get(bytes);
                    os.write(bytes);
                }
            }
        }
        return out;
    }

    private ByteBuffer tts(String text, String voice) {
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .apiKey(props.getDashscope().getApiKey())
                .model(props.getDashscope().getTtsModel())
                .voice(voice)
                .format(FMT)
                .build();
        SpeechSynthesizer synth = new SpeechSynthesizer(param, null);
        return synth.call(text);
    }

    private String buildContext(Long notebookId, List<Long> noteIds) {
        List<Chunk> chunks = (noteIds != null && !noteIds.isEmpty())
                ? chunkRepo.findByNoteIdInOrderBySourceIdAscSeqAsc(noteIds)
                : chunkRepo.findByNotebookIdOrderBySourceIdAscSeqAsc(notebookId);
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (c.getContent() == null) continue;
            if (sb.length() + c.getContent().length() > MAX_CONTEXT_CHARS) break;
            sb.append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 容错:从可能包裹了代码块的输出里抠出 JSON 主体。 */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return s;
    }

    private void fail(Podcast p, String msg) {
        p.setStatus("FAILED");
        p.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        podcastRepo.save(p);
    }
}
