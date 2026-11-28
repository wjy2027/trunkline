package org.unreal.modelrouter.persistence.jpa.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;

import java.util.List;
import java.util.Optional;

/**
 * 配置数据仓库 (JPA 版本)
 * v1.5.1: 替代 R2DBC 的 ConfigRepository
 */
@Repository
public interface ConfigRepository extends JpaRepository<ConfigEntity, Long> {

    /**
     * 根据配置键查找最新版本
     */
    Optional<ConfigEntity> findFirstByConfigKeyAndIsLatestTrue(String configKey);

    /**
     * 根据配置键和版本号查找
     */
    Optional<ConfigEntity> findByConfigKeyAndVersion(String configKey, Integer version);

    /**
     * 容错查找（2026-11-28）：同 (configKey, version) 出现重复行（历史脏数据/并发写入/
     * 唯一约束缺失场景）时取 id 最大的一行，而不是抛 IncorrectResultSizeDataAccessException
     * 把整条启动初始化链路炸掉（实测后果：security.api-keys 合并失败 → 回退 YAML 初始化
     * 也失败 → 重启后 API Key 库清空，全部密钥 401）。
     */
    Optional<ConfigEntity> findFirstByConfigKeyAndVersionOrderByIdDesc(String configKey, Integer version);

    /**
     * 同 (configKey, version) 的全部行（供写入侧自愈去重用）。
     */
    List<ConfigEntity> findAllByConfigKeyAndVersion(String configKey, Integer version);

    /**
     * #811/#715：悲观行锁读取当前配置行（仅限事务内调用）。
     *
     * <p>供 {@link org.unreal.modelrouter.persistence.store.StoreManager#mutateConfig}
     * 的 JPA 实现使用：多副本共享同一 PG 时，行锁保证「读-改-写」跨 JVM 互斥，
     * 单 Key 用量回写不被兄弟副本的整表写覆盖。H2 单进程场景由 JVM 监视器兜底。
     * 方法名不带语义后缀（派生名不识别 ForUpdate），查询体显式声明。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ConfigEntity c WHERE c.configKey = :configKey AND c.version = :version")
    Optional<ConfigEntity> findByConfigKeyAndVersionForUpdate(
            @Param("configKey") String configKey, @Param("version") Integer version);

    /**
     * 查找配置键的所有版本号
     * @deprecated E1 后版本列表请用 {@link #findAllSnapshotVersionsByConfigKey(String)}
     *             （只含 version>=1 快照行；本方法会混入 v0 当前行）。
     */
    @Deprecated
    @Query("SELECT c.version FROM ConfigEntity c WHERE c.configKey = :configKey ORDER BY c.version ASC")
    List<Integer> findAllVersionsByConfigKey(@Param("configKey") String configKey);

    /**
     * 查找配置键的所有版本实体（按版本号升序）
     */
    List<ConfigEntity> findAllByConfigKeyOrderByVersionAsc(String configKey);

    /**
     * 查找所有最新版本的配置键
     */
    @Query("SELECT DISTINCT c.configKey FROM ConfigEntity c WHERE c.isLatest = true")
    List<String> findAllLatestConfigKeys();

    /**
     * 删除指定配置键的所有版本
     */
    @Modifying
    @Query("DELETE FROM ConfigEntity c WHERE c.configKey = :configKey")
    void deleteAllByConfigKey(@Param("configKey") String configKey);

    /**
     * 删除指定配置键的指定版本
     */
    @Modifying
    @Query("DELETE FROM ConfigEntity c WHERE c.configKey = :configKey AND c.version = :version")
    void deleteByConfigKeyAndVersion(@Param("configKey") String configKey, @Param("version") Integer version);

    /**
     * 将指定配置键的所有版本标记为非最新
     * @deprecated E1 后版本快照恒为 isLatest=false、当前值由 v0 行承载，
     *             「全量翻牌」不再有任何合法用途。
     */
    @Deprecated
    @Modifying
    @Query("UPDATE ConfigEntity c SET c.isLatest = false WHERE c.configKey = :configKey")
    void markAllAsNotLatest(@Param("configKey") String configKey);

    /**
     * 检查配置键是否存在
     */
    boolean existsByConfigKeyAndIsLatestTrue(String configKey);

    /**
     * 检查指定版本是否存在
     */
    boolean existsByConfigKeyAndVersion(String configKey, Integer version);

    /**
     * E1: 查找配置键的所有「版本快照」行（version >= 1，只追加、不改写）。
     * v0 是当前配置行，不属于版本序列。
     */
    @Query("SELECT c.version FROM ConfigEntity c WHERE c.configKey = :configKey AND c.version >= 1 ORDER BY c.version ASC")
    List<Integer> findAllSnapshotVersionsByConfigKey(@Param("configKey") String configKey);

    // findByConfigKeyAndVersion(configKey, version) 沿用上方既有声明：
    // E1 后它同时承担两类语义——version=0 时命中「当前配置行」，version>=1 命中「快照行」。
}
