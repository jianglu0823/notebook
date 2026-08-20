package io.llmnote.world.forum.event;

/**
 * 论坛评论落库后派发的事件:W2 AuthorReplyListener 监听,经 5-30s delay +
 * 概率掷骰决定是否调 AuthorReplyGenerator 生成作者回复。
 * 事件仅在评论事务 afterCommit 后发布,避免 listener 读未提交数据。
 */
public record CommentAddedEvent(
        Long postId,
        Long commentId,
        Long authorAgentId) {
}
