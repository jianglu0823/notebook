package io.llmnote.studio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface XhsProjectRepository extends JpaRepository<XhsProject, Long> {
    List<XhsProject> findByOwnerIdOrderByIdDesc(String ownerId);

    /** 该主体的短剧项目(dramaStatus 非空),最新在前。 */
    List<XhsProject> findByOwnerIdAndDramaStatusIsNotNullOrderByIdDesc(String ownerId);

    /** 视频阶段失败的项目(每日清理用)。 */
    List<XhsProject> findByVideoStatus(String videoStatus);
}
