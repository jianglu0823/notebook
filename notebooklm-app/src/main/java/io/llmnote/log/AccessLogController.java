package io.llmnote.log;

import io.llmnote.auth.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 查看自己的登录/操作日志(按 owner 隔离,仅返回当前主体的记录)。 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class AccessLogController {

    private final AccessLogRepository repo;

    @GetMapping
    public List<AccessLog> mine(Principal principal) {
        if (principal == null) {
            return List.of();
        }
        return repo.findByOwnerIdOrderByIdDesc(principal.ownerId());
    }
}
