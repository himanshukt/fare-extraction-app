package com.example.fare.controller;

import com.example.fare.dto.FareTemplateResponseDto;
import com.example.fare.service.FareExtractionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class FareExtractionController {

    private static final Logger log = LoggerFactory.getLogger(FareExtractionController.class);

    private final FareExtractionService fareExtractionService;
    private final ObjectMapper objectMapper;

    public FareExtractionController(FareExtractionService fareExtractionService, ObjectMapper objectMapper) {
        this.fareExtractionService = fareExtractionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Full end-to-end flow:
     * 1. Accepts Excel template (AVC_IDs) + Fare chart (PDF/Image)
     * 2. Reads Excel for AVC_ID → VEHICLE_DESCS mapping
     * 3. Sends PDF to AI with AVC_ID context
     * 4. AI extracts fares and maps to correct AVC_IDs
     * 5. Validates AI response
     * 6. Merges fares into template rows
     * 7. Returns complete rows to frontend
     *
     * @param template  Excel file (.xlsx/.xls) with AVC_ID, VEHICLE_DESCS, etc.
     * @param fareChart PDF or Image containing the fare chart
     */
    @PostMapping(value = "/extract-and-map",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> extractAndMap(
            @RequestParam("template") MultipartFile template,
            @RequestParam("fareChart") MultipartFile fareChart) {

        log.info("=== Extract & Map Request ===");
        log.info("Template: name={}, size={}B, type={}",
                template.getOriginalFilename(), template.getSize(), template.getContentType());
        log.info("FareChart: name={}, size={}B, type={}",
                fareChart.getOriginalFilename(), fareChart.getSize(), fareChart.getContentType());

        try {
            FareTemplateResponseDto result = fareExtractionService.extractAndMap(template, fareChart);

            log.info("=== Extract & Map Complete ===");
            log.info("Rows: {}, Warnings: {}",
                    result.getRows() != null ? result.getRows().size() : 0,
                    result.getWarnings() != null ? result.getWarnings().size() : 0);

            if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
                log.warn("Warnings: {}", result.getWarnings());
            }

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", e.getMessage(),
                            "type", "VALIDATION_ERROR"
                    ));
        } catch (IOException e) {
            log.error("IO error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "Failed to process uploaded files: " + e.getMessage(),
                            "type", "IO_ERROR"
                    ));
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "An unexpected error occurred: " + e.getMessage(),
                            "type", "INTERNAL_ERROR"
                    ));
        }
    }
}
