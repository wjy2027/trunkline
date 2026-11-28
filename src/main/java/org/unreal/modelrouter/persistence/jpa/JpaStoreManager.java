package org.unreal.modelrouter.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ConfigRepository;
import org.unreal.modelrouter.persistence.store.StoreManager;
import org.unreal.modelrouter.common.util.JacksonHelper;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * JPA 存储管理器
 * v1.5.1: 完全替代 R2DBC 的 H2StoreManager
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaStoreManager implements StoreManager {

    /**
     * E1: 当前配置行的哨兵版本号。config_data 中 version=0 的行专职「当前生效配置」，
     * version>=1 的行专职「版本快照」（只追加、永不改写）。
     */
    public static final int CURRENT_CONFIG_VERSION = 0;

    private final ConfigRepository configRepository;

    /**
     * 保存当前生效配置（upsert version=0 的当前行）。
     *
     * <p>E1 语义：本方法只维护「当前值」，绝不触碰 version>=1 的版本快照行——
     * 旧实现会就地覆盖 isLatest=true 行（该行同时是最新版本快照），导致
     * saveAsNewVersion 创建的版本内容立刻被篡改、applyVersion 把历史快照反向覆盖。</p>
     */
    @Override
    @Transactional
    public void saveConfig(final String key, final Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            // 空配置表示清空该 key：删除当前行 + 全部版本快照行。
            // 旧实现直接 return 会导致「删除集合中最后一项」变成假删除——
            // DB 旧值保留，下次 loadAll 又把已删除项读回（模型分组/路由规则/适配器/API Key 均踩过）。
            deleteConfig(key);
            return;
        }
        try {
            String configValue = JacksonHelper.getObjectMapper().writeValueAsString(config);

            // E1: 当前值固定落 version=0 行（不再依赖 isLatest 标志定位）。
            // 容错读取 + 写入时自愈去重：同 key 同版本重复行（历史脏数据/异常写入）会导致
            // 精确单行查询抛 IncorrectResultSizeDataAccessException，进而炸掉整条初始化链路
            // （2026-11-28 实测：security.api-keys 双 v0 行 → 重启后 API Key 库清空）。
            final List<ConfigEntity> currentRows =
                    configRepository.findAllByConfigKeyAndVersion(key, CURRENT_CONFIG_VERSION);
            if (currentRows.size() > 1) {
                log.warn("Detected {} duplicate current rows for key: {}, healing (keep max id)",
                        currentRows.size(), key);
                currentRows.sort(Comparator.comparing(ConfigEntity::getId).reversed());
                for (final ConfigEntity extra : currentRows.subList(1, currentRows.size())) {
                    configRepository.delete(extra);
                }
            }
            final Optional<ConfigEntity> existingEntity =
                    currentRows.isEmpty()
                            ? Optional.empty()
                            : Optional.of(currentRows.get(0));

            if (existingEntity.isPresent()) {
                ConfigEntity entity = existingEntity.get();
                entity.setConfigValue(configValue);
                entity.setUpdatedAt(LocalDateTime.now());
                configRepository.save(entity);
                log.debug("Updated current config for key: {} (v0 current row)", key);
            } else {
                ConfigEntity entity = ConfigEntity.builder()
                        .configKey(key)
                        .configValue(configValue)
                        .version(CURRENT_CONFIG_VERSION)
                        .isLatest(true)
                        .build();
                configRepository.save(entity);
                log.info("Created current config for key: {} (v0 current row)", key);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize config for key: {}", key, e);
            throw new RuntimeException("Failed to save config", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getConfig(final String key) {
        // E1: 当前值 = version=0 行；兼容迁移前数据（无 v0 行时回退 isLatest 行）。
        // 【多租户】必须显式开启只读事务：TenantJpaFilter 切面只在「事务已激活」时
        // enableFilter（见 TenantJpaFilter.applyTenantFilter 的 isActualTransactionActive 判定），
        // 且事务拦截器 order 严格小于切面 order（见 TenantAspectOrderingConfiguration）。
        // 若本方法不带事务，Spring Data 仓库方法自带的事务在切面之后才开启，切面跳过过滤 →
        // findByConfigKeyAndVersion(key, 0) 返回所有租户的行 → NonUniqueResultException
        // 被调用方（如 PoolPersistenceService.loadAll）吞成空集合，表现为「切租户/新建后看不到数据」。
        // 容错读取（2026-11-28）：重复行取 id 最大者，不让脏数据把读链路炸成异常。
        final Optional<ConfigEntity> currentRow =
                configRepository.findFirstByConfigKeyAndVersionOrderByIdDesc(key, CURRENT_CONFIG_VERSION);
        if (currentRow.isPresent()) {
            return deserializeConfig(currentRow.get());
        }
        return configRepository.findFirstByConfigKeyAndIsLatestTrue(key)
                .map(this::deserializeConfig)
                .orElse(null);
    }

    @Override
    @Transactional
    public void deleteConfig(final String key) {
        configRepository.deleteAllByConfigKey(key);
        log.info("Deleted all versions of config for key: {}", key);
    }

    @Override
    public Iterable<String> getAllKeys() {
        return configRepository.findAllLatestConfigKeys();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(final String key) {
        return configRepository.existsByConfigKeyAndIsLatestTrue(key);
    }

    @Override
    public void updateConfig(final String key, final Map<String, Object> config) {
        // 更新操作与保存相同
        saveConfig(key, config);
    }

    /**
     * 追加一条版本快照（version >= 1，isLatest = false，只追加、永不改写）。
     *
     * <p>E1 语义：版本行是不可变历史——旧实现的 markAllAsNotLatest + isLatest=true
     * 会让「最新版本行」与「当前配置行」合一，后续 saveConfig/applyVersion 都会
     * 就地篡改这份快照。新实现下版本行写入后内容冻结，当前值由 v0 行独立承载。</p>
     *
     * <p>若同 key 同版本行已存在（异常场景/重放），幂等跳过而非覆盖——
     * 唯一约束 uk_config_data_key_version 兜底。</p>
     */
    @Override
    @Transactional
    public void saveConfigVersion(final String key, final Map<String, Object> config, final int version) {
        if (config == null || config.isEmpty()) {
            return;
        }
        if (version <= CURRENT_CONFIG_VERSION) {
            throw new IllegalArgumentException(
                    "版本快照 version 必须 >= 1（0 保留给当前配置行）: key=" + key + ", version=" + version);
        }
        try {
            // 幂等：已有同号快照（重放/恢复场景）直接返回，拒绝静默改写历史
            if (configRepository.existsByConfigKeyAndVersion(key, version)) {
                log.info("Config version already exists, skip append (snapshot is immutable): key={}, version={}",
                        key, version);
                return;
            }

            String configValue = JacksonHelper.getObjectMapper().writeValueAsString(config);

            // E1: 版本行 isLatest=false（当前值由 v0 行承载），只追加、不改写任何既有行
            ConfigEntity entity = ConfigEntity.builder()
                    .configKey(key)
                    .configValue(configValue)
                    .version(version)
                    .isLatest(false)
                    .build();

            configRepository.save(entity);
            log.info("Appended config version snapshot: key={}, version={} (immutable, isLatest=false)", key, version);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize config for key: {} version: {}", key, version, e);
            throw new RuntimeException("Failed to save config version", e);
        }
    }

    @Override
    public List<Integer> getConfigVersions(final String key) {
        // E1: 版本列表只含快照行（version >= 1），v0 是当前行不属于版本序列
        return configRepository.findAllSnapshotVersionsByConfigKey(key);
    }

    @Override
    public Map<String, Object> getConfigByVersion(final String key, final int version) {
        // E1: version=0 语义统一为「当前配置」（与 VersionDiffService/VersionClassesTest 既有约定一致）
        if (version == CURRENT_CONFIG_VERSION) {
            return getConfig(key);
        }
        return configRepository.findFirstByConfigKeyAndVersionOrderByIdDesc(key, version)
                .map(this::deserializeConfig)
                .orElse(null);
    }

    @Override
    @Transactional
    public void deleteConfigVersion(final String key, final int version) {
        configRepository.deleteByConfigKeyAndVersion(key, version);
        log.info("Deleted config version for key: {}, version: {}", key, version);
    }

    @Override
    public boolean versionExists(final String key, final int version) {
        return configRepository.existsByConfigKeyAndVersion(key, version);
    }

    @Override
    public String getVersionFilePath(final String key, final int version) {
        // JPA 存储不基于文件，返回标识信息
        return "jpa://" + key + "/v" + version;
    }

    @Override
    public LocalDateTime getVersionCreatedTime(final String key, final int version) {
        return configRepository.findFirstByConfigKeyAndVersionOrderByIdDesc(key, version)
                .map(ConfigEntity::getCreatedAt)
                .orElse(null);
    }

    @Override
    public Map<String, Object> getLatestConfig(final String configKey) {
        return getConfig(configKey);
    }

    /**
     * #811/#715：JPA 实现——事务 + 悲观行锁的读改写。
     *
     * <p>两层互斥：JVM 内按实例监视器串行（{@code synchronized}），
     * 跨副本共享同一 PG 时由 {@code SELECT ... FOR UPDATE} 行锁互斥。
     * 读侧用带锁查询命中 v0 当前行（迁移期无 v0 行时回退 isLatest 行，不加锁），
     * 写侧复用 {@link #saveConfig} 的 upsert 语义。</p>
     */
    @Override
    @Transactional
    public Map<String, Object> mutateConfig(final String key, final UnaryOperator<Map<String, Object>> mutator) {
        synchronized (this) {
            final Map<String, Object> current = readConfigForUpdate(key);
            final Map<String, Object> updated = mutator.apply(current);
            if (updated != null) {
                saveConfig(key, updated);
            }
            return updated;
        }
    }

    /**
     * 带行锁的当前值读取（仅限 {@link #mutateConfig} 的事务内调用）。
     */
    private Map<String, Object> readConfigForUpdate(final String key) {
        final Optional<ConfigEntity> currentRow =
                configRepository.findByConfigKeyAndVersionForUpdate(key, CURRENT_CONFIG_VERSION);
        if (currentRow.isPresent()) {
            return deserializeConfig(currentRow.get());
        }
        return configRepository.findFirstByConfigKeyAndIsLatestTrue(key)
                .map(this::deserializeConfig)
                .orElse(null);
    }

    private Map<String, Object> deserializeConfig(final ConfigEntity entity) {
        try {
            return JacksonHelper.getObjectMapper().readValue(
                    entity.getConfigValue(),
                    new TypeReference<Map<String, Object>>() { }
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize config for key: {} version: {}", 
                    entity.getConfigKey(), entity.getVersion(), e);
            throw new RuntimeException("Failed to deserialize config", e);
        }
    }
}
