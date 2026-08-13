package com.example.fare.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FareTemplateRowDto {
    @JsonProperty("ENTRY_PLAZA_ID")
    private String entryPlazaId;

    @JsonProperty("EXIT_PLAZA_ID")
    private String exitPlazaId;

    @JsonProperty("AVC_ID")
    private String avcId;

    @JsonProperty("MVC_IDS")
    private String mvcIds;

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
