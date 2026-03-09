package com.slmplatform.memory;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MemoryChunkRepository extends MongoRepository<MemoryChunk, String> {
    void deleteBySessionId(String sessionId);
}
