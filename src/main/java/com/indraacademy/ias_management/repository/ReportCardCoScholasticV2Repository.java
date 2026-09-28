package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardCoScholasticV2;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportCardCoScholasticV2Repository extends JpaRepository<ReportCardCoScholasticV2, Long> {
    List<ReportCardCoScholasticV2> findBySetupIdAndSchoolId(Long setupId, Long schoolId);
    boolean existsBySetupIdAndSchoolId(Long setupId, Long schoolId);
}
