package com.slmplatform.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WeatherTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public String getDescription() {
        return "Fetches the current weather for a specific location. Required parameter: 'location'.";
    }

    @Override
    public String execute(String parametersJson, String sessionId) {
        try {
            Map<String, String> params = objectMapper.readValue(parametersJson,
                    new TypeReference<Map<String, String>>() {
                    });
            String location = params.get("location");

            if (location == null || location.isBlank()) {
                return "{ \"error\": \"'location' parameter is required.\" }";
            }

            log.info("[WeatherTool] Fetching mock weather for location: {}", location);

            // For the demo, we'll return a deterministic mock response based on the
            // location length
            // to simulate a fast external API call without actual network overhead or API
            // keys.
            int temp = 50 + (location.length() * 3);
            String conditions = location.length() % 2 == 0 ? "Sunny and clear" : "Cloudy with scattered showers";

            return String.format("Current weather in %s: %d°F, %s.", location, temp, conditions);

        } catch (Exception e) {
            log.error("[WeatherTool] Error executing tool: {}", e.getMessage());
            return "{ \"error\": \"Failed to fetch weather: " + e.getMessage() + "\" }";
        }
    }
}
