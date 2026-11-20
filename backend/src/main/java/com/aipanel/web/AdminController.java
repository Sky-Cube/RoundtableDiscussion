package com.aipanel.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aipanel.service.AdminService;
import com.aipanel.web.vo.AdminDiscussionVO;
import com.aipanel.web.vo.AdminOverviewVO;
import com.aipanel.web.vo.AdminRoomVO;

/** 后台管理接口：总览、按名称/日期检索、房间详情与事件流，仅本机可访问。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminOverviewVO> overview() {
        return ApiResponse.ok(adminService.overview());
    }

    @GetMapping("/discussions")
    public ApiResponse<List<AdminDiscussionVO>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String date) {
        return ApiResponse.ok(adminService.search(status, keyword, date));
    }

    @GetMapping("/discussions/{id}")
    public ApiResponse<AdminRoomVO> detail(@PathVariable String id) {
        return ApiResponse.ok(adminService.detail(id));
    }
}
