package com.example.fare.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO for a single fare row returned by the AI.
 * The AI maps fares to AVC_IDs provided from the Excel template.
 */
@Data
public class AiFareRowDto {
    @JsonProperty("AVC_ID")
    private String avcId;

    @JsonProperty("VEHICLE_DESCS")
    private String vehicleDescs;

    @JsonProperty("SINGLE_JOURNEY_FARE")
    private Double singleJourneyFare;

    @JsonProperty("RETURN_JOURNEY_FARE")
    private Double returnJourneyFare;

    @JsonProperty("COM_VEHICLE_FARE")
    private Double comVehicleFare;

    @JsonProperty("MONTHLY_PASS_FARE")
    private Double monthlyPassFare;

    @JsonProperty("LOCAL20_PASS_FARE")
    private Double local20PassFare;
}
