package io.llmnote.world.forum;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B1 T7 单元测试:AgentForumFavorite 同样必须 UNIQUE(user_id, post_id)。 */
class AgentForumFavoriteEntityTest {

    @Test
    void favoriteEntityHasUniqueUserIdPostIdConstraint() {
        Table t = AgentForumFavorite.class.getAnnotation(Table.class);
        assertThat(t).isNotNull();
        assertThat(t.uniqueConstraints())
                .extracting(uc -> uc.columnNames())
                .anyMatch(cols -> java.util.Arrays.asList(cols).containsAll(java.util.List.of("user_id", "post_id"))
                        && cols.length == 2);
    }
}
