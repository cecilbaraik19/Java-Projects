package com.cecil.docqa.repository;

import com.cecil.docqa.model.QaRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QaRecordRepository extends JpaRepository<QaRecord, Long> {
    List<QaRecord> findTop50ByOrderByIdDesc();
}
