package io.llmnote.studio;

import io.llmnote.auth.Principal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多智能体协同写作工作台入口(方案7)。提交主题后异步跑 作者⇄编辑⇄核查 迭代循环,
 * 前端轮询 GET /{id} 逐轮「围观」协作过程。按 principal.ownerId() 做数据隔离。
 */
@RestController
@RequestMapping("/api/studio/writing")
@RequiredArgsConstructor
public class WritingController {

    private final WritingAgentService service;

    /** 提交写作主题,异步启动多智能体协作。 */
    @PostMapping
    public WritingProject create(@RequestBody CreateReq req, Principal principal) {
        return service.create(principal.ownerId(),
                req == null ? null : req.getTopic(),
                req == null ? null : req.getGenre(),
                req == null ? null : req.getMaxRounds());
    }

    @GetMapping
    public List<WritingProject> list(Principal principal) {
        return service.list(principal.ownerId());
    }

    /** 轮询单个项目(围观协作进度)。 */
    @GetMapping("/{id}")
    public WritingProject get(@PathVariable Long id, Principal principal) {
        return service.get(id, principal.ownerId());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.ownerId());
    }

    @Data
    public static class CreateReq {
        private String topic;
        private String genre;
        private Integer maxRounds;
    }
}
