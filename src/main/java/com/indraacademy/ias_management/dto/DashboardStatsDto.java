package com.indraacademy.ias_management.dto;

public class DashboardStatsDto {
    private long totalStudents;
    private long totalTeachers;
    private long feesCollectedThisMonth;
    private long overdueStudents;
    private double todayAttendanceRate;
    private long pendingLeaves;
    /** Breakdown of pendingLeaves (student + teacher leave). */
    private long pendingStudentLeaves;
    private long pendingTeacherLeaves;

    public long getTotalStudents() { return totalStudents; }
    public void setTotalStudents(long totalStudents) { this.totalStudents = totalStudents; }

    public long getTotalTeachers() { return totalTeachers; }
    public void setTotalTeachers(long totalTeachers) { this.totalTeachers = totalTeachers; }

    public long getFeesCollectedThisMonth() { return feesCollectedThisMonth; }
    public void setFeesCollectedThisMonth(long feesCollectedThisMonth) { this.feesCollectedThisMonth = feesCollectedThisMonth; }

    public long getOverdueStudents() { return overdueStudents; }
    public void setOverdueStudents(long overdueStudents) { this.overdueStudents = overdueStudents; }

    public double getTodayAttendanceRate() { return todayAttendanceRate; }
    public void setTodayAttendanceRate(double todayAttendanceRate) { this.todayAttendanceRate = todayAttendanceRate; }

    public long getPendingLeaves() { return pendingLeaves; }
    public void setPendingLeaves(long pendingLeaves) { this.pendingLeaves = pendingLeaves; }
    public long getPendingStudentLeaves() { return pendingStudentLeaves; }
    public void setPendingStudentLeaves(long v) { this.pendingStudentLeaves = v; }
    public long getPendingTeacherLeaves() { return pendingTeacherLeaves; }
    public void setPendingTeacherLeaves(long v) { this.pendingTeacherLeaves = v; }
}
