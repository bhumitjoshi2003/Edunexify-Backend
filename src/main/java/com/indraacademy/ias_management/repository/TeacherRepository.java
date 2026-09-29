package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherRepository extends JpaRepository<Teacher, String> {

    List<Teacher> findBySchoolId(Long schoolId);

    List<Teacher> findByStatusAndSchoolId(TeacherStatus status, Long schoolId);

    Optional<Teacher> findByTeacherIdAndSchoolId(String teacherId, Long schoolId);

    /** Row-locked teacher read — serializes a teacher's concurrent leave applications (overlap check). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Teacher t where t.teacherId = :teacherId and t.schoolId = :schoolId")
    Optional<Teacher> lockByTeacherIdAndSchoolId(@org.springframework.data.repository.query.Param("teacherId") String teacherId,
                                                 @org.springframework.data.repository.query.Param("schoolId") Long schoolId);

    /** A class can legitimately have more than one class-teacher once it has sections (one per
     *  section) — callers that need "the" class teacher for a specific student/section must use
     *  {@link #findByClassTeacherAndClassTeacherSectionIdAndSchoolId} or filter this list
     *  themselves; do not assume a single result. Kept for classes with no sections, where
     *  exactly one (or zero) class-teacher is still the correct expectation. */
    List<Teacher> findByClassTeacherAndSchoolId(String className, Long schoolId);

    List<Teacher> findByClassTeacherAndClassTeacherSectionIdAndSchoolId(String className, Long sectionId, Long schoolId);

    List<Teacher> findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(String className, Long schoolId);

    /** Phase F4: every teacher currently holding ANY live class-teacher scope — the complete
     *  "currently live" set that activation must reconcile against, so a teacher whose live
     *  assignment isn't backed by the current session's configuration gets cleared rather than
     *  silently left stale. */
    List<Teacher> findBySchoolIdAndClassTeacherIsNotNull(Long schoolId);

    long countBySchoolId(Long schoolId);

    long countBySchoolIdAndStatus(Long schoolId, TeacherStatus status);

    /** Phase F5B.1: is any teacher's LIVE class-teacher scope currently pinned to this section? —
     *  used by {@code SectionService#deleteSection} to fail closed rather than leave
     *  {@code TeacherClassScopeService} reading a dangling {@code classTeacherSectionId}. */
    boolean existsBySchoolIdAndClassTeacherSectionId(Long schoolId, Long sectionId);
}
