package io.llmnote.companion.dto;

/** 亲密度里程碑(7/30/100 天触发时由 Service 推给前端,落库一条 SYSTEM 消息)。 */
public record Milestone(String kind, String text) {
}
