package io.llmnote.companion;

import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.config.NotebookLmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanionUploadServiceTest {

    private CompanionUploadService service;
    private Path tmpDir;

    @BeforeEach
    void setup() throws IOException {
        NotebookLmProperties props = new NotebookLmProperties();
        props.getStorage().setImageDir(Files.createTempDirectory("comp-upload-test").toString());
        CompanionProperties cp = new CompanionProperties();
        cp.setMaxUploadBytes(1024 * 1024);
        service = new CompanionUploadService(props, cp);
        tmpDir = Paths.get(props.getStorage().getImageDir());
    }

    @Test
    void save_validPng_createsFile() throws IOException {
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", new byte[]{(byte)0x89, 'P', 'N', 'G'});
        CompanionUploadService.Saved s = service.save("u:1", f);
        assertThat(s.url()).startsWith("/uploads/companion/").endsWith(".png");
        assertThat(Files.exists(tmpDir.resolve("companion"))).isTrue();
    }

    @Test
    void save_nonImage_rejects() {
        MockMultipartFile f = new MockMultipartFile("file", "x.txt", "text/plain", "hi".getBytes());
        assertThatThrownBy(() -> service.save("u:1", f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void save_oversize_rejects() {
        CompanionProperties cp = new CompanionProperties();
        cp.setMaxUploadBytes(10);
        NotebookLmProperties props = new NotebookLmProperties();
        props.getStorage().setImageDir(tmpDir.toString());
        CompanionUploadService small = new CompanionUploadService(props, cp);
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", new byte[100]);
        assertThatThrownBy(() -> small.save("u:1", f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小");
    }
}
