package com.example.fare.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@Data
public class VehicleClassDto {
    @JsonProperty("avc_id")
    private String class_id;
    private String class_name;
    private String description;
    private List<JourneyTypeDto> journey_types;
}
