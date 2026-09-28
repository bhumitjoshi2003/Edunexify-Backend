package com.indraacademy.ias_management.entity;

/**
 * How a Report Card V2 setup turns its exams into one result.
 * TOTAL: total obtained ÷ total max marks across the exams (a single exam = its own result).
 * WEIGHTED: Σ exam % × weight (or term % × term weight, a term being Σ exam % × weight, or its
 * total marks when its exams carry no weights).
 */
public enum ReportCardResultMode { TOTAL, WEIGHTED }
