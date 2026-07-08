package io.llmnote.news;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.llmnote.note.Note;
import io.llmnote.note.NoteService;
import io.llmnote.notebook.Notebook;
import io.llmnote.notebook.NotebookRepository;
import io.llmnote.notebook.NotebookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 新闻收集:用户选方向 → qwen 开启联网搜索(enable_search)抓取最新动态 → 整理成 HTML 正文 → 写入一条笔记。
 * 异步任务,状态写入 news_job(PENDING → GENERATING → DONE/FAILED);所有新闻笔记落固定的"新闻动态"笔记本(自动创建)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    /** 固定的新闻笔记本名称;每个 owner 首次收集时自动创建。 */
    public static final String NEWS_NOTEBOOK_NAME = "新闻动态";

    private final DashScopeChatModel chatModel;
    private final NewsJobRepository jobRepo;
    private final NotebookRepository notebookRepo;
    private final NotebookService notebookService;
    private final NoteService noteService;

    /** 创建一个 PENDING 任务并异步收集。返回占位记录供前端轮询。 */
    public NewsJob submit(String ownerId, String topic) {
        NewsJob job = new NewsJob();
        job.setOwnerId(ownerId);
        job.setTopic(topic == null || topic.isBlank() ? "综合" : topic.trim());
        job.setStatus("PENDING");
        job = jobRepo.save(job);
        collectAsync(job.getId());
        return job;
    }

    public List<NewsJob> list(String ownerId) {
        return jobRepo.findByOwnerIdOrderByIdDesc(ownerId);
    }

    @Async
    public void collectAsync(Long jobId) {
        NewsJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        try {
            job.setStatus("GENERATING");
            jobRepo.save(job);

            String html = collect(job.getTopic());
            if (html == null || html.isBlank()) {
                fail(job, "未能获取到新闻内容,请稍后重试。");
                return;
            }

            Notebook nb = ensureNewsNotebook(job.getOwnerId());
            String title = job.getTopic() + " · " + LocalDate.now();
            Note note = noteService.create(nb.getId(), title, "RICHTEXT", job.getOwnerId());
            noteService.updateBody(nb.getId(), note.getId(), title, html, job.getOwnerId());

            job.setNotebookId(nb.getId());
            job.setNoteId(note.getId());
            job.setStatus("DONE");
            jobRepo.save(job);
            log.info("news done: job={} topic={} note={}", jobId, job.getTopic(), note.getId());
        } catch (Exception e) {
            log.error("news failed: job={}", jobId, e);
            fail(job, e.getMessage());
        }
    }

    private void fail(NewsJob job, String msg) {
        job.setStatus("FAILED");
        job.setErrorMsg(msg);
        jobRepo.save(job);
    }

    /** 取该 owner 的"新闻动态"笔记本,不存在则创建。 */
    private Notebook ensureNewsNotebook(String ownerId) {
        return notebookRepo.findByOwnerIdOrderByIdDesc(ownerId).stream()
                .filter(nb -> NEWS_NOTEBOOK_NAME.equals(nb.getName()))
                .findFirst()
                .orElseGet(() -> notebookService.create(NEWS_NOTEBOOK_NAME, "由新闻收集功能自动整理的最新动态", ownerId));
    }

    /** 调 qwen(开启联网搜索)收集该方向最新新闻,返回可直接作为笔记正文的 HTML 片段。 */
    private String collect(String topic) {
        String system = "你是一名专业的新闻编辑。请使用联网搜索获取最新、真实的新闻动态,严禁编造或使用过时信息。"
                + "输出必须是可直接嵌入网页的 HTML 片段(不要 markdown、不要 ```、不要 <html>/<body> 外层标签),"
                + "使用 <h3> 分组、<ul>/<li> 罗列条目,每条包含标题(<strong>)、简要摘要与(若有)来源/时间。语言为简体中文。";
        String user = "请收集「" + topic + "」方向近期(尽量最近几天)的重要新闻动态,整理为 6-10 条,"
                + "按重要性排序,并在开头用一句话概述当前该领域的整体态势。今天是 " + LocalDate.now() + "。";

        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of("enable_search", true))
                .build();

        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());

        List<ChatResponse> responses = chatModel.stream(messages, List.of(), options)
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
        return sanitize(sb.toString());
    }

    /** 去掉模型可能多带的 markdown 代码围栏。 */
    private String sanitize(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("(?s)^```[a-zA-Z]*\\s*", "").replaceFirst("(?s)\\s*```\\s*$", "");
        }
        return t.trim();
    }
}
