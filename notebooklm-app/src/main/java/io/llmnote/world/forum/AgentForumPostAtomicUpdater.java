package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 原子计数 ±1(避免 read-modify-write race):
 * UPDATE agent_forum_post SET like_count = like_count + 1 WHERE id = :id
 * 等价于乐观锁,MySQL 8 + InnoDB 单 SQL 原子操作。
 */
public interface AgentForumPostAtomicUpdater extends Repository<AgentForumPost, Long> {

    @Modifying
    @Query("update AgentForumPost p set p.likeCount = p.likeCount + 1 where p.id = :id")
    int incrementLikeCount(@Param("id") Long id);

    @Modifying
    @Query("update AgentForumPost p set p.likeCount = case when p.likeCount > 0 then p.likeCount - 1 else 0 end where p.id = :id")
    int decrementLikeCount(@Param("id") Long id);

    @Modifying
    @Query("update AgentForumPost p set p.favoriteCount = p.favoriteCount + 1 where p.id = :id")
    int incrementFavoriteCount(@Param("id") Long id);

    @Modifying
    @Query("update AgentForumPost p set p.favoriteCount = case when p.favoriteCount > 0 then p.favoriteCount - 1 else 0 end where p.id = :id")
    int decrementFavoriteCount(@Param("id") Long id);

    @Modifying
    @Query("update AgentForumPost p set p.commentCount = p.commentCount + 1 where p.id = :id")
    int incrementCommentCount(@Param("id") Long id);
}
