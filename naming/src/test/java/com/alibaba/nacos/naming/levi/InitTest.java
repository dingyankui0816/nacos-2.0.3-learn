package com.alibaba.nacos.naming.levi;

import com.alibaba.nacos.core.code.ControllerMethodsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @Description 服务初始化验证
 * @Author: Levi.Ding
 * @Date: 2023/6/12 14:06
 * @Version V1.0
 */
public class InitTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitTest.class);

    public static void main(String[] args) {
        ControllerMethodsCache methodsCache = new ControllerMethodsCache();
        methodsCache.initClassMethod("com.alibaba.nacos.core.controller");
        methodsCache.initClassMethod("com.alibaba.nacos.naming.controllers");
        methodsCache.initClassMethod("com.alibaba.nacos.config.server.controller");
        methodsCache.initClassMethod("com.alibaba.nacos.console.controller");
        LOGGER.info("methodsCache init success");
    }
}
