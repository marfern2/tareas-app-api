package com.tareas.app.demo.controller.publicapi;

import com.tareas.app.demo.dto.publicapi.PublicDemoDtos.*;
import com.tareas.app.demo.service.PublicDemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/demo")
@RequiredArgsConstructor
public class PublicDemoController {
    private final PublicDemoService service;

    private <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/users")
    public ResponseEntity<PublicDemoPage<PublicDemoUser>> users(@RequestParam MultiValueMap<String, String> params) {
        return response(service.users(params));
    }

    @GetMapping("/users/{publicId}")
    public ResponseEntity<PublicDemoUser> user(@PathVariable String publicId,
                                                @RequestParam MultiValueMap<String, String> params) {
        return response(service.user(publicId, params));
    }

    @GetMapping("/task-types")
    public ResponseEntity<PublicDemoPage<PublicDemoTaskType>> types(@RequestParam MultiValueMap<String, String> params) {
        return response(service.types(params));
    }

    @GetMapping("/task-types/{publicId}")
    public ResponseEntity<PublicDemoTaskType> type(@PathVariable String publicId,
                                                    @RequestParam MultiValueMap<String, String> params) {
        return response(service.type(publicId, params));
    }

    @GetMapping("/tasks")
    public ResponseEntity<PublicDemoPage<PublicDemoTask>> tasks(@RequestParam MultiValueMap<String, String> params) {
        return response(service.tasks(params));
    }

    @GetMapping("/tasks/{publicId}")
    public ResponseEntity<PublicDemoTask> task(@PathVariable String publicId,
                                                @RequestParam MultiValueMap<String, String> params) {
        return response(service.task(publicId, params));
    }

    @GetMapping("/stats")
    public ResponseEntity<PublicDemoStats> stats(@RequestParam MultiValueMap<String, String> params) {
        return response(service.stats(params));
    }
}
