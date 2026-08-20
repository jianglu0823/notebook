package io.llmnote.world.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldChildrenPropertiesTest {

    /** 默认值:9 个 emoji 池 + 上限 3(R-1 / R-3 scenario "defaults applied when keys absent")。 */
    @Test
    void defaults_appliedWhenNoConfig() {
        WorldChildrenProperties p = new WorldChildrenProperties();
        assertThat(p.getAvatarPool()).containsExactly("👶", "👧", "👦", "🧒", "🧒🏻", "🧒🏼", "🧒🏽", "🧒🏾", "🧒🏿");
        assertThat(p.getMaxPerCouple()).isEqualTo(3);
    }

    /** 自定义覆盖(R-3 scenario "custom config wins")。 */
    @Test
    void customConfigWins() {
        WorldChildrenProperties p = new WorldChildrenProperties();
        p.setAvatarPool(List.of("🐣", "🐤"));
        p.setMaxPerCouple(5);
        assertThat(p.getAvatarPool()).containsExactly("🐣", "🐤");
        assertThat(p.getMaxPerCouple()).isEqualTo(5);
    }

    /** pickAvatar 池内且确定性(R-1 scenario "same parents produce varied avatars" + 幂等 R-4)。 */
    @Test
    void pickAvatar_isInPoolAndDeterministic() {
        WorldChildrenProperties p = new WorldChildrenProperties();
        List<String> pool = p.getAvatarPool();
        // 确定性:同一名字 10 次都返回同一个值
        for (int i = 0; i < 10; i++) {
            assertThat(p.pickAvatar("墨小宝")).isEqualTo(p.pickAvatar("墨小宝"));
        }
        // 在池内
        for (String name : List.of("墨小宝", "老王的孩子", "小美的小宝", "豆豆的妹妹", "阿强的娃")) {
            assertThat(pool).contains(p.pickAvatar(name));
        }
        // 5 个不同名字横跨默认池,至少看到 2 个不同的值
        long distinct = List.of("墨小宝", "老王的孩子", "小美的小宝", "豆豆的妹妹", "阿强的娃")
                .stream().map(p::pickAvatar).distinct().count();
        assertThat(distinct).isGreaterThanOrEqualTo(2);
    }

    /** 空池降级到 👶(R-1 scenario "empty pool falls back")。 */
    @Test
    void pickAvatar_emptyPoolFallsBackToBaby() {
        WorldChildrenProperties p = new WorldChildrenProperties();
        p.setAvatarPool(List.of());
        assertThat(p.pickAvatar("墨小宝")).isEqualTo("👶");
    }

    /** null 池也降级到 👶(防御性)。 */
    @Test
    void pickAvatar_nullPoolFallsBackToBaby() {
        WorldChildrenProperties p = new WorldChildrenProperties();
        p.setAvatarPool(null);
        assertThat(p.pickAvatar("墨小宝")).isEqualTo("👶");
    }

    /** null name 走 hash(0)不抛。 */
    @Test
    void pickAvatar_nullNameDoesNotThrow() {
        WorldChildrenProperties p = new WorldChildrenProperties();
        assertThat(p.getAvatarPool()).contains(p.pickAvatar(null));
    }
}
