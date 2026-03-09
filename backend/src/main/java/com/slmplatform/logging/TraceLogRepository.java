package com.slmplatform.logging;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TraceLogRepository extends MongoRepository<ApiTraceLog, String> {
    List<ApiTraceLog> findBySessionId(String sessionId);
}
