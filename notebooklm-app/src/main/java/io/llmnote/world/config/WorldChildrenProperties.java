package io.llmnote.world.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * town-children-rules:孩子出生规则的外部化配置。
 * 头像池 + 每对夫妇最大孩子数,均在 application.yml 的 world.children.* 暴露。
 * 缺省值在字段初始化器给,保证部署无 key 时仍可用。
 */
@Data
@ConfigurationProperties(prefix = "world.children")
public class WorldChildrenProperties {

    /** 默认 9 个儿童 emoji。skin-tone 修饰符提供视觉多样性,不引入 sex 字段。 */
    private List<String> avatarPool = List.of("👶", "👧", "👦", "🧒", "🧒🏻", "🧒🏼", "🧒🏽", "🧒🏾", "🧒🏿");

    /** 每对夫妇最大孩子数。达到后 WorldSimEngine 不再让该夫妇怀上。 */
    private int maxPerCouple = 3;

    /**
     * 确定性选 avatar:hash(childName) mod pool.size()。
     * 同名同池 → 同一 emoji;空池 / null 池 → 回退 👶(保留旧行为)。
     * 确定性是 R-4 迁移幂等的关键:重启时同一行算出同一 emoji,不会改写。
     */
    public String pickAvatar(String childName) {
        if (avatarPool == null || avatarPool.isEmpty()) {
            return "👶";
        }
        int h = childName == null ? 0 : childName.hashCode();
        return avatarPool.get(Math.floorMod(h, avatarPool.size()));
    }
}
