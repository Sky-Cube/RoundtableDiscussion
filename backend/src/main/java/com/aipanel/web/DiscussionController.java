package com.aipanel.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;
import com.aipanel.service.DiscussionService;
import com.aipanel.util.Uuids;
import com.aipanel.web.dto.DiscussionCreateRequest;
import com.aipanel.web.vo.DiscussionSnapshotVO;
import com.aipanel.web.vo.DiscussionSummaryVO;
import com.aipanel.web.vo.StartResultVO;

/** 大厅列表、草稿创建、确认开场与房间快照。 */
@RestController
@RequestMapping("/api/v1/discussions")
public class DiscussionController {

    private final DiscussionService discussionService;

    public DiscussionController(DiscussionService discussionService) {
        this.discussionService = discussionService;
    }

    @GetMapping
    public ApiResponse<List<DiscussionSummaryVO>> list(@RequestParam(required = false) String status) {
        if (status != null && !"ACTIVE".equals(status)) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return ApiResponse.ok(discussionService.listActive());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DiscussionSnapshotVO>> create(
            @RequestBody(required = false) DiscussionCreateRequest request) {
        if (request == null) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        DiscussionSnapshotVO snapshot = discussionService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/discussions/" + snapshot.id()))
                .body(ApiResponse.success(HttpStatus.CREATED, snapshot));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<StartResultVO>> start(@PathVariable String id) {
        StartResultVO result = discussionService.start(Uuids.requireId(id));
        return ResponseEntity.accepted().body(ApiResponse.success(HttpStatus.ACCEPTED, result));
    }

    @GetMapping("/{id}")
    public ApiResponse<DiscussionSnapshotVO> get(@PathVariable String id) {
        return ApiResponse.ok(discussionService.get(Uuids.requireId(id)));
    }
}
