package io.llmnote.companion.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.companion.CompanionTemplate;
import io.llmnote.companion.CompanionTemplateRepository;
import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.PersonalityTag;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 启动时从 classpath:companion-init/templates.json 加载 12 个伴侣模板到 companion_template 表。
 * 已存在(template_key 主键相同)则跳过,不覆盖用户后续可能做的调整。
 */
@Component
public class CompanionTemplateSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanionTemplateSeeder.class);
    private static final String TEMPLATES_RESOURCE = "companion-init/templates.json";

    private final CompanionTemplateRepository repository;
    private final ObjectMapper objectMapper;

    public CompanionTemplateSeeder(CompanionTemplateRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (repository.count() > 0) {
            log.info("companion_template 已有数据,跳过 seeder");
            return;
        }
        try (InputStream in = new ClassPathResource(TEMPLATES_RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            if (!root.isArray()) {
                log.warn("{} 顶层不是数组,跳过", TEMPLATES_RESOURCE);
                return;
            }
            List<CompanionTemplate> templates = new ArrayList<>();
            for (JsonNode node : root) {
                Optional<CompanionTemplate> t = parse(node);
                t.ifPresent(templates::add);
            }
            repository.saveAll(templates);
            log.info("companion_template seeder: 加载 {} 条", templates.size());
        }
    }

    private Optional<CompanionTemplate> parse(JsonNode node) {
        try {
            CompanionTemplate t = new CompanionTemplate();
            t.setTemplateKey(textOrNull(node, "template_key"));
            t.setName(textOrNull(node, "name"));
            t.setGender(parseEnum(Gender.class, textOrNull(node, "gender"), Gender.UNSPECIFIED));
            t.setAge(node.path("age").asInt(25));
            t.setPersonalityTags(toEnumNames(node.path("personality_tags")));
            t.setBio(textOrNull(node, "bio"));
            t.setVoiceStyle(parseEnum(VoiceStyle.class, textOrNull(node, "voice_style"), VoiceStyle.SWEET));
            t.setRelationshipType(parseEnum(RelationshipType.class,
                    textOrNull(node, "relationship_type"), RelationshipType.INTIMATE));
            t.setIntimacyStart(node.path("intimacy_start").asInt(30));
            t.setSpeakingStyle(parseEnum(SpeakingStyle.class, textOrNull(node, "speaking_style"), SpeakingStyle.CASUAL));
            t.setBackgroundStory(textOrNull(node, "background_story"));
            t.setDefaultModel(textOrNull(node, "default_model"));
            if (t.getTemplateKey() == null || t.getName() == null) {
                log.warn("template 缺少 template_key 或 name,跳过: {}", node);
                return Optional.empty();
            }
            return Optional.of(t);
        } catch (Exception e) {
            log.warn("解析 template 失败: {}", node, e);
            return Optional.empty();
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * personality_tags 在 JSON 中是中文标签数组(如 ["温柔体贴","知性优雅"]),
     * 落库前转换为 PersonalityTag 枚举名数组(如 ["GENTLE","ELEGANT"])。
     * 未匹配到的中文标签保留原文,以便后续人工补枚举后重跑 seeder。
     */
    private String toEnumNames(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return "[]";
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : arr) {
            String label = item.asText();
            String matched = matchLabel(label);
            result.add(matched != null ? matched : label);
        }
        return "[" + String.join(",", result.stream().map(s -> "\"" + s + "\"").toList()) + "]";
    }

    private String matchLabel(String label) {
        for (PersonalityTag tag : PersonalityTag.values()) {
            if (tag.getLabel().equals(label)) {
                return tag.name();
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
