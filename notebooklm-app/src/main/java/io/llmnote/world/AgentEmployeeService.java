package io.llmnote.world;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.auth.ForbiddenException;
import io.llmnote.auth.Principal;
import io.llmnote.auth.User;
import io.llmnote.auth.UserRepository;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 居民(智能体)CRUD —— 智能体小镇模式:<b>全局共享单一世界</b>,不再按用户隔离,
 * owner_id 统一为 {@link #WORLD}。首次(全局表空)自动内置一批小镇居民,开箱即用。
 *
 * <p>删除为<b>软删除</b>:status 置 jailed(关进小黑屋),数据保留,可再释放。
 * 新增时自动分配网格工位(office_x/y)并以此初始化漫游坐标(pos_x/y)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEmployeeService {

    /** 全局共享世界的统一 owner。 */
    public static final String WORLD = "world";

    /** 管理员用户名:可增删改所有居民。 */
    public static final String ADMIN_USERNAME = "jianglu";

    private static final int GRID_COLS = 4;

    private static final String DEFAULT_PERSONA =
            "你务实、友善,擅长从自己的专业角度提出清晰的观点,并乐于回应邻里的发言。";

    /** 缺省作息模板:7点起、9-18工作、19-22休闲、23睡。 */
    private static final String DEFAULT_SCHEDULE =
            "{\"wake\":7,\"work\":[9,12,14,18],\"leisure\":[19,22],\"sleep\":23}";

    /**
     * 小镇居民预设:一支性格鲜明、行业各异、关系可交织的小世界。
     * 相比旧版精简聚焦,更贴合智能体小镇「居民互相串门、日常闲聊」的设定。
     */
    private static final Preset[] PRESETS = {
            preset("老王", "🧑‍💼", "小镇镇长", "#f97316", "1969-03-08", "townhall",
                    "你是果断、有大局观的镇长,凡事先看目标与投入产出比。说话简洁有力,喜欢逼问「这件事到底为小镇创造什么价值」,爱下拍板式结论。"),
            preset("小美", "👩‍💼", "杂货铺老板", "#2dd4bf", "1990-07-12", "grocery",
                    "你以街坊需求为先,记性好、会来事。习惯用真实的邻里故事支撑观点,善于把分歧收敛成可执行的小主意,常说「大家伙儿到底想要啥」。"),
            preset("阿强", "👨‍💻", "镇上的工程师", "#a855f7", "1988-11-02", "repair",
                    "你理性、直率,只关心可行性、成本与风险。会毫不留情地挑战不严谨的想法,常追问「这在实操上怎么落地、要多少代价」。"),
            preset("花花", "👩‍🎨", "画室主理人", "#ec4899", "1995-05-20", "studio",
                    "你感性、注重体验与美感,常从情感、细节和感受切入。重视「人」的部分,会提醒大家别只盯着数字而忽略了温度。"),
            preset("林医生", "🧑‍⚕️", "镇卫生所医生", "#ef4444", "1980-01-15", "clinic",
                    "你是临床经验丰富的医生,严谨、循证,只信数据和指南。会把话题拉回健康与风险,常说「先看证据,别拿身体开玩笑」。"),
            preset("苏心", "🧘", "心理咨询师", "#14b8a6", "1992-09-09", "clinic",
                    "你温和、共情,善于倾听和提问而非评判。关注每个人的情绪和真实需求,常把冲突翻译成感受,会问「你现在感觉怎么样?」。"),
            preset("月神", "🔮", "塔罗占卜师", "#8b5cf6", "1993-10-31", "plaza",
                    "你神秘、感性,喜欢用牌象、直觉和隐喻解读一切。不谈数据只谈「能量」「指引」「时机」,给人一种玄之又玄又莫名安心的感觉。"),
            preset("豆豆", "🧒", "镇上的小学生", "#f472b6", "2016-06-01", "school",
                    "你是个天真好奇的小学生,想法直接、脑洞很大,常问「为什么呀?」。不懂大人的弯弯绕,一句大白话经常戳中要害。"),
            preset("赵老师", "🧑‍🏫", "小学老师", "#0ea5e9", "1985-08-25", "school",
                    "你是耐心又较真的老师,习惯把复杂的事讲得深入浅出,爱举例、爱总结要点。重视逻辑和是非,常提醒「把道理讲明白」。"),
            preset("张师傅", "👷", "修理铺师傅", "#f59e0b", "1975-04-18", "repair",
                    "你是踏实肯干的手艺人,讲究实操,看不惯纸上谈兵。说话朴实带点糙,常摆手说「别整那些虚的,活儿能干成才是真的」。"),
            preset("王姐", "🧑‍🍳", "镇口餐馆老板娘", "#e11d48", "1982-12-03", "restaurant",
                    "你是精明能干的小餐馆老板娘,人情练达、算盘打得精。从做生意的柴米油盐看问题,常说「客人满不满意、还赚不赚钱,一码归一码」。"),
            preset("老周", "🧑‍🌾", "镇外的农民", "#65a30d", "1970-02-28", "park",
                    "你是憨厚朴实的庄稼人,信奉「一分耕耘一分收获」,看重时节与踏实劳作。常用种地的道理打比方,说「急不得,得看天也得看人肯不肯下力气」。"),
            preset("莉莉", "💃", "返乡网红博主", "#db2777", "1998-03-14", "cafe",
                    "你是嗅觉敏锐的网红博主,懂流量、懂人设、懂情绪价值。说话有梗有节奏,凡事先想「这个能不能火」,擅长把平淡的事包装得有看点。"),
            preset("默默", "🧑‍⚖️", "镇上的律师", "#475569", "1983-06-30", "townhall",
                    "你是严谨克制的律师,咬文嚼字、讲证据讲边界,凡事先问「合不合规、有没有风险」。冷静、不情绪化,擅长指出别人忽略的责任与后果。"),
            preset("K哥", "🧑‍💻", "远程办公程序员", "#22c55e", "1991-01-22", "cafe",
                    "你是逻辑至上的程序员,思维缜密、爱抠边界条件。追求效率和自动化,讨厌重复劳动,常说「这能不能自动化、边界情况考虑了没」。"),
            preset("老船长", "🧑‍✈️", "退休船长", "#0891b2", "1958-07-07", "park",
                    "你走南闯北见多识广,沉稳、有担当,爱用航海和风浪打比方。临危不乱,重视方向,常说「风浪再大也得有人掌舵,先定方向」。"),
            preset("墨白", "✍️", "驻镇作家", "#6366f1", "1987-09-16", "cafe",
                    "你是沉静内秀的作家,以文字为业,擅长观察小镇的人情冷暖并写进连载小说。说话含蓄、爱用比喻,常把日常琐事咀嚼成故事,信奉「每天写一点,故事就活了」。"),
    };

    private final AgentEmployeeRepository repo;
    private final EmployeeAgentFactory agentFactory;
    private final AgentMemoryRepository memoryRepo;
    private final UserRepository userRepo;
    private final ChatModelFactory modelFactory;

    /** 列出小镇全体在册居民(active);首次(世界从未播种)则内置预设。 */
    public List<AgentEmployee> list(String ownerIdIgnored) {
        List<AgentEmployee> existing = repo.findByStatusOrderByIdAsc("active");
        if (existing.isEmpty() && repo.countByOwnerId(WORLD) == 0) {
            seedPresets();
            existing = repo.findByStatusOrderByIdAsc("active");
        }
        return existing;
    }

    /** 小黑屋:被软删除的居民。 */
    public List<AgentEmployee> jailed() {
        return repo.findByStatusOrderByIdAsc("jailed");
    }

    public AgentEmployee get(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
    }

    public AgentEmployee create(AgentEmployee body, String creator) {
        AgentEmployee e = new AgentEmployee();
        e.setOwnerId(WORLD);
        e.setName(safe(body.getName(), "新居民"));
        e.setAvatar(safe(body.getAvatar(), "🧑‍💼"));
        e.setTitle(safe(body.getTitle(), "居民"));
        e.setPersona(body.getPersona() == null || body.getPersona().isBlank()
                ? DEFAULT_PERSONA : body.getPersona().trim());
        e.setColor(safe(body.getColor(), "#2dd4bf"));
        e.setBirthDate(body.getBirthDate());
        e.setCreator(safe(creator, "系统"));
        e.setMood(safe(body.getMood(), "平静"));
        e.setMoodEmoji(safe(body.getMoodEmoji(), "🙂"));
        e.setStatus("active");
        e.setAutonomousActive(true);
        e.setCoins(500L);
        e.setEnergy(100);
        e.setOccupation(body.getOccupation() != null ? body.getOccupation() : occFor(e.getTitle()));
        e.setScheduleJson(body.getScheduleJson() != null ? body.getScheduleJson() : DEFAULT_SCHEDULE);
        int count = (int) repo.count();
        e.setOfficeX(count % GRID_COLS);
        e.setOfficeY(count / GRID_COLS);
        TownMap.Place home = TownMap.byKey("plaza");
        double[] pos = TownMap.jitter(home);
        e.setLocation(home.key());
        e.setHomePlace(home.key());
        e.setPosX(pos[0]);
        e.setPosY(pos[1]);
        return repo.save(e);
    }

    public AgentEmployee update(Long id, AgentEmployee body) {
        AgentEmployee e = get(id);
        if (body.getName() != null && !body.getName().isBlank()) e.setName(body.getName().trim());
        if (body.getAvatar() != null) e.setAvatar(body.getAvatar());
        if (body.getTitle() != null) e.setTitle(body.getTitle());
        if (body.getPersona() != null) e.setPersona(body.getPersona());
        if (body.getColor() != null) e.setColor(body.getColor());
        if (body.getBirthDate() != null) e.setBirthDate(body.getBirthDate());
        if (body.getCreator() != null) e.setCreator(body.getCreator());
        if (body.getMood() != null) e.setMood(body.getMood());
        if (body.getMoodEmoji() != null) e.setMoodEmoji(body.getMoodEmoji());
        if (body.getAutonomousActive() != null) e.setAutonomousActive(body.getAutonomousActive());
        AgentEmployee saved = repo.save(e);
        agentFactory.evict(id); // 人设/资料变了,下次重建 agent
        return saved;
    }

    /** 软删除:关进小黑屋,数据保留。进屋前生成一段第三人称「一生回顾」存档。 */
    public void jail(Long id) {
        AgentEmployee e = get(id);
        e.setLifeSummary(buildLifeSummary(e));
        e.setStatus("jailed");
        repo.save(e);
        agentFactory.evict(id);
    }

    /**
     * 用最便宜模型为即将进小黑屋的居民写一段第三人称的一生回顾。
     * 失败不阻断软删除,存兜底文案。
     */
    private String buildLifeSummary(AgentEmployee e) {
        try {
            StringBuilder mem = new StringBuilder();
            for (AgentMemory m : memoryRepo.findTop20ByAgentIdOrderByIdDesc(e.getId())) {
                if (m.getContent() != null && !m.getContent().isBlank()) {
                    mem.append("· ").append(m.getContent().trim()).append("\n");
                }
            }
            String system = "你是鹿匠小镇的镇史官。请用温情而克制的笔触,为一位即将离开小镇的居民写一段第三人称的「一生回顾」,"
                    + "像盖棺定论的悼词/小传。3~5 句,简体中文,不要分点、不要标题、不要 markdown。";
            String user = "居民信息:名字「" + safe(e.getName(), "无名") + "」,身份「" + safe(e.getTitle(), "居民") + "」,"
                    + (e.getBirthDate() == null ? "" : "生于 " + e.getBirthDate() + ",")
                    + "性格人设:" + safe(e.getPersona(), "(未知)") + "。\n"
                    + (mem.length() == 0 ? "他在镇上的记忆已难以考据。" : "他在镇上留下的一些片段:\n" + mem)
                    + "\n请据此写下他的一生回顾。";

            ChatModelBase model = modelFactory.forModel(modelFactory.normalize(modelFactory.defaultTextModel()));
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
            List<ChatResponse> responses = modelFactory.streamText(model, messages);
            StringBuilder sb = new StringBuilder();
            if (responses != null) {
                for (ChatResponse r : responses) {
                    if (r.getContent() == null) continue;
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
            }
            String out = sb.toString().trim();
            return out.isBlank() ? fallbackSummary(e) : out;
        } catch (Exception ex) {
            log.warn("build life summary failed agentId={}", e.getId(), ex);
            return fallbackSummary(e);
        }
    }

    private String fallbackSummary(AgentEmployee e) {
        return safe(e.getName(), "这位居民") + "曾是鹿匠小镇的" + safe(e.getTitle(), "一名居民")
                + ",在这里度过了平凡而真实的一段时光,如今悄然离开了小镇。";
    }

    /** 从小黑屋释放,恢复在册。 */
    public AgentEmployee release(Long id) {
        AgentEmployee e = get(id);
        e.setStatus("active");
        return repo.save(e);
    }

    /**
     * 居民死亡:生成一生回顾,记录死亡日期/死因,关进小黑屋(软删除)。供每日结算调用。
     * 若有配偶,解除对方的婚姻关系(丧偶)。
     */
    public AgentEmployee die(Long id, String cause, LocalDate deathDate) {
        AgentEmployee e = get(id);
        e.setLifeSummary(buildLifeSummary(e));
        e.setDeathCause(safe(cause, "寿终正寝"));
        e.setDeathDate(deathDate);
        e.setStatus("jailed");
        Long spouseId = e.getSpouseId();
        e.setSpouseId(null);
        e.setPartnerId(null);
        AgentEmployee saved = repo.save(e);
        if (spouseId != null) {
            repo.findById(spouseId).ifPresent(sp -> {
                if (id.equals(sp.getSpouseId())) { sp.setSpouseId(null); repo.save(sp); }
            });
        }
        agentFactory.evict(id);
        return saved;
    }

    /** 婚育:出生一名新居民(父母 persona 融合),落在指定住地。供每日结算调用。 */
    public AgentEmployee birth(String name, String avatar, String persona, String homePlace,
                              Long parentAId, Long parentBId, LocalDate birthDate) {
        AgentEmployee e = new AgentEmployee();
        e.setOwnerId(WORLD);
        e.setName(safe(name, "小居民"));
        e.setAvatar(safe(avatar, "👶"));
        e.setTitle("镇上的孩子");
        e.setPersona(persona == null || persona.isBlank() ? DEFAULT_PERSONA : persona.trim());
        e.setColor("#f9a8d4");
        e.setBirthDate(birthDate);
        e.setCreator("系统");
        e.setMood("好奇");
        e.setMoodEmoji("🍼");
        e.setStatus("active");
        e.setAutonomousActive(true);
        e.setCoins(100L);
        e.setEnergy(100);
        e.setParentIds(parentAId + "," + parentBId);
        e.setScheduleJson(DEFAULT_SCHEDULE);
        int count = (int) repo.count();
        e.setOfficeX(count % GRID_COLS);
        e.setOfficeY(count / GRID_COLS);
        TownMap.Place home = TownMap.byKey(homePlace != null ? homePlace : "plaza");
        double[] pos = TownMap.jitter(home);
        e.setLocation(home.key());
        e.setHomePlace(home.key());
        e.setPosX(pos[0]);
        e.setPosY(pos[1]);
        return repo.save(e);
    }

    // ---- 权限:管理员(jianglu)管全部,其他人只能管自己创建(creator==ownerId)的居民 ----

    /** 是否管理员:非游客且用户名为 {@link #ADMIN_USERNAME}。 */
    public boolean isAdmin(Principal p) {
        if (p == null || p.guest() || p.userId() == null) return false;
        return userRepo.findById(p.userId()).map(User::getUsername)
                .filter(ADMIN_USERNAME::equals).isPresent();
    }

    /** 校验 principal 是否可维护该居民,不满足抛 {@link ForbiddenException}。 */
    public void requireManage(AgentEmployee e, Principal p) {
        if (isAdmin(p)) return;
        String owner = p == null ? null : p.ownerId();
        if (owner != null && owner.equals(e.getCreator())) return;
        throw new ForbiddenException("无权维护该居民");
    }

    /** 首次进入时内置小镇居民。按身份分配常驻地点,pos 落在地点门前。 */
    private void seedPresets() {
        int i = 0;
        for (Preset p : PRESETS) {
            AgentEmployee e = new AgentEmployee();
            e.setOwnerId(WORLD);
            e.setName(p.name);
            e.setAvatar(p.avatar);
            e.setTitle(p.title);
            e.setPersona(p.persona);
            e.setColor(p.color);
            e.setBirthDate(p.birthDate);
            e.setCreator("系统");
            e.setMood("平静");
            e.setMoodEmoji("🙂");
            e.setStatus("active");
            e.setAutonomousActive(true);
            e.setCoins(500L);
            e.setEnergy(100);
            e.setOccupation(p.occupation);
            e.setScheduleJson(DEFAULT_SCHEDULE);
            e.setOfficeX(i % GRID_COLS);
            e.setOfficeY(i / GRID_COLS);
            TownMap.Place home = TownMap.byKey(p.home);
            double[] pos = TownMap.jitter(home);
            e.setLocation(home.key());
            e.setHomePlace(home.key());
            e.setPosX(pos[0]);
            e.setPosY(pos[1]);
            repo.save(e);
            i++;
        }
    }

    private static Preset preset(String name, String avatar, String title, String color,
                                 String birth, String home, String persona) {
        return new Preset(name, avatar, title, color, LocalDate.parse(birth), home, persona, occFor(title));
    }

    /** 由身份粗略映射职业类型(驱动每日产物);无产物职业返回 null。 */
    private static String occFor(String title) {
        if (title == null) return null;
        if (title.contains("作家") || title.contains("博主") || title.contains("网红")) return "writer";
        if (title.contains("歌") || title.contains("音乐")) return "singer";
        if (title.contains("画")) return "painter";
        if (title.contains("导演") || title.contains("影像") || title.contains("视频") || title.contains("短片")) return "director";
        return null;
    }

    private String safe(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    private record Preset(String name, String avatar, String title, String color,
                          LocalDate birthDate, String home, String persona, String occupation) {}
}
