package com.alibaba.nacos.core;

import com.alibaba.nacos.core.listener.StandaloneProfileApplicationListenerTest;
import com.alibaba.nacos.sys.env.EnvUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * @Description todo
 * @Author: Levi.Ding
 * @Date: 2023/6/26 14:51
 * @Version V1.0
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = NacosSpringApplicationListenerTest.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class NacosSpringApplicationListenerTest {


    private static final Log logger = LogFactory.getLog(NacosSpringApplicationListenerTest.class);

    /**
     * @Description: 测试SpringApplicationListener解析逻辑
     * @author Levi.Ding
     * @date 2023/6/26 14:53
     * @return : void
     */
    @Test
    public void loadFactoryNamesTest(){
        List<String> names = SpringFactoriesLoader.loadFactoryNames(SpringApplicationRunListener.class, ClassUtils.getDefaultClassLoader());

        names.forEach(n -> logger.info(n));

    }

    /**
     * @Description:
     *      加载 conf/application.properties 文件配置
     *      使用
     * @author Levi.Ding
     * @date 2023/6/27 10:21
     * @return : void
     */
    @Test
    public void loadConfApplication() throws IOException {
        Map<String, ?> map = EnvUtil.loadProperties(EnvUtil.getApplicationConfFileResource());

        map.forEach((k,v)-> logger.info("key: "+k+"value : "+v));
    }
}
