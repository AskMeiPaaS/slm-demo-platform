package com.slmplatform.logging;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ExternalApiLogRepository extends MongoRepository<ExternalApiLog, String> {
    List<ExternalApiLog> findBySessionId(String sessionId);
}
