/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.core.listener;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import com.alibaba.nacos.common.executor.ExecutorFactory;
import com.alibaba.nacos.common.executor.NameThreadFactory;
import com.alibaba.nacos.common.executor.ThreadPoolManager;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.event.ServerConfigChangeEvent;
import com.alibaba.nacos.sys.env.EnvUtil;
import com.alibaba.nacos.sys.file.FileChangeEvent;
import com.alibaba.nacos.sys.file.FileWatcher;
import com.alibaba.nacos.sys.file.WatchFileCenter;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import com.alibaba.nacos.sys.utils.DiskUtils;
import com.alibaba.nacos.sys.utils.InetUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * init environment config.
 *
 * @author <a href="mailto:huangxiaoyu1018@gmail.com">hxy1991</a>
 * @since 0.5.0
 */
public class StartingApplicationListener implements NacosApplicationListener {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(StartingApplicationListener.class);
    
    private static final String MODE_PROPERTY_KEY_STAND_MODE = "nacos.mode";
    
    private static final String MODE_PROPERTY_KEY_FUNCTION_MODE = "nacos.function.mode";
    
    private static final String LOCAL_IP_PROPERTY_KEY = "nacos.local.ip";
    
    private static final String NACOS_APPLICATION_CONF = "nacos_application_conf";
    
    private static final String NACOS_MODE_STAND_ALONE = "stand alone";
    
    private static final String NACOS_MODE_CLUSTER = "cluster";
    
    private static final String DEFAULT_FUNCTION_MODE = "All";
    
    private static final String DEFAULT_DATABASE = "mysql";
    
    private static final String DATASOURCE_PLATFORM_PROPERTY = "spring.datasource.platform";
    
    private static final String DEFAULT_DATASOURCE_PLATFORM = "";
    
    private static final String DATASOURCE_MODE_EXTERNAL = "external";
    
    private static final String DATASOURCE_MODE_EMBEDDED = "embedded";
    
    private static final Map<String, Object> SOURCES = new ConcurrentHashMap<>();
    
    private ScheduledExecutorService scheduledExecutorService;
    
    private volatile boolean starting;
    
    @Override
    public void starting() {
        starting = true;
    }
    
    @Override
    public void environmentPrepared(ConfigurableEnvironment environment) {
        makeWorkDir();
        
        injectEnvironment(environment);
        
        loadPreProperties(environment);
        
        initSystemProperty();
    }
    
    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {
        logClusterConf();
        
        logStarting();
    }
    
    @Override
    public void contextLoaded(ConfigurableApplicationContext context) {
    
    }
    
    @Override
    public void started(ConfigurableApplicationContext context) {
        //标记启动中结束
        starting = false;
        //关闭启动中日志输出
        closeExecutor();
        //设置启动完成标识
        ApplicationUtils.setStarted(true);
        //设置存储模式
        judgeStorageMode(context.getEnvironment());
    }
    
    @Override
    public void running(ConfigurableApplicationContext context) {
    }
    
    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {
        starting = false;
        
        makeWorkDir();
        
        LOGGER.error("Startup errors : ", exception);
        ThreadPoolManager.shutdown();
        WatchFileCenter.shutdown();
        NotifyCenter.shutdown();
        
        closeExecutor();
        
        context.close();
        
        LOGGER.error("Nacos failed to start, please see {} for more details.",
                Paths.get(EnvUtil.getNacosHome(), "logs/nacos.log"));
    }

    /**
     * @Description: 将环境配置注入到Nacos Env
     * @author Levi.Ding
     * @date 2023/6/26 18:02
     * @param environment :
     * @return : void
     */
    private void injectEnvironment(ConfigurableEnvironment environment) {
        EnvUtil.setEnvironment(environment);
    }
    
    /**
     * @Description: 将 conf/application.properties 中配置信息 加载到环境配置中
     * 注册 conf/application.properties 文件监听器
     * @author Levi.Ding
     * @date 2023/6/29 16:35
     * @param environment : 
     * @return : void
     */
    private void loadPreProperties(ConfigurableEnvironment environment) {
        try {
            SOURCES.putAll(EnvUtil.loadProperties(EnvUtil.getApplicationConfFileResource()));
            environment.getPropertySources()
                    .addLast(new OriginTrackedMapPropertySource(NACOS_APPLICATION_CONF, SOURCES));
            registerWatcher();
        } catch (Exception e) {
            throw new NacosRuntimeException(NacosException.SERVER_ERROR, e);
        }
    }
    
    /**
     * @Description: 监听 nacos/conf/application.properties 文件
     * 发生变更时，从新加载配置 ，并发布 ServerConfigChangeEvent.newEvent() 事件
     * @author Levi.Ding
     * @date 2023/6/29 16:43
     * @return : void
     */
    private void registerWatcher() throws NacosException {

        WatchFileCenter.registerWatcher(EnvUtil.getConfPath(), new FileWatcher() {
            @Override
            public void onChange(FileChangeEvent event) {
                try {
                    Map<String, ?> tmp = EnvUtil.loadProperties(EnvUtil.getApplicationConfFileResource());
                    SOURCES.putAll(tmp);
                    NotifyCenter.publishEvent(ServerConfigChangeEvent.newEvent());
                } catch (IOException ignore) {
                    LOGGER.warn("Failed to monitor file ", ignore);
                }
            }
            
            @Override
            public boolean interest(String context) {
                return StringUtils.contains(context, "application.properties");
            }
        });
        
    }

