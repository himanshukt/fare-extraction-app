package com.example.fare.dto;

import lombok.Data;
import java.util.List;

/**
 * Wrapper DTO for the AI fare extraction response.
 * Used by BeanOutputConverter to deserialize the AI's structured output.
 */
@Data
public class AiFareExtractionResult {
    private List<AiFareRowDto> fares;
    private List<String> warnings;
}
