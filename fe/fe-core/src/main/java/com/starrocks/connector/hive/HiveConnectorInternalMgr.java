// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.hive;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.starrocks.common.Config;
import com.starrocks.common.util.Util;
import com.starrocks.connector.CachingRemoteFileConf;
import com.starrocks.connector.CachingRemoteFileIO;
import com.starrocks.connector.HdfsEnvironment;
import com.starrocks.connector.MetastoreType;
import com.starrocks.connector.ReentrantExecutor;
import com.starrocks.connector.RemoteFileIO;
import com.starrocks.sql.analyzer.SemanticException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.starrocks.connector.CachingRemoteFileIO.NEVER_REFRESH;
import static com.starrocks.connector.hive.HiveConnector.HIVE_METASTORE_TYPE;
import static com.starrocks.connector.hive.HiveConnector.HIVE_METASTORE_URIS;

public class HiveConnectorInternalMgr {
    public static final List<String> SUPPORTED_METASTORE_TYPE = Lists.newArrayList("hive", "glue", "dlf");
    private final String catalogName;
    private final HdfsEnvironment hdfsEnvironment;
    private final Map<String, String> properties;
    private final boolean enableMetastoreCache;
    private final CachingHiveMetastoreConf hmsConf;

    private final boolean enableRemoteFileCache;
    private final CachingRemoteFileConf remoteFileConf;

    private ExecutorService refreshHiveMetastoreExecutor;
    private ExecutorService refreshHiveExternalTableExecutor;
    private ExecutorService refreshRemoteFileExecutor;
    private ExecutorService pullRemoteFileExecutor;
    private ExecutorService updateRemoteFilesExecutor;
    private ExecutorService updateStatisticsExecutor;

    private final boolean isRecursive;
    private final int loadRemoteFileMetadataThreadNum;
    private final int updateRemoteFileMetadataThreadNum;
    private final boolean enableHmsEventsIncrementalSync;

    private final boolean enableBackgroundRefreshHiveMetadata;
    private final MetastoreType metastoreType;

    public HiveConnectorInternalMgr(String catalogName, Map<String, String> properties, HdfsEnvironment hdfsEnvironment) {
        this.catalogName = catalogName;
        this.properties = properties;
        this.hdfsEnvironment = hdfsEnvironment;
        this.enableMetastoreCache = Boolean.parseBoolean(properties.getOrDefault("enable_metastore_cache", "true"));
        this.hmsConf = new CachingHiveMetastoreConf(properties, "hive");

        this.enableRemoteFileCache = Boolean.parseBoolean(properties.getOrDefault("enable_remote_file_cache", "true"));
        this.remoteFileConf = new CachingRemoteFileConf(properties);

        this.isRecursive = Boolean.parseBoolean(properties.getOrDefault("enable_recursive_listing", "true"));
        this.loadRemoteFileMetadataThreadNum = Integer.parseInt(properties.getOrDefault("remote_file_load_thread_num",
                String.valueOf(Config.remote_file_metadata_load_concurrency)));
        this.updateRemoteFileMetadataThreadNum = Integer.parseInt(properties.getOrDefault("remote_file_update_thread_num",
                String.valueOf(Config.remote_file_metadata_load_concurrency / 4)));
        this.enableHmsEventsIncrementalSync = Boolean.parseBoolean(properties.getOrDefault("enable_hms_events_incremental_sync",
                String.valueOf(Config.enable_hms_events_incremental_sync)));

        this.enableBackgroundRefreshHiveMetadata = Boolean.parseBoolean(properties.getOrDefault(
                "enable_background_refresh_connector_metadata", "true"));

        String hiveMetastoreType = properties.getOrDefault(HIVE_METASTORE_TYPE, "hive").toLowerCase();
        if (!SUPPORTED_METASTORE_TYPE.contains(hiveMetastoreType)) {
            throw new SemanticException("hive metastore type [%s] is not supported", hiveMetastoreType);
        }

        if (hiveMetastoreType.equals("hive")) {
            String hiveMetastoreUris = Preconditions.checkNotNull(properties.get(HIVE_METASTORE_URIS),
                    "%s must be set in properties when creating hive catalog", HIVE_METASTORE_URIS);
            Util.validateMetastoreUris(hiveMetastoreUris);
        }
        this.metastoreType = MetastoreType.get(hiveMetastoreType);
    }

    public void shutdown() {
        if (enableMetastoreCache && refreshHiveMetastoreExecutor != null) {
            refreshHiveMetastoreExecutor.shutdown();
        }
        if (enableMetastoreCache && refreshHiveExternalTableExecutor != null) {
            refreshHiveExternalTableExecutor.shutdown();
        }
        if (enableRemoteFileCache && refreshRemoteFileExecutor != null) {
            refreshRemoteFileExecutor.shutdown();
        }
        if (pullRemoteFileExecutor != null) {
            pullRemoteFileExecutor.shutdown();
        }
    }

