package com.slmplatform.state;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UiStateRepository extends MongoRepository<UiState, String> {
}
