package com.slmplatform.memory;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ChatMemoryRepository extends MongoRepository<ChatMemory, String> {
    List<ChatMemory> findTop10BySessionIdOrderByTimestampDesc(String sessionId);

    void deleteBySessionId(String sessionId);
}
