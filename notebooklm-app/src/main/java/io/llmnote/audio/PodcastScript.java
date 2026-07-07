package io.llmnote.audio;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 播客脚本:标题 + 双主持人对话轮次。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PodcastScript {
    private String title;
    private List<Turn> turns;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Turn {
        private String speaker;  // "A" 或 "B"
        private String text;
    }
}
