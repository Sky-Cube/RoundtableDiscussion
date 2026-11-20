package com.aipanel.gateway;

import java.util.List;

/**
 * 模型网关抽象。真实供应商适配在后续阶段实现；本阶段只在测试与本地联调提供替身。
 */
public interface ModelGateway {

    /**
     * 生成阵容。
     *
     * @param topic          规范化后的议题
     * @param expertCount    专家人数
     * @param validationCodes 上一次不合格输出的校验码，空列表表示首次调用；非空即 R08 补偿
     */
    String generateRoster(String topic, int expertCount, List<String> validationCodes);
}
