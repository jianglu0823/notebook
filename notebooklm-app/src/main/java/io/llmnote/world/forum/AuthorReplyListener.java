package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.forum.event.CommentAddedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 论坛「作者偶尔回一嘴」监听器:订阅 {@link CommentAddedEvent},
 * 5-30s 随机 delay 后掷骰子(默认 15% 命中)决定是否调 {@link AuthorReplyGenerator}
 * 生成作者回复并 {@link ForumInteractionService#addComment}。
 * 同一帖子 5min cooldown,避免连续刷屏。
 *
 * <p>{@code author-reply.enabled=false} 时整个 bean 不被注册(@ConditionalOnProperty),
 * 事件发布后无副作用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "author-reply.enabled", havingValue = "true", matchIfMissing = true)
public class AuthorReplyListener {

    private final AgentForumPostRepository postRepo;
    private final AgentEmployeeRepository employeeRepo;
    private final ForumQueryService forumQueryService;
    private final AuthorReplyGenerator replyGen;
    private final ForumInteractionService interactionService;

    /** 注入用 Random;测试可 seed 化,prod 默认 new Random()。 */
    private Random random = new Random();

    /** postId → 上次回复时间戳 ms。重启失效(acceptable,best-effort)。 */
    private final Map<Long, Long> lastReplyAtByPost = new ConcurrentHashMap<>();

    @Value("${author-reply.probability:0.15}")
    private double probability;

    @Value("${author-reply.min-delay-seconds:5}")
    private int minDelaySeconds;

    @Value("${author-reply.max-delay-seconds:30}")
    private int maxDelaySeconds;

    @Value("${author-reply.cooldown-ms:300000}")
    private long cooldownMs;

    private volatile long minDelayMs;
    private volatile long maxDelayMs;

    public AuthorReplyListener(AgentForumPostRepository postRepo,
                               AgentEmployeeRepository employeeRepo,
                               ForumQueryService forumQueryService,
                               AuthorReplyGenerator replyGen,
                               ForumInteractionService interactionService) {
        this.postRepo = postRepo;
        this.employeeRepo = employeeRepo;
        this.forumQueryService = forumQueryService;
        this.replyGen = replyGen;
        this.interactionService = interactionService;
    }

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void init() {
        this.minDelayMs = (long) minDelaySeconds * 1000L;
        this.maxDelayMs = (long) maxDelaySeconds * 1000L;
        log.info("AuthorReplyListener inited probability={} delay={}~{}s cooldown={}ms",
                probability, minDelaySeconds, maxDelaySeconds, cooldownMs);
    }

    @Async
    @EventListener
    public void onCommentAdded(CommentAddedEvent event) {
        try {
            handle(event);
        } catch (Exception ex) {
            // 整个 listener 兜底:不让异步线程死掉影响后续事件
            log.warn("AuthorReplyListener onCommentAdded failed: {}", ex.getMessage(), ex);
        }
    }

    /** 同步逻辑(供测试直接调,避开 @Async 代理)。 */
    void handle(CommentAddedEvent event) {
        // 1) delay 5-30s
        sleepRandomDelay();

        Long postId = event.postId();
        Long authorAgentId = event.authorAgentId();

        // 2) cooldown 检查
        long now = System.currentTimeMillis();
        Long lastAt = lastReplyAtByPost.get(postId);
        if (lastAt != null && now - lastAt < cooldownMs) {
            log.info("AuthorReply skipped, cooldown postId={} age={}ms", postId, now - lastAt);
            return;
        }

        // 3) 帖子还在?
        Optional<AgentForumPost> postOpt = postRepo.findById(postId);
        if (postOpt.isEmpty()) {
            log.warn("AuthorReply skipped, post {} not found (may be deleted)", postId);
            return;
        }
        AgentForumPost post = postOpt.get();

        // 4) 作者 agent 还在 / active?
        if (authorAgentId == null) {
            log.info("AuthorReply skipped, no authorAgentId for postId={}", postId);
            return;
        }
        Optional<AgentEmployee> authorOpt = employeeRepo.findById(authorAgentId);
        if (authorOpt.isEmpty() || !"active".equals(authorOpt.get().getStatus())) {
            log.info("AuthorReply skipped, author agent {} inactive/missing postId={}", authorAgentId, postId);
            return;
        }
        AgentEmployee author = authorOpt.get();

        // 5) 帖子还有评论?(防止我们成为最后一条评论时回复自己)
        ForumQueryService.CommentPage latest = forumQueryService.listComments(postId, 0, 1);
        if (latest == null || latest.comments() == null || latest.comments().isEmpty()) {
            log.info("AuthorReply skipped, no comments left postId={}", postId);
            return;
        }
        AgentComment targetComment = latest.comments().get(0);

        // 6) 概率掷骰
        if (random.nextDouble() >= probability) {
            log.info("AuthorReply skipped, probability={} postId={}", probability, postId);
            return;
        }

        // 7) 命中:生成回复 → addComment → 更新 cooldown
        String reply = replyGen.generate(author.getPersona(), targetComment.getContent(),
                post.getTitle(), post.getContent());
        if (reply == null || reply.isBlank()) {
            log.warn("AuthorReply gen empty, skipped postId={}", postId);
            return;
        }
        try {
            interactionService.addComment(postId, String.valueOf(authorAgentId), author.getName(), reply);
            lastReplyAtByPost.put(postId, System.currentTimeMillis());
            log.info("AuthorReply sent postId={} author={} reply='{}'", postId, author.getName(), reply);
        } catch (Exception ex) {
            log.warn("AuthorReply addComment failed postId={}: {}", postId, ex.getMessage());
        }
    }

    private void sleepRandomDelay() {
        long lo = Math.min(minDelayMs, maxDelayMs);
        long hi = Math.max(minDelayMs, maxDelayMs);
        if (hi <= 0) return;
        long delta = hi - lo;
        long sleep = lo + (delta <= 0 ? 0 : ThreadLocalRandom.current().nextLong(delta + 1));
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
