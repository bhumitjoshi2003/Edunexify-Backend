package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardSetupTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReportCardSetupTermRepository extends JpaRepository<ReportCardSetupTerm, Long> {
    List<ReportCardSetupTerm> findBySetupIdAndSchoolIdOrderByDisplayOrderAscIdAsc(Long setupId, Long schoolId);
    /** Bulk delete, executed immediately (so re-inserting the same rows in one transaction is safe). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ReportCardSetupTerm x WHERE x.setupId = :setupId AND x.schoolId = :schoolId")
    int deleteAllForSetup(@Param("setupId") Long setupId, @Param("schoolId") Long schoolId);
}
