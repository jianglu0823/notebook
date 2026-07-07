package io.llmnote.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.llmnote.config.NotebookLmProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** 自签 JWT:sub = userId。 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(NotebookLmProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = props.getAuth().getJwtExpirySeconds();
    }

    public String sign(long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirySeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** 解析并校验;失败返回 null(交由调用方回退游客)。 */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}
