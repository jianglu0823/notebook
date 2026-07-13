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

    /** 世界坐标系尺寸(前端按容器宽等比缩放到画布)。横向长方形,与页签同宽。 */
    public static final double WORLD_W = 1360;
    public static final double WORLD_H = 700;

    /** 一处具名地点:key(存库)+ 中文名 + 门牌 emoji + 世界坐标。 */
    public record Place(String key, String name, String emoji, double x, double y) {}

    /**
     * 横向井字网格镇图:五列(x=180/430/680/930/1180)× 三排(y=155/370/590),
     * 纵向街 x=305/555/805/1055,横向街 y=262/478,全部横平竖直,中心为「古井广场」。
     * 顶部窄带(y&lt;80)为群山 / 药王庙 / 采药人茅草屋,前端绘制,不作可交互地点。
     */
    private static final List<Place> PLACES = List.of(
            // 第一排(北)
            new Place("cafe",       "咖啡馆",   "☕", 180, 155),
            new Place("school",     "学堂",     "🏫", 430, 155),
            new Place("townhall",   "镇公所",   "🏛", 680, 155),
            new Place("studio",     "画室",     "🎨", 930, 155),
            new Place("home2",      "溪畔小院", "🏡", 1180, 155),
            // 第二排(中,含中心广场)
            new Place("clinic",     "回春医馆", "🏥", 180, 370),
            new Place("grocery",    "杂货铺",   "🛒", 430, 370),
            new Place("plaza",      "古井广场", "⛲", 680, 370),
            new Place("repair",     "匠作坊",   "🔧", 930, 370),
            new Place("park",       "公园",     "🌳", 1180, 370),
            // 第三排(南)
            new Place("cemetery",   "墓园",     "🪦", 180, 590),
            new Place("chapel",     "婚礼堂",   "💒", 430, 590),
            new Place("restaurant", "食肆",     "🍜", 680, 590),
            new Place("market",     "集市",     "🎪", 930, 590),
            new Place("home1",      "枫叶小屋", "🏠", 1180, 590)
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
