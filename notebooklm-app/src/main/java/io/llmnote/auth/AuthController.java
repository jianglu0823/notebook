package io.llmnote.auth;

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

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Credentials req) {
        String username = req.getUsername().trim();
        if (userRepo.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordHasher.hash(req.getPassword()));
        u = userRepo.save(u);
        return tokenResponse(u);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Credentials req) {
        User u = userRepo.findByUsername(req.getUsername().trim())
                .filter(x -> passwordHasher.matches(req.getPassword(), x.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
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
