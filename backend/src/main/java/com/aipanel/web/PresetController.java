package com.aipanel.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aipanel.service.PresetService;
import com.aipanel.web.vo.DiscussionSnapshotVO;
import com.aipanel.web.vo.PresetVO;

/** 预设读取与预设复制。复制不调用模型网关。 */
@RestController
@RequestMapping("/api/v1/presets")
public class PresetController {

    private final PresetService presetService;

    public PresetController(PresetService presetService) {
        this.presetService = presetService;
    }

    @GetMapping
    public ApiResponse<List<PresetVO>> list() {
        return ApiResponse.ok(presetService.list());
    }

    @PostMapping("/{slug}/discussions")
    public ResponseEntity<ApiResponse<DiscussionSnapshotVO>> copy(@PathVariable String slug) {
        DiscussionSnapshotVO snapshot = presetService.copy(slug);
        return ResponseEntity.created(URI.create("/api/v1/discussions/" + snapshot.id()))
                .body(ApiResponse.success(HttpStatus.CREATED, snapshot));
    }
}
