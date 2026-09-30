package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * The student login account, kept in step with the student's lifecycle: created together with
 * the admission (initial password = date of birth, YYYYMMDD, change forced at first login),
 * deactivated with sessions revoked when a student leaves by transfer, withdrawal or a
 * cancelled admission, and reactivated on readmission. A graduated student keeps their login so
 * past results and report cards stay reachable. The account row is never deleted here.
 */
@Service
public class StudentLoginService {

    private static final Logger log = LoggerFactory.getLogger(StudentLoginService.class);

    public record LoginStatus(boolean exists, boolean active) {}

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final WelcomeEmailService welcomeEmail;
    private final UserSessionService sessions;

    public StudentLoginService(UserRepository users, PasswordEncoder passwordEncoder,
                               WelcomeEmailService welcomeEmail, UserSessionService sessions) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.welcomeEmail = welcomeEmail;
        this.sessions = sessions;
    }

    public LoginStatus status(Long schoolId, String studentId) {
        return ownLogin(schoolId, studentId)
                .map(user -> new LoginStatus(true, user.isActive()))
                .orElse(new LoginStatus(false, false));
    }

    /**
     * Creates the login inside the caller's transaction, so a failure rolls back with it. The
     * welcome email is sent only after that transaction commits.
     *
     * @throws IllegalStateException when any account already uses this ID
     */
    public User create(Student student) {
        if (student.getDob() == null) {
            throw new IllegalArgumentException("Date of birth is required because it is used as the initial password.");
        }
        if (users.findByUserId(student.getStudentId()).isPresent()) {
            throw new IllegalStateException("A login already exists for student " + student.getStudentId() + ".");
        }
        User user = new User();
        user.setUserId(student.getStudentId());
        user.setRole(Role.STUDENT);
        user.setSchoolId(student.getSchoolId());
        user.setEmail(student.getEmail());
        user.setPassword(passwordEncoder.encode(student.getDob().format(DateTimeFormatter.BASIC_ISO_DATE)));
        user.setMustChangePassword(true);
        user.setActive(true);
        User saved = users.saveAndFlush(user);
        afterCommit(() -> welcomeEmail.sendWelcomeEmail(student.getStudentId(), student.getName(), Role.STUDENT,
                student.getEmail(), student.getSchoolId()));
        return saved;
    }

    /** Deactivates the login and revokes every session. No-op when there is no login. */
    public void deactivate(Long schoolId, String studentId) {
        ownLogin(schoolId, studentId).ifPresent(user -> {
            if (user.isActive()) {
                user.setActive(false);
                users.save(user);
            }
            sessions.revokeAllForUser(user.getUserId());
            log.info("Student login {} deactivated and sessions revoked.", studentId);
        });
    }

    /** Reactivates an existing login. No-op when there is no login. */
    public void reactivate(Long schoolId, String studentId) {
        ownLogin(schoolId, studentId).filter(user -> !user.isActive()).ifPresent(user -> {
            user.setActive(true);
            users.save(user);
            log.info("Student login {} reactivated.", studentId);
        });
    }

    private Optional<User> ownLogin(Long schoolId, String studentId) {
        return users.findByUserId(studentId)
                .filter(user -> Role.STUDENT.equals(user.getRole()) && Objects.equals(schoolId, user.getSchoolId()));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }
}
