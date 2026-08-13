package com.example.fare.service;

import com.example.fare.dto.AiFareExtractionResult;
import com.example.fare.dto.AiFareRowDto;
import com.example.fare.dto.FareTemplateResponseDto;
import com.example.fare.dto.FareTemplateRowDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.poi.ss.usermodel.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FareExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FareExtractionService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // AI Prompt — uses {avcContext} and {format} as template parameters
    // to avoid StringTemplate parsing errors with raw JSON/curly braces.
    // =========================================================================
    private static final String EXTRACTION_PROMPT = """
    You are a fare extraction engine for Indian toll plazas (IHMCL).

Analyze the provided PDF/image/document and extract the fare information accurately.

AUTHORITATIVE AVC_ID MAPPING:
The following AVC_IDs and vehicle descriptions come from our database and are the ONLY valid AVC_IDs.

{avcContext}

IMPORTANT:
- Never create, modify, or invent an AVC_ID.
- AVC_ID must always come from the provided authoritative mapping.
- Match the fare from the document to the appropriate vehicle description from the authoritative mapping.

FARE FIELDS TO EXTRACT:

For each applicable AVC_ID, extract:

- SINGLE_JOURNEY_FARE: Single / one-way journey fare
- RETURN_JOURNEY_FARE: Return / round-trip fare
- COM_VEHICLE_FARE: Commercial vehicle fare
- MONTHLY_PASS_FARE: Monthly pass fare
- LOCAL20_PASS_FARE: Local 20km pass fare

VEHICLE DESCRIPTION / AXLE RANGE RULE:

A vehicle description in the document may represent multiple axle categories.

For example:

"4 to 6 Axle"
"4-6 Axle"
"4 / 5 / 6 Axle"
"4, 5 & 6 Axle"
"4 to 6 XLE"
"4-6 XLE"

All of these mean that the SAME fare applies to:

4 Axle
5 Axle
6 Axle

Therefore, if the document contains:

"4 to 6 Axle - Single Journey Fare: 500"

and the authoritative AVC mapping contains:

AVC_ID 4 = 4 Axle
AVC_ID 5 = 5 Axle
AVC_ID 6 = 6 Axle

then the output MUST contain:

AVC_ID 4 → SINGLE_JOURNEY_FARE = 500
AVC_ID 5 → SINGLE_JOURNEY_FARE = 500
AVC_ID 6 → SINGLE_JOURNEY_FARE = 500

Do NOT assign the fare only to AVC_ID 4.

The same rule applies to all fare fields:

SINGLE_JOURNEY_FARE
RETURN_JOURNEY_FARE
COM_VEHICLE_FARE
MONTHLY_PASS_FARE
LOCAL20_PASS_FARE

VEHICLE MATCHING:

Match vehicle descriptions semantically rather than using exact string matching.

Examples:

"4 to 6 Axle" = 4 Axle + 5 Axle + 6 Axle

"4-6 Axle" = 4 Axle + 5 Axle + 6 Axle

"4, 5 and 6 Axle" = 4 Axle + 5 Axle + 6 Axle

"4 to 6 XLE" = 4 XLE + 5 XLE + 6 XLE

When a fare is specified for a vehicle range, apply the same fare to EVERY matching AVC_ID in the authoritative mapping.

EXTRACTION RULES:

1. Extract ONLY fare values explicitly present in the document.
2. Do NOT hallucinate or invent fare values.
3. If a fare cannot be determined reliably, return null.
4. Parse Indian number formats correctly.
   Example: "1,25,000" must become 125000.
5. Return fare amounts as numbers without currency symbols.
6. Correct obvious OCR errors when the intended value is unambiguous.
7. Do not calculate or derive fares unless explicitly supported by the document.
8. If a document fare applies to a vehicle range, apply that fare to every matching AVC_ID.
9. Never create an AVC_ID that is not present in the authoritative mapping.
10. Never change an AVC_ID from the authoritative mapping.
11. Do not omit an applicable AVC_ID when the document explicitly defines a fare for its vehicle category.
12. If a fare is unavailable, return null.
13. If a general "Local 20km pass" or "Local pass" fare is mentioned (e.g., as a footnote or single value for the plaza), apply that same LOCAL20_PASS_FARE value to ALL AVC_IDs in your response.

CONFLICT RULE:

If the document contains both a range fare and a specific fare for an individual vehicle category, the specific fare takes precedence.

Example:

"4 to 6 Axle = 500"
"5 Axle = 550"

Result:

4 Axle → 500
5 Axle → 550
6 Axle → 500

OUTPUT REQUIREMENT:
{format}

STRICT JSON RULES:

- Return ONLY the JSON array.
- Do NOT return Markdown.
- Do NOT use ```json.
- Do NOT provide explanations.
- Do NOT provide reasoning.
- Do NOT add comments.
- Do NOT add fields that are not present in the schema.
- Use double quotes for JSON property names.
- Numeric fare values must be JSON numbers, not strings.
- Use null when a fare is unavailable.
- The JSON must be directly deserializable using Jackson ObjectMapper.
""";


    // Allowed Excel MIME types for validation
    private static final Set<String> EXCEL_MIME_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
            "application/vnd.ms-excel",                                          // .xls
            "application/octet-stream"                                           // fallback
    );

    private static final Set<String> PDF_IMAGE_MIME_TYPES = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/jpg"
    );

    public FareExtractionService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // MAIN ORCHESTRATOR: Full end-to-end flow
    // =========================================================================
    public FareTemplateResponseDto extractAndMap(MultipartFile excelTemplate, MultipartFile fareChart) throws IOException {
        List<String> warnings = new ArrayList<>();

        // --- Phase 1: Validate inputs ---
        validateExcelFile(excelTemplate);
        validateFareChartFile(fareChart);

        // --- Phase 2: Read Excel template (source of truth for AVC_IDs) ---
        List<FareTemplateRowDto> templateRows = readExcelTemplate(excelTemplate.getInputStream());
        if (templateRows.isEmpty()) {
            throw new IllegalArgumentException("Excel template contains no data rows after the header.");
        }
        log.info("Phase 2: Read {} rows from Excel template", templateRows.size());

        // Extract unique AVC_ID → VEHICLE_DESCS mapping for the AI context
        Map<String, String> avcMappings = extractUniqueAvcMappings(templateRows);
        log.info("Phase 2: Found {} unique AVC_IDs: {}", avcMappings.size(), avcMappings.keySet());

        // --- Phase 3: AI extraction from PDF with AVC_ID context ---
        AiFareExtractionResult aiResult = extractFaresFromDocument(fareChart, avcMappings);
        log.info("Phase 3: AI returned {} fare rows", aiResult.getFares() != null ? aiResult.getFares().size() : 0);
        if (aiResult.getWarnings() != null) {
            warnings.addAll(aiResult.getWarnings());
        }

        // --- Phase 4: Validate AI response ---
        List<AiFareRowDto> validatedFares = validateAiResponse(aiResult, avcMappings.keySet(), warnings);
        log.info("Phase 4: {} validated fare rows", validatedFares.size());

        // --- Phase 5: Map AI fares into template rows by AVC_ID ---
        Map<String, AiFareRowDto> fareLookup = new HashMap<>();
        for (AiFareRowDto fare : validatedFares) {
            fareLookup.put(fare.getAvcId().trim(), fare);
        }

        int matched = 0;
        for (FareTemplateRowDto row : templateRows) {
            String avcId = row.getAvcId();
            if (avcId == null || avcId.isBlank()) continue;

            AiFareRowDto fare = fareLookup.get(avcId.trim());
            if (fare != null) {
                row.setSingleJourneyFare(fare.getSingleJourneyFare());
                row.setReturnJourneyFare(fare.getReturnJourneyFare());
                row.setComVehicleFare(fare.getComVehicleFare());
                row.setMonthlyPassFare(fare.getMonthlyPassFare());
                row.setLocal20PassFare(fare.getLocal20PassFare());
                matched++;
                log.info("Phase 5: AVC_ID={} -> Single={}, Return={}, Com={}, Monthly={}, Local20={}",
                        avcId, fare.getSingleJourneyFare(), fare.getReturnJourneyFare(),
                        fare.getComVehicleFare(), fare.getMonthlyPassFare(), fare.getLocal20PassFare());
            } else {
                warnings.add("No fare extracted for AVC_ID=" + avcId + " (" + row.getVehicleDescs() + ")");
                log.warn("Phase 5: No fare match for AVC_ID={} ({})", avcId, row.getVehicleDescs());
            }
        }
        log.info("Phase 5: Mapped {}/{} template rows", matched, templateRows.size());

        FareTemplateResponseDto result = new FareTemplateResponseDto();
        result.setRows(templateRows);
        result.setWarnings(warnings);
        return result;
    }

    // =========================================================================
    // FILE VALIDATION
    // =========================================================================
    private void validateExcelFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Excel template file is required and must not be empty.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls"))) {
            throw new IllegalArgumentException(
                    "Excel template must be .xlsx or .xls format. Received: " + filename +
                    ". Do NOT upload a PDF as the Excel template.");
        }
    }

    private void validateFareChartFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Fare chart file is required and must not be empty.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !PDF_IMAGE_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Fare chart must be a PDF or image (PNG/JPG). Received content type: " + contentType);
        }
    }

    // =========================================================================
    // EXCEL TEMPLATE READING (Apache POI)
    // Uses WorkbookFactory.create() to handle BOTH .xls and .xlsx
    // =========================================================================
    private List<FareTemplateRowDto> readExcelTemplate(InputStream inputStream) throws IOException {
        List<FareTemplateRowDto> rows = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);

            // Scan for header row containing "AVC_ID"
            int headerRowIdx = -1;
            Map<String, Integer> colIndex = new LinkedHashMap<>();

            for (Row row : sheet) {
                for (Cell cell : row) {
                    String val = getCellStringValue(cell).trim();
                    if ("AVC_ID".equalsIgnoreCase(val)) {
                        headerRowIdx = row.getRowNum();
                        for (Cell hCell : row) {
                            String header = getCellStringValue(hCell).trim();
                            if (!header.isEmpty()) {
                                colIndex.put(header.toUpperCase(), hCell.getColumnIndex());
                            }
                        }
                        break;
                    }
                }
                if (headerRowIdx != -1) break;
            }

            if (headerRowIdx == -1) {
                throw new IllegalArgumentException(
                        "Excel template must contain an 'AVC_ID' column in the header row. " +
                        "Please verify the uploaded file is the correct AVC template.");
            }

            log.info("Excel header found at row {}. Columns: {}", headerRowIdx, colIndex.keySet());

            // Read data rows below the header
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Skip completely empty rows
                String avcId = getCellVal(row, colIndex, "AVC_ID");
                String vehicleDescs = getCellVal(row, colIndex, "VEHICLE_DESCS");
                if (avcId.isEmpty() && vehicleDescs.isEmpty()) continue;

                FareTemplateRowDto dto = new FareTemplateRowDto();
                dto.setEntryPlazaId(getCellVal(row, colIndex, "ENTRY_PLAZA_ID"));
                dto.setExitPlazaId(getCellVal(row, colIndex, "EXIT_PLAZA_ID"));
                dto.setAvcId(avcId);
                dto.setMvcIds(getCellVal(row, colIndex, "MVC_IDS"));
                dto.setVehicleDescs(vehicleDescs);
                // Do NOT read old fare values — they will be replaced by AI extraction
                rows.add(dto);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The uploaded file is not a valid Excel file (.xlsx/.xls). " +
                    "Error: " + e.getMessage() + ". " +
                    "Make sure you are uploading the Excel template, NOT the PDF.");
        }

        return rows;
    }

    private String getCellVal(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return "";
        Cell cell = row.getCell(idx);
        return getCellStringValue(cell);
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getCachedFormulaResultType() == CellType.STRING
                            ? cell.getStringCellValue().trim()
                            : String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    yield "";
                }
            }
            default -> "";
        };
    }

    // =========================================================================
    // Extract unique AVC_ID → VEHICLE_DESCS mapping from template rows
    // =========================================================================
    private Map<String, String> extractUniqueAvcMappings(List<FareTemplateRowDto> templateRows) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (FareTemplateRowDto row : templateRows) {
            String avcId = row.getAvcId();
            if (avcId != null && !avcId.isBlank() && !mappings.containsKey(avcId.trim())) {
                mappings.put(avcId.trim(), row.getVehicleDescs() != null ? row.getVehicleDescs() : "");
            }
        }
        return mappings;
    }

    // =========================================================================
    // AI EXTRACTION from PDF/Image with AVC_ID context
    // =========================================================================
    private AiFareExtractionResult extractFaresFromDocument(MultipartFile fareChart, Map<String, String> avcMappings)
            throws IOException {

        String contentType = fareChart.getContentType();
        List<Media> mediaList = new ArrayList<>();

        if (contentType != null && contentType.startsWith("image/")) {
            mediaList.add(new Media(MimeTypeUtils.parseMimeType(contentType),
                    new ByteArrayResource(fareChart.getBytes())));
        } else if (contentType != null && contentType.equals("application/pdf")) {
            // Convert PDF pages to images for the Vision model
            try (PDDocument document = PDDocument.load(fareChart.getInputStream())) {
                PDFRenderer pdfRenderer = new PDFRenderer(document);
                int pagesToProcess = Math.min(document.getNumberOfPages(), 5);
                for (int page = 0; page < pagesToProcess; page++) {
                    BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 300, ImageType.RGB);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(bim, "png", baos);
                    mediaList.add(new Media(MimeTypeUtils.parseMimeType("image/png"),
                            new ByteArrayResource(baos.toByteArray())));
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported fare chart type: " + contentType);
        }

        // Build the AVC_ID context as a plain text list (no JSON curly braces)
        StringBuilder avcContext = new StringBuilder();
        for (Map.Entry<String, String> entry : avcMappings.entrySet()) {
            avcContext.append("AVC_ID=").append(entry.getKey())
                      .append(": ").append(entry.getValue()).append("\n");
        }

        BeanOutputConverter<AiFareExtractionResult> converter =
                new BeanOutputConverter<>(AiFareExtractionResult.class);
        String format = converter.getFormat();

        log.info("Phase 3: Sending to AI with AVC context:\n{}", avcContext);

        String response = chatClient.prompt()
                .system(s -> s.text(EXTRACTION_PROMPT)
                              .param("format", format)
                              .param("avcContext", avcContext.toString()))
                .user(u -> u.text("Extract the fare information from the attached document. " +
                                  "Map each fare to the correct AVC_ID based on vehicle description. " +
                                  "Use ONLY the AVC_IDs listed in the system instructions.")
                            .media(mediaList.toArray(new Media[0])))
                .call()
                .content();

        log.info("Phase 3: Raw AI response: {}", response);

        try {
            AiFareExtractionResult result = converter.convert(response);
            if (result == null) {
                throw new RuntimeException("AI returned null after conversion");
            }
            return result;
        } catch (Exception e) {
            log.error("Phase 3: Failed to parse AI response: {}", e.getMessage());
            // Try fallback: manual parsing
            try {
                AiFareExtractionResult fallback = objectMapper.readValue(response, AiFareExtractionResult.class);
                if (fallback.getFares() == null) {
                    fallback.setFares(List.of());
                }
                fallback.setWarnings(List.of("AI response required fallback parsing"));
                return fallback;
            } catch (Exception e2) {
                AiFareExtractionResult empty = new AiFareExtractionResult();
                empty.setFares(List.of());
                empty.setWarnings(List.of(
                        "Failed to parse AI response: " + e.getMessage(),
                        "Raw response: " + response
                ));
                return empty;
            }
        }
    }

    // =========================================================================
    // VALIDATION of AI response
    // =========================================================================
    private List<AiFareRowDto> validateAiResponse(AiFareExtractionResult aiResult,
                                                   Set<String> validAvcIds,
                                                   List<String> warnings) {
        if (aiResult.getFares() == null || aiResult.getFares().isEmpty()) {
            warnings.add("AI returned no fare rows.");
            return List.of();
        }

        List<AiFareRowDto> validated = new ArrayList<>();
        Set<String> seenAvcIds = new HashSet<>();

        for (AiFareRowDto fare : aiResult.getFares()) {
            // 1. AVC_ID must be present
            if (fare.getAvcId() == null || fare.getAvcId().isBlank()) {
                warnings.add("Skipped AI row with missing AVC_ID: " + fare.getVehicleDescs());
                continue;
            }

            String avcId = fare.getAvcId().trim();

            // 2. AVC_ID must exist in the Excel template
            if (!validAvcIds.contains(avcId)) {
                warnings.add("Skipped unknown AVC_ID=" + avcId +
                             " (not in Excel template). Vehicle: " + fare.getVehicleDescs());
                continue;
            }

            // 3. Check for duplicate AVC_ID
            if (seenAvcIds.contains(avcId)) {
                warnings.add("Duplicate AVC_ID=" + avcId + " in AI response. Using first occurrence.");
                continue;
            }

            seenAvcIds.add(avcId);
            validated.add(fare);
        }

        // 4. Check if any Excel AVC_IDs are missing from AI response
        for (String avcId : validAvcIds) {
            if (!seenAvcIds.contains(avcId)) {
                warnings.add("AVC_ID=" + avcId + " exists in Excel but AI did not extract fares for it.");
            }
        }

        return validated;
    }
}
