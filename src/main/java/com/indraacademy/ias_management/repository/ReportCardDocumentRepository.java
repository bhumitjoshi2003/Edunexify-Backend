package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardDocument;
import com.indraacademy.ias_management.entity.ReportCardPublicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReportCardDocumentRepository extends JpaRepository<ReportCardDocument, Long> {
    Optional<ReportCardDocument> findByIdAndSchoolId(Long id, Long schoolId);
    /** Public verification: tokens are globally unique, so no school context is needed. */
    Optional<ReportCardDocument> findByVerificationToken(String verificationToken);
    List<ReportCardDocument> findByPublicationIdAndSchoolIdOrderByStudentNameAscStudentIdAsc(Long publicationId, Long schoolId);
    List<ReportCardDocument> findBySchoolIdAndStudentIdAndStatusOrderByIssuedAtDesc(Long schoolId, String studentId, ReportCardPublicationStatus status);
    boolean existsBySetupIdAndStudentIdAndSchoolIdAndStatus(Long setupId, String studentId, Long schoolId, ReportCardPublicationStatus status);
    List<ReportCardDocument> findBySetupIdAndSchoolIdAndStatus(Long setupId, Long schoolId, ReportCardPublicationStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReportCardDocument d SET d.status = :status, d.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE d.publicationId = :publicationId AND d.schoolId = :schoolId")
    int updateStatusForPublication(@Param("publicationId") Long publicationId, @Param("schoolId") Long schoolId,
                                   @Param("status") ReportCardPublicationStatus status);
}
