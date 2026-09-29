package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentRolloverRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentRolloverRunRepository extends JpaRepository<StudentRolloverRun, Long> {
    Optional<StudentRolloverRun> findByIdAndSchoolId(Long id, Long schoolId);
    List<StudentRolloverRun> findTop20BySchoolIdAndTargetSessionIdOrderByStartedAtDescIdDesc(Long schoolId, Long targetSessionId);
}
