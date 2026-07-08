package io.llmnote.notebook;

import io.llmnote.auth.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotebookService {

    /** 内置只读笔记本的 owner_id:对所有用户可见、可读、可问答,但不可编辑/删除/上传。 */
    public static final String SYSTEM_OWNER = "system";

    private final NotebookRepository notebookRepo;
    private final SourceRepository sourceRepo;

    /** 列出:当前主体自己的笔记本 + 内置系统笔记本(系统笔记本置顶)。 */
    public List<Notebook> list(String ownerId) {
        List<Notebook> result = new ArrayList<>();
        if (!SYSTEM_OWNER.equals(ownerId)) {
            result.addAll(notebookRepo.findByOwnerIdOrderByIdDesc(SYSTEM_OWNER));
        }
        result.addAll(notebookRepo.findByOwnerIdOrderByIdDesc(ownerId));
        return result;
    }

    /** 按 id 取并校验归属;不属于当前主体(或不存在)一律当作不存在。 */
    public Notebook getOwned(Long id, String ownerId) {
        return notebookRepo.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ForbiddenException("notebook not found: " + id));
    }

    /** 读取校验:本人的笔记本或内置系统笔记本均可读;用于只读/问答等非修改入口。 */
    public Notebook getReadable(Long id, String ownerId) {
        return notebookRepo.findById(id)
                .filter(nb -> ownerId.equals(nb.getOwnerId()) || SYSTEM_OWNER.equals(nb.getOwnerId()))
                .orElseThrow(() -> new ForbiddenException("notebook not found: " + id));
    }

    /** 该笔记本是否为内置系统笔记本(前端只读判定亦可用)。 */
    public boolean isSystem(Notebook nb) {
        return nb != null && SYSTEM_OWNER.equals(nb.getOwnerId());
    }

    @Transactional
    public Notebook create(String name, String description, String ownerId) {
        Notebook nb = new Notebook();
        nb.setName(name);
        nb.setDescription(description);
        nb.setOwnerId(ownerId);
        return notebookRepo.save(nb);
    }

    @Transactional
    public Notebook update(Long id, String name, String description, String ownerId) {
        Notebook nb = getOwned(id, ownerId);
        if (name != null && !name.isBlank()) {
            nb.setName(name.trim());
        }
        nb.setDescription(description);
        return notebookRepo.save(nb);
    }

    @Transactional
    public void delete(Long id, String ownerId) {
        Notebook nb = getOwned(id, ownerId);
        notebookRepo.delete(nb);
    }

    public List<Source> listSources(Long notebookId, String ownerId) {
        getReadable(notebookId, ownerId);
        return sourceRepo.findByNotebookId(notebookId);
    }
}
