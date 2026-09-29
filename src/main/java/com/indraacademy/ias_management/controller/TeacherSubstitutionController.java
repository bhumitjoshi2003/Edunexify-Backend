package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.service.TeacherSubstitutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/substitutions")
public class TeacherSubstitutionController {
    private final TeacherSubstitutionService service;
    public TeacherSubstitutionController(TeacherSubstitutionService service) { this.service = service; }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @GetMapping("/uncovered")
    public List<UncoveredPeriod> uncovered(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.uncovered(date);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @GetMapping("/overview")
    public DayOverview overview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.overview(date);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @GetMapping("/suggest-fill")
    public FillPreview suggestFill(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.fillPreview(date);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @PostMapping("/bulk")
    public BulkResult bulk(@Valid @RequestBody BulkRequest request, HttpServletRequest http) {
        return service.assignMany(request, http);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @GetMapping("/free-teachers")
    public List<FreeTeacher> freeTeachers(@RequestParam Long timetableEntryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.freeTeachers(timetableEntryId, date);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @PostMapping
    public Assignment assign(@Valid @RequestBody UpsertRequest request, HttpServletRequest http) {
        return service.assign(request, http);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @PutMapping("/{id}")
    public Assignment change(@PathVariable Long id, @Valid @RequestBody ChangeRequest request, HttpServletRequest http) {
        return service.change(id, request, http);
    }
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    @DeleteMapping("/{id}")
    public Assignment cancel(@PathVariable Long id, HttpServletRequest http) { return service.cancel(id, http); }

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @GetMapping("/mine")
    public List<Assignment> mine(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.mine(date);
    }

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @GetMapping("/my-coverage")
    public MyCoverage myCoverage(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.myCoverage(date);
    }
}
