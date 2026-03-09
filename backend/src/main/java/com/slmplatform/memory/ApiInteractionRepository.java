package com.slmplatform.memory;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ApiInteractionRepository extends MongoRepository<ApiInteraction, String> {
}
