package com.tareas.app.demo.controller.admin;

import com.tareas.app.demo.fixtures.DemoFixtureService;
import com.tareas.app.demo.fixtures.DemoFixtureService.Preview;
import com.tareas.app.demo.fixtures.DemoFixtureService.RestoreResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/demo/fixtures")
@RequiredArgsConstructor
public class DemoFixtureController {
    private final DemoFixtureService service;

    @GetMapping("/restore-preview")
    public ResponseEntity<Preview> preview() {
        Preview p = service.preview();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(p.etag()).body(p);
    }

    @PostMapping("/restore")
    public ResponseEntity<RestoreResult> restore(@RequestHeader(value = "If-Match", required = false) String ifMatch) {
        RestoreResult result = service.restore(ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result);
    }
}
