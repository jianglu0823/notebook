package io.llmnote.companion;

import io.llmnote.companion.dto.ModelDto;
import io.llmnote.config.NotebookLmProperties.ModelPricing;
import io.llmnote.llm.ChatModelFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 伴侣可用模型清单(只读)。
 * - 来源:ChatModelFactory.pricing()(智谱免费 + 千问付费合并,按 label 字典序展示)。
 * - normalize() 透传 ChatModelFactory,非法名 → 默认。
 */
@Service
public class CompanionModelService {

    private final ChatModelFactory modelFactory;

    public CompanionModelService(ChatModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    public List<ModelDto> listModels() {
        return modelFactory.pricing().stream()
                .sorted(Comparator.comparing(ModelPricing::getLabel, String.CASE_INSENSITIVE_ORDER))
                .map(p -> new ModelDto(p.getName(), p.getLabel(), p.getInputPer1k(), p.getOutputPer1k()))
                .toList();
    }

    public String normalize(String name) {
        return modelFactory.normalize(name);
    }
}
