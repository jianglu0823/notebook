package io.llmnote.auth;

import io.llmnote.log.AccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 注册 / 登录 / 当前身份。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final AccessLogService accessLogService;

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Credentials req, HttpServletRequest httpReq) {
        String username = req.getUsername().trim();
        if (userRepo.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordHasher.hash(req.getPassword()));
        u = userRepo.save(u);
        accessLogService.logAuth(httpReq, "REGISTER", u.getId(), u.getUsername(), false);
        return tokenResponse(u);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Credentials req, HttpServletRequest httpReq) {
        String username = req.getUsername().trim();
        User u = userRepo.findByUsername(username)
                .filter(x -> passwordHasher.matches(req.getPassword(), x.getPasswordHash()))
                .orElse(null);
        if (u == null) {
            accessLogService.logAuth(httpReq, "LOGIN_FAIL", null, username, false);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        accessLogService.logAuth(httpReq, "LOGIN", u.getId(), u.getUsername(), false);
        return tokenResponse(u);
    }

    /** 当前身份:注册用户返回 userId+username,游客返回 guest=true。 */
    @GetMapping("/me")
    public Map<String, Object> me(Principal principal) {
        if (principal == null || principal.guest()) {
            return Map.of("guest", true);
        }
        String username = userRepo.findById(principal.userId())
                .map(User::getUsername).orElse(null);
        return Map.of("guest", false, "userId", principal.userId(), "username", username);
    }

    private Map<String, Object> tokenResponse(User u) {
        return Map.of("token", jwtService.sign(u.getId()), "userId", u.getId(), "username", u.getUsername());
    }

    @Data
    public static class Credentials {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }
}
