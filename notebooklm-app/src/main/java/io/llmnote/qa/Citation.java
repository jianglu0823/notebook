package io.llmnote.qa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条引用出处:指向某个 source 的某个切块。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Citation {
    private int index;          // 角标序号,从 1 开始
    private Long sourceId;
    private String sourceName;
    private Long chunkId;
    private int seq;            // 块在 source 内的序号
    private String snippet;     // 片段预览
}
