package com.aipanel.web.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

/**
 * 新建草稿请求。只接受 topic 与可选 expertCount；其他字段由 Jackson 严格反序列化拒绝。
 *
 * <p>显式传 expertCount=null 与省略 expertCount 语义不同：前者非法，后者取默认 4。
 */
public class DiscussionCreateRequest {

    private String topic;
    private Integer expertCount;
    private boolean expertCountSpecified;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Integer getExpertCount() {
        return expertCount;
    }

    @JsonSetter("expertCount")
    public void setExpertCount(Integer expertCount) {
        this.expertCount = expertCount;
        this.expertCountSpecified = true;
    }

    public boolean expertCountSpecified() {
        return expertCountSpecified;
    }
}
