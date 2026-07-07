package io.llmnote.notebook;

import io.llmnote.auth.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotebookService {

    private final NotebookRepository notebookRepo;
    private final SourceRepository sourceRepo;

    public List<Notebook> list(String ownerId) {
        return notebookRepo.findByOwnerIdOrderByIdDesc(ownerId);
    }

    /** 按 id 取并校验归属;不属于当前主体(或不存在)一律当作不存在。 */
    public Notebook getOwned(Long id, String ownerId) {
        return notebookRepo.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ForbiddenException("notebook not found: " + id));
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
    public void delete(Long id, String ownerId) {
        Notebook nb = getOwned(id, ownerId);
        notebookRepo.delete(nb);
    }

    public List<Source> listSources(Long notebookId, String ownerId) {
        getOwned(notebookId, ownerId);
        return sourceRepo.findByNotebookId(notebookId);
    }
}
