package io.llmnote.world.forum;

import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 小镇居民随机互动任务:每 5-15 分钟(内部 sleep)醒来一次,
 * 拉最近 24h 的 published 帖 → 抽 1-3 个 → 对每帖抽 1-2 个非作者 active agent →
 * 每个 agent 独立掷骰子决定 like / favorite / comment(可叠加,任一失败不阻塞其他)。
 *
 * <p>概率、间隔、每 tick 帖 / agent 数全部走 application.yml 配置,
 * {@code enabled=false} 时 {@link Scheduled} 仍触发但 {@code tick()} 第一行 return。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "resident-interaction.enabled", havingValue = "true", matchIfMissing = true)
public class ResidentInteractionJob {

    private final ForumQueryService forumQueryService;
    private final AgentEmployeeRepository employeeRepo;
    private final ResidentCommentGenerator commentGen;
    private final ForumInteractionService interactionService;

    /** 注入用 {@link Random};测试可 seed 化,prod 默认 new Random()。 */
    private Random random = new Random();

    @Value("${resident-interaction.enabled:true}")
    private boolean enabled;

    @Value("${resident-interaction.min-interval-minutes:5}")
    private int minIntervalMinutes;

    @Value("${resident-interaction.max-interval-minutes:15}")
    private int maxIntervalMinutes;

    @Value("${resident-interaction.max-posts-per-tick:3}")
    private int maxPostsPerTick;

    @Value("${resident-interaction.max-agents-per-post:2}")
    private int maxAgentsPerPost;

    @Value("${resident-interaction.like-probability:0.5}")
    private double likeProbability;

    @Value("${resident-interaction.favorite-probability:0.3}")
    private double favoriteProbability;

    @Value("${resident-interaction.comment-probability:0.6}")
    private double commentProbability;

    @Value("${resident-interaction.lookback-hours:24}")
    private int lookbackHours;

    @Value("${resident-interaction.candidate-post-page-size:30}")
    private int candidatePostPageSize;

    /** 算作 ms 缓存,避免每次 tick 重算。 */
    private volatile long minIntervalMs;
    private volatile long maxIntervalMs;

    public ResidentInteractionJob(ForumQueryService forumQueryService,
                                  AgentEmployeeRepository employeeRepo,
                                  ResidentCommentGenerator commentGen,
                                  ForumInteractionService interactionService) {
        this.forumQueryService = forumQueryService;
        this.employeeRepo = employeeRepo;
        this.commentGen = commentGen;
        this.interactionService = interactionService;
    }

    /** Spring 注入 @Value 之后,做一次 ms 换算,让 tick() 拿到的是 long 范围。 */
    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        this.minIntervalMs = (long) minIntervalMinutes * 60_000L;
        this.maxIntervalMs = (long) maxIntervalMinutes * 60_000L;
        log.info("ResidentInteractionJob inited enabled={} interval={}~{}min like={} fav={} comment={}",
                enabled, minIntervalMinutes, maxIntervalMinutes,
                likeProbability, favoriteProbability, commentProbability);
    }

    @Scheduled(fixedDelay = 5L * 60_000L, initialDelay = 60_000L)
    public void tick() {
        if (!enabled) {
            log.info("ResidentInteractionJob skipped, enabled=false");
            return;
        }
        // 内部 sleep 到 [min, max] 区间,避免所有 agent 同时刷
        sleepRandomInterval();

        try {
            ForumQueryService.PostPage page = forumQueryService.listPosts(null, 0, candidatePostPageSize);
            if (page == null || page.posts() == null || page.posts().isEmpty()) {
                log.info("ResidentInteractionJob: no candidate posts");
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusHours(lookbackHours);
            List<AgentForumPost> candidates = new ArrayList<>();
            for (AgentForumPost p : page.posts()) {
                if (!"published".equals(p.getStatus())) continue;
                if (p.getCreatedAt() == null || p.getCreatedAt().isBefore(cutoff)) continue;
                candidates.add(p);
            }
            if (candidates.isEmpty()) {
                log.info("ResidentInteractionJob: no recent posts in {}h", lookbackHours);
                return;
            }
            int pickN = 1 + random.nextInt(Math.max(1, maxPostsPerTick));
            Collections.shuffle(candidates, random);
            List<AgentForumPost> picked = candidates.subList(0, Math.min(pickN, candidates.size()));

            List<AgentEmployee> allActive = employeeRepo.findByStatusOrderByIdAsc("active");
            if (allActive == null || allActive.isEmpty()) return;

            for (AgentForumPost post : picked) {
                processPost(post, allActive);
            }
        } catch (Exception ex) {
            log.warn("ResidentInteractionJob tick failed: {}", ex.getMessage(), ex);
        }
    }

    /** 抽 1-maxAgentsPerPost 个非作者 agent,各自掷三个独立骰子。 */
    private void processPost(AgentForumPost post, List<AgentEmployee> allActive) {
        List<AgentEmployee> nonAuthors = new ArrayList<>();
        for (AgentEmployee e : allActive) {
            if (!e.getId().equals(post.getAuthorAgentId())) nonAuthors.add(e);
        }
        if (nonAuthors.isEmpty()) return;
        Collections.shuffle(nonAuthors, random);
        int pickN = Math.min(nonAuthors.size(), maxAgentsPerPost);
        List<AgentEmployee> picked = nonAuthors.subList(0, pickN);
        for (AgentEmployee agent : picked) {
            rollAndAct(post, agent);
        }
    }

    /** 三个动作互相独立,任一异常 catch + log,不影响其他。 */
    private void rollAndAct(AgentForumPost post, AgentEmployee agent) {
        Long userId = agent.getId();
        String authorId = String.valueOf(agent.getId());
        String name = agent.getName();
        String persona = agent.getPersona();

        // like
        if (random.nextDouble() < likeProbability) {
            try {
                interactionService.like(userId, post.getId());
            } catch (Exception ex) {
                log.warn("resident like failed post={} agent={}: {}", post.getId(), name, ex.getMessage());
            }
        }
        // favorite
        if (random.nextDouble() < favoriteProbability) {
            try {
                interactionService.favorite(userId, post.getId());
            } catch (Exception ex) {
                log.warn("resident favorite failed post={} agent={}: {}", post.getId(), name, ex.getMessage());
            }
        }
        // comment
        if (random.nextDouble() < commentProbability) {
            String text = commentGen.generate(persona, name, post.getTitle(), post.getContent(), post.getCategory());
            if (text == null || text.isBlank()) {
                log.warn("resident comment gen empty, skipped post={} agent={}", post.getId(), name);
                return;
            }
            try {
                interactionService.addComment(post.getId(), authorId, name, text);
            } catch (Exception ex) {
                log.warn("resident addComment failed post={} agent={}: {}", post.getId(), name, ex.getMessage());
            }
        }
    }

    private void sleepRandomInterval() {
        long lo = Math.min(minIntervalMs, maxIntervalMs);
        long hi = Math.max(minIntervalMs, maxIntervalMs);
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
