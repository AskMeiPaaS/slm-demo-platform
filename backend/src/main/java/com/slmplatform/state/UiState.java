package com.slmplatform.state;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "ui_state")
public record UiState(
        @Id String deviceId,
        String activeTab,
        String chatSessionId,
        String observabilitySessionId,
        Instant lastUpdated) {
}