    public IHiveMetastore createHiveMetastore() {
        // TODO(stephen): Abstract the creator class to construct hive meta client
        HiveMetaClient metaClient = HiveMetaClient.createHiveMetaClient(hdfsEnvironment, properties);
        IHiveMetastore hiveMetastore = new HiveMetastore(metaClient, catalogName, metastoreType);
        IHiveMetastore baseHiveMetastore;
        if (!enableMetastoreCache) {
            baseHiveMetastore = hiveMetastore;
        } else {
            refreshHiveMetastoreExecutor = Executors.newCachedThreadPool(
                    new ThreadFactoryBuilder().setNameFormat("hive-metastore-refresh-%d").build());
            refreshHiveExternalTableExecutor = Executors.newCachedThreadPool(
                    new ThreadFactoryBuilder().setNameFormat("hive-external-table-refresh-%d").build());
            baseHiveMetastore = CachingHiveMetastore.createCatalogLevelInstance(
                    hiveMetastore,
                    new ReentrantExecutor(refreshHiveMetastoreExecutor, hmsConf.getCacheRefreshThreadMaxNum()),
                    new ReentrantExecutor(refreshHiveExternalTableExecutor, hmsConf.getCacheRefreshThreadMaxNum()),
                    hmsConf.getCacheTtlSec(),
                    enableHmsEventsIncrementalSync ? NEVER_REFRESH : hmsConf.getCacheRefreshIntervalSec(),
                    hmsConf.getCacheMaxNum(),
                    hmsConf.enableListNamesCache());
        }

        return baseHiveMetastore;
    }

    public RemoteFileIO createRemoteFileIO() {
        // TODO(stephen): Abstract the creator class to construct RemoteFiloIO

        RemoteFileIO remoteFileIO = new HiveRemoteFileIO(hdfsEnvironment.getConfiguration());

        RemoteFileIO baseRemoteFileIO;
        if (!enableRemoteFileCache) {
            baseRemoteFileIO = remoteFileIO;
        } else {
            refreshRemoteFileExecutor = Executors.newCachedThreadPool(
                    new ThreadFactoryBuilder().setNameFormat("hive-remote-files-refresh-%d").build());
            baseRemoteFileIO = CachingRemoteFileIO.createCatalogLevelInstance(
                    remoteFileIO,
                    new ReentrantExecutor(refreshRemoteFileExecutor, remoteFileConf.getRefreshMaxThreadNum()),
                    remoteFileConf.getCacheTtlSec(),
                    enableHmsEventsIncrementalSync ? NEVER_REFRESH : remoteFileConf.getCacheRefreshIntervalSec(),
                    remoteFileConf.getCacheMaxSize());
        }

        return baseRemoteFileIO;
    }

    public ExecutorService getPullRemoteFileExecutor() {
        if (pullRemoteFileExecutor == null) {
            pullRemoteFileExecutor = Executors.newFixedThreadPool(loadRemoteFileMetadataThreadNum,
                    new ThreadFactoryBuilder().setNameFormat("pull-hive-remote-files-%d").build());
        }

        return pullRemoteFileExecutor;
    }

    public ExecutorService getupdateRemoteFilesExecutor() {
        if (updateRemoteFilesExecutor == null) {
            updateRemoteFilesExecutor = Executors.newFixedThreadPool(updateRemoteFileMetadataThreadNum,
                    new ThreadFactoryBuilder().setNameFormat("update-hive-remote-files-%d").build());
        }

        return updateRemoteFilesExecutor;
    }

    public Executor getUpdateStatisticsExecutor() {
        Executor baseExecutor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder().setNameFormat("hive-metastore-update-%d").build());
        return new ReentrantExecutor(baseExecutor, remoteFileConf.getRefreshMaxThreadNum());
    }

    public Executor getRefreshOthersFeExecutor() {
        Executor baseExecutor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder().setNameFormat("refresh-others-fe-hive-metadata-cache-%d").build());
        return new ReentrantExecutor(baseExecutor, remoteFileConf.getRefreshMaxThreadNum());
    }

    public boolean isSearchRecursive() {
        return isRecursive;
    }

    public CachingHiveMetastoreConf getHiveMetastoreConf() {
        return hmsConf;
    }

    public CachingRemoteFileConf getRemoteFileConf() {
        return remoteFileConf;
    }

    public boolean enableHmsEventsIncrementalSync() {
        return enableHmsEventsIncrementalSync;
    }

    public HdfsEnvironment getHdfsEnvironment() {
        return hdfsEnvironment;
    }

    public boolean isEnableBackgroundRefreshHiveMetadata() {
        return enableBackgroundRefreshHiveMetadata;
    }

    public MetastoreType getMetastoreType() {
        return metastoreType;
    }
}