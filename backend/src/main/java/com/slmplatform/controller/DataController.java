package com.slmplatform.controller;

import com.slmplatform.seeder.DataSeederService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1/data")
@CrossOrigin(origins = "*")
public class DataController {

    private final DataSeederService dataSeederService;
    private final AtomicBoolean isSeeding = new AtomicBoolean(false);

    @Value("${platform.data-loader.enabled:true}")
    private boolean isDataLoaderEnabled;

    public DataController(DataSeederService dataSeederService) {
        this.dataSeederService = dataSeederService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of("enabled", isDataLoaderEnabled));
    }

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedData() {
        if (!isDataLoaderEnabled) {
            return ResponseEntity.ok(Map.of("status", "error", "message", "Data loader is disabled by configuration."));
        }
        return ResponseEntity.ok(dataSeederService.seedAndEmbedData());
    }

    @GetMapping(path = "/seed-stream", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> seedDataStream() {
        SseEmitter emitter = new SseEmitter(-1L); // Infinite timeout

        if (!isDataLoaderEnabled) {
            try {
                emitter.send(SseEmitter.event()
                        .data(Map.of("status", "error", "message",
                                "Data loading is disabled (ENABLE_DATA_LOADER is false).")));
                emitter.complete();
            } catch (Exception e) {
            }
            return ResponseEntity.ok().body(emitter);
        }

        if (!isSeeding.compareAndSet(false, true)) {
            try {
                emitter.send(SseEmitter.event()
                        .data(Map.of("status", "error", "message", "Seeding is already in progress...")));
                emitter.complete();
            } catch (Exception e) {
                // Ignored
            }
            return ResponseEntity.ok().body(emitter);
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicBoolean isComplete = new AtomicBoolean(false);

        // Keep-alive thread to prevent Nginx/proxy timeouts during long Voyage AI calls
        executor.execute(() -> {
            try {
                while (!isComplete.get()) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                // Ignore broken pipe if client disconnects early
            }
        });

        // Main processing thread
        executor.execute(() -> {
            try {
                dataSeederService.seedAndEmbedDataStream(emitter);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(Map.of("status", "error", "message", e.getMessage())));
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    // Ignored
                }
            } finally {
                isComplete.set(true);
                isSeeding.set(false);
                executor.shutdown();
            }
        });

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}