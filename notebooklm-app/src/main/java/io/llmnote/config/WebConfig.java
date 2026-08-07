package io.llmnote.config;

import io.llmnote.auth.PrincipalArgumentResolver;
import io.llmnote.log.AccessLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.List;

/** 开发期放开跨域,便于前端(独立端口或经网关)直连调试;并注册 Principal 参数解析器与访问日志拦截器。 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final PrincipalArgumentResolver principalArgumentResolver;
    private final AccessLogInterceptor accessLogInterceptor;
    private final NotebookLmProperties props;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessLogInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 暴露图片/音频/上传目录给浏览器直读(供伴侣 / 笔记 / Agent 资产共用)。
        String imageLoc = "file:" + Paths.get(props.getStorage().getImageDir()).toAbsolutePath() + "/";
        String uploadLoc = "file:" + Paths.get(props.getStorage().getUploadDir()).toAbsolutePath() + "/";
        String audioLoc = "file:" + Paths.get(props.getStorage().getAudioDir()).toAbsolutePath() + "/";
        registry.addResourceHandler("/images/**").addResourceLocations(imageLoc);
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploadLoc);
        registry.addResourceHandler("/audio/**").addResourceLocations(audioLoc);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(principalArgumentResolver);
    }
}
