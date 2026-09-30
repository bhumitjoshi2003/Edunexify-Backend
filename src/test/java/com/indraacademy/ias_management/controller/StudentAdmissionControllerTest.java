package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StudentAdmissionDtos;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.*;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Student Admission Phase 1 at the HTTP boundary: request bodies bind only admin-editable fields
 * (forged lifecycle fields and signed photo URLs are dropped), roles on the new endpoints, and
 * teachers opening a single student only within their own class/section.
 */
@SpringJUnitConfig(StudentAdmissionControllerTest.Config.class)
class StudentAdmissionControllerTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean StudentController studentController() { return new StudentController(); }
    }

    @Autowired StudentController controller;
    @MockBean StudentService studentService;
    @MockBean StudentBulkImportService bulkImportService;
    @MockBean StudentPromotionService promotionService;
    @MockBean PasswordEncoder passwordEncoder;
    @MockBean StudentRepository studentRepository;
    @MockBean UserRepository userRepository;
    @MockBean ObjectMapper objectMapper;
    @MockBean AuthService authService;
    @MockBean ParentPortalService parentPortalService;
    @MockBean TeacherClassScopeService scopeService;
    @MockBean SecurityUtil securityUtil;
    @MockBean ObjectStorageService objectStorageService;

    final HttpServletRequest http = mock(HttpServletRequest.class);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(authService.getRole()).thenReturn(role);
        when(authService.getUserId()).thenReturn("u1");
        when(securityUtil.getSchoolId()).thenReturn(1L);
    }

    /** MockMvc over the unproxied controller: exercises real JSON binding (unknown fields ignored). */
    private MockMvc mvc() {
        StudentController plain = new StudentController();
        ReflectionTestUtils.setField(plain, "studentService", studentService);
        ReflectionTestUtils.setField(plain, "objectStorageService", objectStorageService);
        return MockMvcBuilders.standaloneSetup(plain).build();
    }

    @Test
    void admissionBindsOnlyAdminEditableFields() throws Exception {
        Student saved = new Student();
        saved.setStudentId("stu_1");
        when(studentService.addStudent(any(StudentAdmissionDtos.AdmissionRequest.class), any())).thenReturn(saved);

        mvc().perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("""
                {"studentId":"CHOSEN","schoolId":99,"status":"GRADUATED","leavingDate":"2026-01-01",
                 "photoUrl":"https://evil/x.jpg","reasonForLeaving":"x",
                 "name":"Asha","email":"a@test.com","dob":"2012-02-03","className":"9","sectionId":5,
                 "gender":"F","takesBus":true,"distance":3.5,"joiningDate":"2026-10-01"}"""))
                .andExpect(status().isCreated());

        ArgumentCaptor<StudentAdmissionDtos.AdmissionRequest> captor = ArgumentCaptor.forClass(StudentAdmissionDtos.AdmissionRequest.class);
        verify(studentService).addStudent(captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(new StudentAdmissionDtos.AdmissionRequest("Asha", "a@test.com", null,
                LocalDate.of(2012, 2, 3), "9", 5L, "F", null, null, true, 3.5, LocalDate.of(2026, 10, 1)));
    }

    @Test
    void anEditPayloadCopiedFromGetDropsTheSignedPhotoUrlAndLifecycleFields() throws Exception {
        when(studentService.updateStudent(eq("stu_1"), any(StudentAdmissionDtos.UpdateRequest.class), any(), any()))
                .thenReturn(new Student());

        mvc().perform(put("/api/students/stu_1").contentType(MediaType.APPLICATION_JSON).content("""
                {"studentDetails":{"studentId":"stu_1","schoolId":1,"status":"TRANSFERRED","leavingDate":"2026-01-01",
                 "photoUrl":"https://storage.example/k.jpg?X-Amz-Signature=abc","exitRemarks":"x","createdAt":"2020-01-01T00:00:00",
                 "name":"Asha","email":"a@test.com","dob":"2012-02-03","className":"9","sectionId":5,"joiningDate":"2026-09-01"},
                 "effectiveFromMonth":4}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<StudentAdmissionDtos.UpdateRequest> captor = ArgumentCaptor.forClass(StudentAdmissionDtos.UpdateRequest.class);
        verify(studentService).updateStudent(eq("stu_1"), captor.capture(), eq(4), any());
        assertThat(captor.getValue()).isEqualTo(new StudentAdmissionDtos.UpdateRequest("Asha", "a@test.com", null,
                LocalDate.of(2012, 2, 3), "9", 5L, null, null, null, null, null, LocalDate.of(2026, 9, 1)));
    }

    @Test
    void theNewLifecycleEndpointsAreAdminOnly() {
        for (String role : List.of("ADMIN", "TEACHER", "SUB_ADMIN", "STUDENT", "PARENT", "SUPER_ADMIN")) {
            as(role);
            List<Runnable> calls = List.of(
                    () -> controller.cancelAdmission("S1", null, http),
                    () -> controller.loginStatus("S1"),
                    () -> controller.createMissingLogin("S1", http),
                    () -> controller.enrollmentHistory("S1"),
                    () -> controller.restorableParentLinks("S1"),
                    () -> controller.restoreParentLinks("S1", new StudentAdmissionDtos.RestoreParentLinksRequest(List.of()), http),
                    () -> controller.readmitStudent("S1", null, http));
            for (Runnable call : calls) {
                if (role.equals("ADMIN")) assertThatCode(call::run).as(role).doesNotThrowAnyException();
                else assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    @Test
    void aTeacherCanOpenOnlyAStudentOfTheirOwnClassSection() {
        as("TEACHER");
        Student other = new Student();
        other.setStudentId("S-OTHER");
        other.setClassName("7");
        other.setSectionId(70L);
        when(studentService.getStudent("S-OTHER")).thenReturn(Optional.of(other));
        when(scopeService.authorizeAndScopeToStudent(Role.TEACHER, "u1", 1L, "7", 70L))
                .thenReturn(ScopedAccess.deny("You can only access students of your own class."));
        assertThatThrownBy(() -> controller.getStudent("S-OTHER")).isInstanceOf(AccessDeniedException.class);

        Student mine = new Student();
        mine.setStudentId("S-MINE");
        mine.setClassName("9");
        mine.setSectionId(90L);
        when(studentService.getStudent("S-MINE")).thenReturn(Optional.of(mine));
        when(scopeService.authorizeAndScopeToStudent(Role.TEACHER, "u1", 1L, "9", 90L)).thenReturn(ScopedAccess.allow(90L));
        assertThat(controller.getStudent("S-MINE").getBody().getStudentId()).isEqualTo("S-MINE");
    }

    @Test
    void adminAccessIsUnchanged_studentsAlwaysGetTheirOwnRecord() {
        as("ADMIN");
        Student any = new Student();
        any.setStudentId("S-ANY");
        when(studentService.getStudent("S-ANY")).thenReturn(Optional.of(any));
        assertThat(controller.getStudent("S-ANY").getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(scopeService);

        as("STUDENT");
        Student self = new Student();
        self.setStudentId("u1");
        when(studentService.getStudent("u1")).thenReturn(Optional.of(self));
        assertThat(controller.getStudent("SOMEONE-ELSE").getBody().getStudentId()).isEqualTo("u1");
    }
}
