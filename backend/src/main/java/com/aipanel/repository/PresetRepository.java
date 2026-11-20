package com.aipanel.repository;

import java.util.List;
import java.util.Optional;

import com.aipanel.model.Preset;

/** 预设读取仓储。复制与损坏注入都通过本接口，便于测试替换。 */
public interface PresetRepository {

    List<Preset> findAll();

    Optional<Preset> findBySlug(String slug);
}
