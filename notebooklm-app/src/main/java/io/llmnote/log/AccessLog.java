package io.llmnote.log;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 登录 / 注册 / 写操作的访问日志:记录主体、动作、IP、设备类型。 */
@Data
@Entity
@Table(name = "access_log")
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "user_id")
    private Long userId;

    private String username;

    private boolean guest;

    @Column(nullable = false)
    private String action;

    private String method;

    private String path;

    private Integer status;

    private String ip;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
