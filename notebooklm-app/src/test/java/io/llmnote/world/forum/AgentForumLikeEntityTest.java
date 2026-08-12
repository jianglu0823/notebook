package io.llmnote.world.forum;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B1 T6 单元测试:AgentForumLike @Table 必须有 UNIQUE(user_id, post_id) 约束。 */
class AgentForumLikeEntityTest {

    @Test
    void likeEntityHasUniqueUserIdPostIdConstraint() {
        Table t = AgentForumLike.class.getAnnotation(Table.class);
        assertThat(t).as("@Table annotation").isNotNull();
        assertThat(t.uniqueConstraints())
                .extracting(uc -> uc.columnNames())
                .anyMatch(cols -> java.util.Arrays.asList(cols).containsAll(java.util.List.of("user_id", "post_id"))
                        && cols.length == 2);
    }
}
