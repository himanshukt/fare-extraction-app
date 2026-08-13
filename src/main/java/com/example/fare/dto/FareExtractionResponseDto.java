package com.example.fare.dto;

import lombok.Data;
import java.util.List;

@Data
public class FareExtractionResponseDto {
    private TollPlazaDto toll_plaza;
    private String effective_date;
    private List<VehicleClassDto> vehicle_classes;
    private List<String> warnings;
    private Double extraction_confidence;
}
