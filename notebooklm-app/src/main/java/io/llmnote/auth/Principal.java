package io.llmnote.auth;

/**
 * 请求主体。注册用户与游客统一用 ownerId 字符串标识,直接作为 notebook.owner_id 存储:
 * 注册用户 → "u:<userId>";游客 → "g:<uuid>"。
 */
public record Principal(String ownerId, boolean guest, Long userId) {

    public static Principal ofUser(long userId) {
        return new Principal("u:" + userId, false, userId);
    }

    public static Principal ofGuest(String uuid) {
        return new Principal("g:" + uuid, true, null);
    }
}
