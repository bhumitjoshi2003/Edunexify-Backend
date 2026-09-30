package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.opencsv.CSVReader;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles CSV bulk import of students.
 *
 * Expected CSV header row and field mapping (columns are matched by name, not position —
 * see {@link #buildColumnIndex}, so column order doesn't matter and old/new templates both
 * work):
 *
 *  Column name     | Student field  | Required | Notes
 *  ----------------|----------------|----------|------------------------------
 *  Student Name    | name           | yes      |
 *  Email           | email          | yes      |
 *  Phone Number    | phoneNumber    | no       |
 *  Date of Birth   | dob            | yes      | yyyy-MM-dd — used as the initial login password (yyyyMMdd)
 *  Class           | className      | yes      |
 *  Gender          | gender         | no       |
 *  Father Name     | fatherName     | no       |
 *  Mother Name     | motherName     | no       |
 *  Section         | sectionId/Name | no       | Must match existing section name for the class
 *  Takes Bus       | takesBus       | no       | true / false (default: false)
 *  Distance (km)   | distance       | no       | numeric (default: 0.0)
 *  Joining Date    | joiningDate    | yes      | yyyy-MM-dd
 *  (Leaving Date)  | —              | —        | a value is rejected: a new admission has no exit; use the exit workflow
 *
 * Edunexify always generates the Student ID for every newly imported student — a "Student
 * ID" column is no longer part of the downloadable template. An OLDER CSV that still has one
 * (from before this feature existed) is still accepted without erroring — its column is
 * simply never read into the entity, and the result's {@code notice} field says so plainly
 * so the admin isn't left guessing why the values they typed there didn't take effect.
 *
 * Processing rules:
 * - Each row is saved in its own transaction (via StudentService.addStudent), which creates the
 *   student, enrollment and login together — a failure at any step rolls back that row only.
 * - An unknown Section is a row error (never silently dropped); a Leaving Date is rejected; a
 *   second row with the same name and date of birth as an earlier row in the file is rejected.
 *   A failure on one row does not roll back previously saved rows.
 * - Blank rows are silently skipped.
 * - Row numbers in error reports are 1-indexed; row 1 is the header.
 */
@Service
public class StudentBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(StudentBulkImportService.class);

    /** Column headers written to the downloadable template CSV — no ID column; Edunexify
     *  generates it. */
    public static final String[] TEMPLATE_HEADERS = {
            "Student Name", "Email", "Phone Number",
            "Date of Birth", "Class", "Section", "Gender", "Father Name", "Mother Name",
            "Takes Bus", "Distance (km)", "Joining Date"
    };

    private static final String LEGACY_ID_COLUMN = "student id";

    @Autowired private StudentService studentService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;

    /**
     * Parses the uploaded CSV, attempts to save each data row, and returns
     * an aggregated result containing success/failure counts and per-row errors.
     */
    public BulkImportResultDTO bulkImport(MultipartFile file, HttpServletRequest request) {
        List<BulkImportResultDTO.RowError> errors = new ArrayList<>();
        List<BulkImportResultDTO.RowSuccess> created = new ArrayList<>();
        int successful = 0;
        int totalRows  = 0;

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty.");
            }
            Map<String, Integer> columnIndex = buildColumnIndex(header);
            boolean hasLegacyIdColumn = columnIndex.containsKey(LEGACY_ID_COLUMN);

            String[] row;
            Map<String, Integer> seenNameDob = new HashMap<>();
            int rowNum = 1; // header is row 1; data starts at row 2
            while ((row = reader.readNext()) != null) {
                rowNum++;
                if (isBlankRow(row)) continue;
                totalRows++;

                String name = getCol(row, columnIndex, "student name");
                try {
                    Student student = parseRow(row, columnIndex, rowNum, errors);
                    if (student == null) continue; // validation error already recorded

                    String duplicateKey = student.getName().trim().toLowerCase(Locale.ROOT) + "|" + student.getDob();
                    Integer firstRow = seenNameDob.putIfAbsent(duplicateKey, rowNum);
                    if (firstRow != null) {
                        errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                                "Duplicate of row " + firstRow + " (same name and date of birth) — not imported"));
                        continue;
                    }

                    // Each addStudent call runs in its own @Transactional context and creates the
                    // student, enrollment and login together — a failure rolls back this row only.
                    // studentId is intentionally left unset; addStudent() generates it.
                    Student saved = studentService.addStudent(student, request);
                    successful++;
                    created.add(new BulkImportResultDTO.RowSuccess(rowNum, saved.getName(), saved.getStudentId()));
                    log.info("Bulk import: row {} saved (studentId={})", rowNum, saved.getStudentId());

                } catch (IllegalArgumentException | IllegalStateException e) {
                    // Business-rule rejections from addStudent (duplicate ID, session rules, a login
                    // that can't be created). The row's transaction has already rolled back.
                    log.warn("Bulk import: row {} rejected (name={}): {}", rowNum, name, e.getMessage());
                    errors.add(new BulkImportResultDTO.RowError(rowNum, name, e.getMessage()));
                } catch (Exception e) {
                    log.error("Bulk import: unexpected error on row {} (name={})", rowNum, name, e);
                    errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                            "Unexpected error: " + e.getMessage()));
                }
            }

            BulkImportResultDTO result = new BulkImportResultDTO(totalRows, successful, errors.size(), errors,
                    created, hasLegacyIdColumn ? LEGACY_ID_NOTICE : null);
            auditBulkImport(file.getOriginalFilename(), result, request);
            return result;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read CSV file during bulk import", e);
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage(), e);
        }
    }

    private static final String LEGACY_ID_NOTICE =
            "This file included a 'Student ID' column. Edunexify now generates the account ID "
                    + "automatically for every new student, so the values in that column were not "
                    + "used. See the generated ID for each row below.";

    /**
     * Writes a single BULK_IMPORT_STUDENT audit entry summarising the entire import session.
     * Each successfully saved row also has its own CREATE_STUDENT entry written by addStudent().
     */
    private void auditBulkImport(String filename, BulkImportResultDTO result, HttpServletRequest request) {
        try {
            String newValue = objectMapper.writeValueAsString(new BulkImportAuditPayload(filename, result));
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "BULK_IMPORT_STUDENT",
                    "Student",
                    "BULK:" + (filename != null ? filename : "unknown"),
                    null,
                    newValue,
                    request.getRemoteAddr()
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize bulk import audit payload", e);
        }
    }

    /** Internal payload structure for the bulk import audit log entry. */
    private record BulkImportAuditPayload(
            String filename,
            int totalRows,
            int successful,
            int failed,
            List<BulkImportResultDTO.RowError> errors,
            List<BulkImportResultDTO.RowSuccess> created
    ) {
        BulkImportAuditPayload(String filename, BulkImportResultDTO result) {
            this(filename, result.getTotalRows(), result.getSuccessful(),
                    result.getFailed(), result.getErrors(), result.getCreated());
        }
    }

    /**
     * Parses a single data row into a Student object. studentId is deliberately left unset —
     * StudentService.addStudent() generates it. Any legacy "Student ID" column in the CSV is
     * simply never consulted here.
     * Returns {@code null} and appends to {@code errors} if any validation fails.
     */
    private Student parseRow(String[] row, Map<String, Integer> columnIndex, int rowNum,
                             List<BulkImportResultDTO.RowError> errors) {
        String name        = getCol(row, columnIndex, "student name");
        String email       = getCol(row, columnIndex, "email");
        String phoneNumber = getCol(row, columnIndex, "phone number");
        String dobStr      = getCol(row, columnIndex, "date of birth");
        String className   = getCol(row, columnIndex, "class");
        String sectionName = getCol(row, columnIndex, "section");
        String gender       = getCol(row, columnIndex, "gender");
        String fatherName  = getCol(row, columnIndex, "father name");
        String motherName  = getCol(row, columnIndex, "mother name");
        String takesBusStr = getCol(row, columnIndex, "takes bus");
        String distanceStr = getCol(row, columnIndex, "distance (km)");
        String joiningStr  = getCol(row, columnIndex, "joining date");
        String leavingStr  = getCol(row, columnIndex, "leaving date");

        // Required field checks
        if (name.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, "", "Student Name is required"));
            return null;
        }
        if (email.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name, "Email is required"));
            return null;
        }
        if (className.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name, "Class is required"));
            return null;
        }
        if (joiningStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name, "Joining Date is required"));
            return null;
        }
        if (dobStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Date of birth is required because it is used as the initial password."));
            return null;
        }

        // Date parsing
        LocalDate dob;
        try {
            dob = LocalDate.parse(dobStr);
        } catch (DateTimeParseException e) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Invalid date format for 'Date of Birth', expected yyyy-MM-dd"));
            return null;
        }

        LocalDate joiningDate;
        try {
            joiningDate = LocalDate.parse(joiningStr);
        } catch (DateTimeParseException e) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Invalid date format for 'Joining Date', expected yyyy-MM-dd"));
            return null;
        }

        if (!leavingStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Leaving Date must be empty for a new admission — use the student exit workflow instead"));
            return null;
        }

        // Boolean parsing
        Boolean takesBus = false;
        if (!takesBusStr.isEmpty()) {
            if ("true".equalsIgnoreCase(takesBusStr)) {
                takesBus = true;
            } else if ("false".equalsIgnoreCase(takesBusStr)) {
                takesBus = false;
            } else {
                errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                        "Takes Bus must be 'true' or 'false'"));
                return null;
            }
        }

        // Numeric parsing
        Double distance = 0.0;
        if (!distanceStr.isEmpty()) {
            try {
                distance = Double.parseDouble(distanceStr);
            } catch (NumberFormatException e) {
                errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                        "Distance must be a valid number"));
                return null;
            }
        }

        Student student = new Student();
        student.setName(name);
        student.setEmail(email);
        student.setPhoneNumber(phoneNumber.isEmpty()  ? null : phoneNumber);
        student.setDob(dob);
        student.setClassName(className);
        // Resolve className → classId, and section name → section. An unknown class is rejected
        // by addStudent; an unknown section is a row error here — never silently dropped.
        Long schoolId = securityUtil.getSchoolId();
        var schoolClass = schoolClassRepository.findBySchoolIdAndName(schoolId, className);
        if (schoolClass.isPresent()) {
            student.setClassId(schoolClass.get().getId());
            if (!sectionName.isEmpty()) {
                var section = sectionRepository.findBySchoolIdAndClassIdAndName(schoolId, schoolClass.get().getId(), sectionName);
                if (section.isEmpty()) {
                    errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                            "Section '" + sectionName + "' does not exist for class '" + className + "'"));
                    return null;
                }
                student.setSectionId(section.get().getId());
                student.setSectionName(section.get().getName());
            }
        }
        student.setGender(gender.isEmpty()             ? null : gender);
        student.setFatherName(fatherName.isEmpty()     ? null : fatherName);
        student.setMotherName(motherName.isEmpty()     ? null : motherName);
        student.setTakesBus(takesBus);
        student.setDistance(distance);
        student.setJoiningDate(joiningDate);
        return student;
    }

    /** Maps each header cell (trimmed, lower-cased) to its column index, so columns are
     *  matched by name rather than fixed position — this is what lets an older CSV that
     *  still has a leading "Student ID" column (or any other reordering) parse correctly
     *  without that column needing to be at any particular position. */
    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null) {
                index.put(header[i].trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return index;
    }

    /** Returns the trimmed cell value for the named column, or an empty string if that
     *  column isn't present in this file's header at all, or the cell itself is blank. */
    private String getCol(String[] row, Map<String, Integer> columnIndex, String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    /** Returns true when every cell in the row is blank (skips fully empty lines). */
    private boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }
}
