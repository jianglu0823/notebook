package io.llmnote.world.forum;

import io.llmnote.llm.ZhipuMediaClient;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.AgentProduct;
import io.llmnote.world.AgentProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B3 T15~T18 单元测试:ForumMediaService 异步生成图片/视频附件 + 写 agent_product。 */
@ExtendWith(MockitoExtension.class)
class ForumMediaServiceTest {

    @Mock ZhipuMediaClient mediaClient;
    @Mock AgentForumAttachmentRepository attachRepo;
    @Mock AgentProductRepository productRepo;
    @Mock AgentEmployeeRepository employeeRepo;

    @Test
    void joke_post_generates2Images_attachmentsAndProductsPersisted() {
        when(mediaClient.generateImage(anyString())).thenReturn("http://img/0.png", "http://img/1.png");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(LocalDate.class), anyInt(), anyString()))
                .thenReturn("world/1/2026-08-12-0.png", "world/1/2026-08-12-1.png");
        when(attachRepo.save(any(AgentForumAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        ForumMediaService svc = new ForumMediaService(mediaClient, attachRepo, productRepo, employeeRepo);
        AgentForumPost post = post(100L, 1L, "alice", "joke", "哈哈哈哈今天天气真好啊");

        svc.generateAttachments(post);

        ArgumentCaptor<AgentForumAttachment> capAttach = ArgumentCaptor.forClass(AgentForumAttachment.class);
        verify(mediaClient, org.mockito.Mockito.times(2)).generateImage(anyString());
        verify(attachRepo, org.mockito.Mockito.times(2)).save(capAttach.capture());
        List<AgentForumAttachment> attaches = capAttach.getAllValues();
        assertThat(attaches.get(0).getSeq()).isEqualTo(0);
        assertThat(attaches.get(1).getSeq()).isEqualTo(1);
        assertThat(attaches.get(0).getMediaType()).isEqualTo("image");

        ArgumentCaptor<AgentProduct> capProd = ArgumentCaptor.forClass(AgentProduct.class);
        verify(productRepo, org.mockito.Mockito.times(2)).save(capProd.capture());
        assertThat(capProd.getAllValues()).allMatch(p -> p.getRefPostId().equals(100L));
        assertThat(capProd.getAllValues()).allMatch(p -> "image".equals(p.getKind()));
        // 帖子无标题 → 作品标题从正文截取,不落 NULL(作品馆"无题"根因)
        assertThat(capProd.getAllValues()).allMatch(p -> "哈哈哈哈今天天气真好啊".equals(p.getTitle()));
    }

    @Test
    void untitledPost_longContent_productTitleTruncatedWithEllipsis() {
        when(mediaClient.generateImage(anyString())).thenReturn("http://img/0.png", "http://img/1.png");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(LocalDate.class), anyInt(), anyString()))
                .thenReturn("world/1/2026-08-12-0.png", "world/1/2026-08-12-1.png");
        when(attachRepo.save(any(AgentForumAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        ForumMediaService svc = new ForumMediaService(mediaClient, attachRepo, productRepo, employeeRepo);
        AgentForumPost post = post(104L, 1L, "alice", "daily",
                "今天去了很远的地方散步看到了很美的晚霞心情特别舒畅想分享给大家");

        svc.generateAttachments(post);

        ArgumentCaptor<AgentProduct> capProd = ArgumentCaptor.forClass(AgentProduct.class);
        verify(productRepo, org.mockito.Mockito.times(2)).save(capProd.capture());
        assertThat(capProd.getAllValues()).allMatch(p ->
                "今天去了很远的地方散步看到了很美…".equals(p.getTitle()) && p.getTitle().length() == 17);
    }

    @Test
    void titledPost_productKeepsPostTitle() {
        when(mediaClient.submitVideo(anyString())).thenReturn("task-9");
        when(mediaClient.pollVideo(anyString(), anyInt(), anyLong())).thenReturn("http://video/9.mp4");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(LocalDate.class), anyInt(), anyString()))
                .thenReturn("world/1/2026-08-12-9.mp4");
        when(attachRepo.save(any(AgentForumAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        ForumMediaService svc = new ForumMediaService(mediaClient, attachRepo, productRepo, employeeRepo);
        AgentForumPost post = post(105L, 1L, "alice", "video_series", "剧情简介");
        post.setTitle("小镇风云第一集");

        svc.generateAttachments(post);

        ArgumentCaptor<AgentProduct> capProd = ArgumentCaptor.forClass(AgentProduct.class);
        verify(productRepo).save(capProd.capture());
        assertThat(capProd.getValue().getTitle()).isEqualTo("小镇风云第一集");
    }

    @Test
    void joke_post_imageFailure_dropsThatAttachment_keepsPost() {
        when(mediaClient.generateImage(anyString())).thenReturn(null, "http://img/1.png");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(LocalDate.class), anyInt(), anyString()))
                .thenReturn("world/1/2026-08-12-1.png");
        when(attachRepo.save(any(AgentForumAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        ForumMediaService svc = new ForumMediaService(mediaClient, attachRepo, productRepo, employeeRepo);
        AgentForumPost post = post(101L, 1L, "alice", "joke", "内容内容内容内容内容内容内容内容内容内容内容内容");

        svc.generateAttachments(post);

        verify(attachRepo, org.mockito.Mockito.times(1)).save(any());
        verify(productRepo, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void chickenSoup_post_generatesExactly1Image() {
        when(mediaClient.generateImage(anyString())).thenReturn("http://img/0.png");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(LocalDate.class), anyInt(), anyString()))
                .thenReturn("world/1/2026-08-12-0.png");
        when(attachRepo.save(any(AgentForumAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        ForumMediaService svc = new ForumMediaService(mediaClient, attachRepo, productRepo, employeeRepo);
        AgentForumPost post = post(102L, 1L, "alice", "chicken_soup", "今天的鸡汤送给你");

        svc.generateAttachments(post);

        verify(mediaClient, org.mockito.Mockito.times(1)).generateImage(anyString());
        verify(mediaClient, never()).submitVideo(anyString());
    }

    @Test
    void videoSeries_post_generates1Video() {
        when(mediaClient.submitVideo(anyString())).thenReturn("task-1");
        when(mediaClient.pollVideo(anyString(), anyInt(), anyLong())).thenReturn("http://video/0.mp4");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(LocalDate.class), anyInt(), anyString()))
                .thenReturn("world/1/2026-08-12-0.mp4");
        when(attachRepo.save(any(AgentForumAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        ForumMediaService svc = new ForumMediaService(mediaClient, attachRepo, productRepo, employeeRepo);
        AgentForumPost post = post(103L, 1L, "alice", "video_series", "第一集剧情简介");

        svc.generateAttachments(post);

        verify(mediaClient).submitVideo(anyString());
        verify(mediaClient).pollVideo("task-1", 50, 12_000L);
    }

    private static AgentForumPost post(Long id, Long authorId, String name, String cat, String content) {
        AgentForumPost p = new AgentForumPost();
        p.setId(id);
        p.setAuthorAgentId(authorId);
        p.setAuthorName(name);
        p.setCategory(cat);
        p.setContent(content);
        p.setStatus("published");
        return p;
    }
}
