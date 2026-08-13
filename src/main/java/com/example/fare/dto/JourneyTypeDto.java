package com.example.fare.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class JourneyTypeDto {
    private String journey_type;
    private BigDecimal fare_amount;
    private String currency;
    private Double confidence;
}
