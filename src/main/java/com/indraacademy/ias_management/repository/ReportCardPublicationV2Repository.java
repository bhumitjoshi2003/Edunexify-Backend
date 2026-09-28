package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardPublicationStatus;
import com.indraacademy.ias_management.entity.ReportCardPublicationV2;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReportCardPublicationV2Repository extends JpaRepository<ReportCardPublicationV2, Long> {
    Optional<ReportCardPublicationV2> findByIdAndSchoolId(Long id, Long schoolId);
    List<ReportCardPublicationV2> findBySetupIdAndSchoolIdOrderByPublishedAtDescIdDesc(Long setupId, Long schoolId);
    boolean existsBySetupIdAndSchoolId(Long setupId, Long schoolId);
    boolean existsBySetupIdAndSchoolIdAndStatus(Long setupId, Long schoolId, ReportCardPublicationStatus status);
    List<ReportCardPublicationV2> findBySetupIdAndSchoolIdAndStatus(Long setupId, Long schoolId, ReportCardPublicationStatus status);

    @Query("SELECT COALESCE(MAX(p.version), 0) FROM ReportCardPublicationV2 p WHERE p.setupId = :setupId AND p.schoolId = :schoolId AND p.sectionId IS NULL")
    int maxVersionForClass(@Param("setupId") Long setupId, @Param("schoolId") Long schoolId);

    @Query("SELECT COALESCE(MAX(p.version), 0) FROM ReportCardPublicationV2 p WHERE p.setupId = :setupId AND p.schoolId = :schoolId AND p.sectionId = :sectionId")
    int maxVersionForSection(@Param("setupId") Long setupId, @Param("schoolId") Long schoolId, @Param("sectionId") Long sectionId);

    /** Whether an exam is part of any ACTIVE V2 publication (the exam-unpublish guard). */
    @Query("SELECT COUNT(p) > 0 FROM ReportCardPublicationV2 p, ReportCardSetupExam e WHERE e.setupId = p.setupId "
            + "AND e.examConfigId = :examId AND p.schoolId = :schoolId AND p.status = com.indraacademy.ias_management.entity.ReportCardPublicationStatus.ACTIVE")
    boolean existsActiveForExam(@Param("examId") Long examId, @Param("schoolId") Long schoolId);
}
