package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolCoScholasticActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolCoScholasticActivityRepository extends JpaRepository<SchoolCoScholasticActivity, Long> {
    List<SchoolCoScholasticActivity> findBySchoolIdOrderByDisplayOrderAscIdAsc(Long schoolId);
    List<SchoolCoScholasticActivity> findBySchoolIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long schoolId);
    Optional<SchoolCoScholasticActivity> findByIdAndSchoolId(Long id, Long schoolId);
}
