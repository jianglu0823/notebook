package io.llmnote.companion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionTemplateRepository extends JpaRepository<CompanionTemplate, String> {

    long count();

    List<CompanionTemplate> findAllByOrderByNameAsc();
}
