package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardSetup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReportCardSetupRepository extends JpaRepository<ReportCardSetup, Long> {
    Optional<ReportCardSetup> findByIdAndSchoolId(Long id, Long schoolId);

    /** Serialises concurrent publishes of the same setup. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReportCardSetup s WHERE s.id = :id AND s.schoolId = :schoolId")
    Optional<ReportCardSetup> lockByIdAndSchoolId(@Param("id") Long id, @Param("schoolId") Long schoolId);
    List<ReportCardSetup> findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(
            Long schoolId, Long academicSessionId, Long classId);
    List<ReportCardSetup> findBySchoolIdAndAcademicSessionIdOrderByDisplayOrderAscNameAsc(Long schoolId, Long academicSessionId);
    List<ReportCardSetup> findBySchoolIdAndAcademicSessionIdAndClassId(Long schoolId, Long academicSessionId, Long classId);
}
