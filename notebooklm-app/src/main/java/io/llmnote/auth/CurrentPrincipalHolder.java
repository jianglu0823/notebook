package io.llmnote.auth;

/** 请求线程内的当前主体。AuthFilter 在 servlet 线程设置,请求结束清理。 */
public final class CurrentPrincipalHolder {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private CurrentPrincipalHolder() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
