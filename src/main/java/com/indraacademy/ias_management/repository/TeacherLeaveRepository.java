package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.TeacherLeave;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherLeaveRepository extends JpaRepository<TeacherLeave, Long> {

    Optional<TeacherLeave> findByIdAndSchoolId(Long id, Long schoolId);

    /** Row-locked read for updateStatus's check-then-update — mirrors LeaveRepository.findByIdForUpdate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TeacherLeave t where t.id = :id")
    Optional<TeacherLeave> findByIdForUpdate(@Param("id") Long id);

    /** PENDING or APPROVED requests of one teacher overlapping a date range (optionally excluding one). */
    @Query("SELECT COUNT(t) > 0 FROM TeacherLeave t WHERE t.schoolId = :schoolId AND t.teacherId = :teacherId " +
           "AND t.status IN (com.indraacademy.ias_management.entity.LeaveStatus.PENDING, " +
           "com.indraacademy.ias_management.entity.LeaveStatus.APPROVED) " +
           "AND t.startDate <= :rangeEnd AND t.endDate >= :rangeStart AND (:excludeId IS NULL OR t.id <> :excludeId)")
    boolean existsActiveOverlapping(@Param("schoolId") Long schoolId, @Param("teacherId") String teacherId,
                                    @Param("rangeStart") LocalDate rangeStart, @Param("rangeEnd") LocalDate rangeEnd,
                                    @Param("excludeId") Long excludeId);

    long countByStatusAndSchoolId(LeaveStatus status, Long schoolId);

    Page<TeacherLeave> findByTeacherIdAndSchoolIdOrderByStartDateDesc(String teacherId, Long schoolId, Pageable pageable);

    /**
     * Admin review queue, filterable by status, teacher and/or a date covered by the leave.
     * Null filters mean "no filter" —
     * mirrors LeaveRepository.findForReview's shape.
     */
    @Query("SELECT t FROM TeacherLeave t WHERE t.schoolId = :schoolId " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:teacherId IS NULL OR t.teacherId = :teacherId) " +
           "AND (:date IS NULL OR (t.startDate <= :date AND t.endDate >= :date)) " +
           "ORDER BY t.appliedDate DESC")
    Page<TeacherLeave> findFiltered(@Param("schoolId") Long schoolId,
                                    @Param("status") LeaveStatus status,
                                    @Param("teacherId") String teacherId,
                                    @Param("date") LocalDate date,
                                    Pageable pageable);

    /**
     * APPROVED leaves overlapping a date range — the query TeacherAttendanceService's calendar
     * reconciliation uses to resolve a missing row to ON_LEAVE instead of ABSENT. Batch-fetched
     * once per range (mirroring SchoolHolidayRepository.findOverlapping), not queried per date.
     */
    @Query("SELECT t FROM TeacherLeave t WHERE t.schoolId = :schoolId AND t.status = 'APPROVED' " +
           "AND t.startDate <= :rangeEnd AND t.endDate >= :rangeStart")
    List<TeacherLeave> findApprovedOverlapping(@Param("schoolId") Long schoolId,
                                               @Param("rangeStart") LocalDate rangeStart,
                                               @Param("rangeEnd") LocalDate rangeEnd);
}
