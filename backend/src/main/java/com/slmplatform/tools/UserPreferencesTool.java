package com.slmplatform.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserPreferencesTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(UserPreferencesTool.class);
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserPreferencesTool(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public String getName() {
        return "user_preferences";
    }

    @Override
    public String getDescription() {
        return "Saves or retrieves the user's favorite movie genres or actors. Required parameters: 'action' (must be 'save' or 'get'), and 'preferences' (a string describing their favorites, required if action is 'save').";
    }

    @Override
    public String execute(String parametersJson, String sessionId) {
        try {
            Map<String, String> params = objectMapper.readValue(parametersJson,
                    new TypeReference<Map<String, String>>() {
                    });
            String action = params.get("action");
            String preferences = params.get("preferences");

            if (action == null || (!action.equals("save") && !action.equals("get"))) {
                return "{ \"error\": \"Invalid action. Must be 'save' or 'get'.\" }";
            }

            Query query = new Query(Criteria.where("sessionId").is(sessionId));

            if ("save".equals(action)) {
                if (preferences == null || preferences.isBlank()) {
                    return "{ \"error\": \"'preferences' parameter is required when saving.\" }";
                }
                Update update = new Update().set("preferences", preferences);
                mongoTemplate.upsert(query, update, "user_preferences");
                log.info("[UserPreferencesTool] Saved preferences for session {}: {}", sessionId, preferences);
                return "Successfully saved preferences: " + preferences;
            } else {
                Document doc = mongoTemplate.findOne(query, Document.class, "user_preferences");
                if (doc != null && doc.getString("preferences") != null) {
                    String savedPrefs = doc.getString("preferences");
                    log.info("[UserPreferencesTool] Retrieved preferences for session {}: {}", sessionId, savedPrefs);
                    return "User's saved preferences: " + savedPrefs;
                } else {
                    log.info("[UserPreferencesTool] No preferences found for session {}", sessionId);
                    return "No preferences saved for this user yet.";
                }
            }
        } catch (Exception e) {
            log.error("[UserPreferencesTool] Error executing tool: {}", e.getMessage());
            return "{ \"error\": \"Failed to execute preferences tool: " + e.getMessage() + "\" }";
        }
    }
}
