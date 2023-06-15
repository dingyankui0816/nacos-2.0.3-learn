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

package com.alibaba.nacos.core.cluster.lookup;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.core.cluster.AbstractMemberLookup;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.MemberUtil;
import com.alibaba.nacos.sys.env.EnvUtil;
import com.alibaba.nacos.sys.file.FileChangeEvent;
import com.alibaba.nacos.sys.file.FileWatcher;
import com.alibaba.nacos.sys.file.WatchFileCenter;
import com.alibaba.nacos.core.utils.Loggers;
import com.alibaba.nacos.common.utils.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Cluster.conf file managed cluster member node addressing pattern.
 *
 * 集群模式下默认的寻址机制，文件寻址
 * 从 nacos/conf/cluster.conf 获取集群节点信息
 *
 *
 * 集群模式下多个nacos 就会有多个 cluster.conf 文件 —————— 对于运维成本较大
 *
 * 《Nacos架构与原理》
 *
 * 这种默认寻址模式有⼀个缺点——运维成本较大，可以想象下，当你新增⼀个Nacos 节点时，需要去手动修改每个 Nacos 节点下的 cluster.conf 文件，
 * 这是多么辛苦的⼀件工作，或者稍微高端⼀点，利用 ansible 等自动化部署的工具去推送 cluster.conf 文件去代替自己的手动操作，虽然说省去了较为繁琐的人工操作步骤
 * ，但是仍旧存在⼀个问题——每⼀个Nacos 节点都存在⼀份cluster.conf 文件，如果其中⼀个节点的 cluster.conf 文件修改失败，就造成了集群间成员节点列表数据的不⼀致性.
 *
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class FileConfigMemberLookup extends AbstractMemberLookup {
    
    private static final String DEFAULT_SEARCH_SEQ = "cluster.conf";

    //监听器
    private FileWatcher watcher = new FileWatcher() {
        @Override
        //发生事件执行 readClusterConfFromDisk
        public void onChange(FileChangeEvent event) {
            readClusterConfFromDisk();
        }
        
        @Override
        //判断当前变更事件是否属于该监听器
        public boolean interest(String context) {
            return StringUtils.contains(context, DEFAULT_SEARCH_SEQ);
        }
    };

    @Override
    public void doStart() throws NacosException {
        //读取cluster.conf文件中配置的集群信息
        //将集群信息添加至ServerMemberManager中
        readClusterConfFromDisk();
        
        // Use the inotify mechanism to monitor file changes and automatically
        // trigger the reading of cluster.conf
        //监听 nacos/conf/ 文件夹中的文件变动事件。用于对集群的自动扩缩容
        try {
            WatchFileCenter.registerWatcher(EnvUtil.getConfPath(), watcher);
        } catch (Throwable e) {
            Loggers.CLUSTER.error("An exception occurred in the launch file monitor : {}", e.getMessage());
        }
    }
    
    @Override
    public boolean useAddressServer() {
        return false;
    }
    
    @Override
    public void destroy() throws NacosException {
        //取消对该路径的监听
        WatchFileCenter.deregisterWatcher(EnvUtil.getConfPath(), watcher);
    }
    
    private void readClusterConfFromDisk() {
        Collection<Member> tmpMembers = new ArrayList<>();
        try {
            List<String> tmp = EnvUtil.readClusterConf();
            tmpMembers = MemberUtil.readServerConf(tmp);
        } catch (Throwable e) {
            Loggers.CLUSTER
                    .error("nacos-XXXX [serverlist] failed to get serverlist from disk!, error : {}", e.getMessage());
        }
        
        afterLookup(tmpMembers);
    }
}
