package io.llmnote.news;

import io.llmnote.auth.Principal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 新闻收集入口:提交方向触发异步收集、列出/查询任务(前端轮询状态)。 */
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;
    private final NewsJobRepository jobRepo;

    /** 提交一个新闻方向,异步收集。返回 PENDING 占位记录。 */
    @PostMapping
    public NewsJob submit(@RequestBody NewsReq req, Principal principal) {
        return newsService.submit(principal.ownerId(), req == null ? null : req.getTopic());
    }

    /** 列出该主体的新闻任务(最新在前)。 */
    @GetMapping
    public List<NewsJob> list(Principal principal) {
        return newsService.list(principal.ownerId());
    }

    /** 查询单个任务(轮询用)。 */
    @GetMapping("/{id}")
    public NewsJob get(@PathVariable Long id, Principal principal) {
        return jobRepo.findById(id)
                .filter(j -> j.getOwnerId().equals(principal.ownerId()))
                .orElseThrow(() -> new IllegalArgumentException("news job not found: " + id));
    }

    @Data
    public static class NewsReq {
        private String topic;
    }
}