    /**
     * @Description: 初始化系统配置
     * @author Levi.Ding
     * @date 2023/6/29 16:57
     * @return : void
     */
    private void initSystemProperty() {
        //集群模式
        if (EnvUtil.getStandaloneMode()) {
            System.setProperty(MODE_PROPERTY_KEY_STAND_MODE, NACOS_MODE_STAND_ALONE);
        } else {
            System.setProperty(MODE_PROPERTY_KEY_STAND_MODE, NACOS_MODE_CLUSTER);
        }
        //使用模式
        if (EnvUtil.getFunctionMode() == null) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, DEFAULT_FUNCTION_MODE);
        } else if (EnvUtil.FUNCTION_MODE_CONFIG.equals(EnvUtil.getFunctionMode())) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, EnvUtil.FUNCTION_MODE_CONFIG);
        } else if (EnvUtil.FUNCTION_MODE_NAMING.equals(EnvUtil.getFunctionMode())) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, EnvUtil.FUNCTION_MODE_NAMING);
        }
        //节点ip
        System.setProperty(LOCAL_IP_PROPERTY_KEY, InetUtils.getSelfIP());
    }

    /**
     * @Description: 集群模式， 加载一遍集群节点文件 nacos/conf/cluster.conf 无任何存储，只进行加载，如果文件不存在 则 log error
     * @author Levi.Ding
     * @date 2023/7/3 14:18
     * @return : void
     */
    private void logClusterConf() {
        if (!EnvUtil.getStandaloneMode()) {
            try {
                List<String> clusterConf = EnvUtil.readClusterConf();
                LOGGER.info("The server IP list of Nacos is {}", clusterConf);
            } catch (IOException e) {
                LOGGER.error("read cluster conf fail", e);
            }
        }
    }

    /**
     * @Description: 关闭节点启动日志
     * @author Levi.Ding
     * @date 2023/7/20 15:40
     * @return : void
     */
    private void closeExecutor() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }
    }

    /**
     * @Description: 确定 nacos/logs,nacos/conf,nacos/data 为文件夹
     * @author Levi.Ding
     * @date 2023/6/26 17:33
     * @return : void
     */
    private void makeWorkDir() {
        String[] dirNames = new String[] {"logs", "conf", "data"};
        for (String dirName : dirNames) {
            LOGGER.info("Nacos Log files: {}", Paths.get(EnvUtil.getNacosHome(), dirName).toString());
            try {
                DiskUtils.forceMkdir(new File(Paths.get(EnvUtil.getNacosHome(), dirName).toUri()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @Description: 集群模式 节点启动中时记录日志
     * @author Levi.Ding
     * @date 2023/7/3 15:10
     * @return : void
     */
    private void logStarting() {
        if (!EnvUtil.getStandaloneMode()) {
            
            scheduledExecutorService = ExecutorFactory
                    .newSingleScheduledExecutorService(new NameThreadFactory("com.alibaba.nacos.core.nacos-starting"));
            
            scheduledExecutorService.scheduleWithFixedDelay(() -> {
                if (starting) {
                    LOGGER.info("Nacos is starting...");
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * @Description: 设置存储模式为 内置数据源 还是 外置数据源
     * 单机模式下默认使用内置数据源，如果要开启外置数据源 需要设置 spring.datasource.platform=mysql
     * 集群模式下默认使用外置数据源，如果要开启内置数据源 启动时需要增加 -p embedded 命令
     * @author Levi.Ding
     * @date 2023/7/20 15:58
     * @param env :
     * @return : void
     */
    private void judgeStorageMode(ConfigurableEnvironment env) {
        
        // External data sources are used by default in cluster mode
        boolean useExternalStorage = (DEFAULT_DATABASE.equalsIgnoreCase(env.getProperty(DATASOURCE_PLATFORM_PROPERTY, DEFAULT_DATASOURCE_PLATFORM)));
        
        // must initialize after setUseExternalDB
        // This value is true in stand-alone mode and false in cluster mode
        // If this value is set to true in cluster mode, nacos's distributed storage engine is turned on
        // default value is depend on ${nacos.standalone}
        
        if (!useExternalStorage) {
            boolean embeddedStorage = EnvUtil.getStandaloneMode() || Boolean.getBoolean("embeddedStorage");
            // If the embedded data source storage is not turned on, it is automatically
            // upgraded to the external data source storage, as before
            if (!embeddedStorage) {
                useExternalStorage = true;
            }
        }
        
        LOGGER.info("Nacos started successfully in {} mode. use {} storage",
                System.getProperty(MODE_PROPERTY_KEY_STAND_MODE), useExternalStorage ? DATASOURCE_MODE_EXTERNAL : DATASOURCE_MODE_EMBEDDED);
    }
}
