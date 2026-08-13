package com.example.fare.dto;

import lombok.Data;
import java.util.List;

@Data
public class FareTemplateResponseDto {
    private List<FareTemplateRowDto> rows;
    private List<String> warnings;
}
