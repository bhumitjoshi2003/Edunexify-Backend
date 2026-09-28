package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardRemarkV2;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReportCardRemarkV2Repository extends JpaRepository<ReportCardRemarkV2, Long> {
    List<ReportCardRemarkV2> findBySetupIdAndSchoolId(Long setupId, Long schoolId);
    Optional<ReportCardRemarkV2> findBySetupIdAndStudentIdAndSchoolId(Long setupId, String studentId, Long schoolId);
    boolean existsBySetupIdAndSchoolId(Long setupId, Long schoolId);
}
