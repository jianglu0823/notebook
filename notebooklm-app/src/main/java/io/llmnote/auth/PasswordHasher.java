package io.llmnote.auth;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;

/** BCrypt 密码哈希。 */
@Component
public class PasswordHasher {

    public String hash(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt());
    }

    public boolean matches(String raw, String hash) {
        try {
            return BCrypt.checkpw(raw, hash);
        } catch (Exception e) {
            return false;
        }
    }
}
