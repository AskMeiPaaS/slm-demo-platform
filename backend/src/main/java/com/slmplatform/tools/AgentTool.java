package com.slmplatform.tools;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public interface AgentTool {
    String getName();

    String getDescription();

    String execute(String parametersJson, String sessionId);
}
