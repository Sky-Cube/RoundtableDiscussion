package com.aipanel.web.vo;

import java.util.List;

/** 管理台房间详情：完整快照与事件流。 */
public record AdminRoomVO(DiscussionSnapshotVO snapshot, List<AdminEventVO> events) {
}