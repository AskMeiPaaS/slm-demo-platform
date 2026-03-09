package com.slmplatform.controller;

import com.slmplatform.state.UiState;
import com.slmplatform.state.UiStateRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/uistate")
@CrossOrigin(origins = "*")
public class UiStateController {

    private final UiStateRepository uiStateRepository;

    public UiStateController(UiStateRepository uiStateRepository) {
        this.uiStateRepository = uiStateRepository;
    }

    @GetMapping("/{deviceId}")
    public UiState getUiState(@PathVariable String deviceId) {
        return uiStateRepository.findById(deviceId)
                .orElse(new UiState(deviceId, "data", "", "", Instant.now()));
    }

    @PostMapping("/{deviceId}")
    public UiState saveUiState(@PathVariable String deviceId, @RequestBody UiStateRequest request) {
        UiState state = new UiState(
                deviceId,
                request.activeTab(),
                request.chatSessionId(),
                request.observabilitySessionId(),
                Instant.now());
        return uiStateRepository.save(state);
    }

    @DeleteMapping("/{deviceId}")
    public void deleteUiState(@PathVariable String deviceId) {
        uiStateRepository.deleteById(deviceId);
    }
}

record UiStateRequest(String activeTab, String chatSessionId, String observabilitySessionId) {
}
