package io.llmnote.world;

import java.util.List;

/**
 * 智能体小镇风格的<b>具名地点地图</b>——纯常量,不建表。
 * 世界坐标系 {@link #WORLD_W}×{@link #WORLD_H}(与前端画布等比映射),
 * 一组固定 {@link Place} 手工排布成不重叠的镇图,是前后端画建筑/落位的<b>单一数据源</b>。
 *
 * <p>居民「去某地点」时把 {@code agent_employee.location} 设为地点 key,
 * {@code pos_x/pos_y} 落到 {@link #jitter(Place)} 抖动坐标(避免同地扎堆重叠)。
 */
public final class TownMap {

    /** 世界坐标系尺寸(前端按容器宽等比缩放到画布)。 */
    public static final double WORLD_W = 960;
    public static final double WORLD_H = 760;

    /** 一处具名地点:key(存库)+ 中文名 + 门牌 emoji + 世界坐标。 */
    public record Place(String key, String name, String emoji, double x, double y) {}

    /** 手工排布的镇图:四行大致铺开,中心广场居中,四周环绕各类场所 + 婚礼堂/墓园/集市/住宅。 */
    private static final List<Place> PLACES = List.of(
            new Place("townhall",   "镇公所",   "🏛", 480, 110),
            new Place("cafe",       "咖啡馆",   "☕", 180, 150),
            new Place("school",     "学校",     "🏫", 780, 150),
            new Place("clinic",     "卫生所",   "🏥", 150, 330),
            new Place("plaza",      "中心广场", "⛲", 480, 330),
            new Place("studio",     "画室",     "🎨", 810, 330),
            new Place("grocery",    "杂货铺",   "🛒", 180, 520),
            new Place("restaurant", "餐馆",     "🍜", 480, 520),
            new Place("park",       "公园",     "🌳", 760, 500),
            new Place("repair",     "修理铺",   "🔧", 810, 545),
            new Place("chapel",     "婚礼堂",   "💒", 320, 660),
            new Place("cemetery",   "墓园",     "🪦", 110, 660),
            new Place("market",     "集市",     "🎪", 560, 660),
            new Place("home1",      "枫叶小屋", "🏠", 720, 660),
            new Place("home2",      "溪畔小院", "🏡", 880, 660)
    );

    private TownMap() {}

    public static List<Place> all() {
        return PLACES;
    }

    /** 按 key 精确取地点,未命中回退中心广场。 */
    public static Place byKey(String key) {
        if (key != null) {
            for (Place p : PLACES) {
                if (p.key().equals(key)) return p;
            }
        }
        return plaza();
    }

    private static Place plaza() {
        for (Place p : PLACES) if ("plaza".equals(p.key())) return p;
        return PLACES.get(0);
    }

    /** 模糊匹配 key 或中文名(含子串),失败回退中心广场。 */
    public static Place match(String nameOrKey) {
        if (nameOrKey == null || nameOrKey.isBlank()) return plaza();
        String s = nameOrKey.trim();
        for (Place p : PLACES) {
            if (p.key().equalsIgnoreCase(s) || p.name().equals(s)) return p;
        }
        for (Place p : PLACES) {
            if (s.contains(p.name()) || p.name().contains(s)) return p;
        }
        return plaza();
    }

    /** 在地点周围 ±36 抖动,避免同地点小人完全重叠。返回 [x, y]。 */
    public static double[] jitter(Place p) {
        double jx = p.x() + (Math.random() - 0.5) * 72;
        double jy = p.y() + 24 + (Math.random() - 0.5) * 48; // 略偏建筑下方(门前)
        jx = Math.max(24, Math.min(WORLD_W - 24, jx));
        jy = Math.max(24, Math.min(WORLD_H - 24, jy));
        return new double[]{jx, jy};
    }
}
