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

package com.alibaba.nacos.core.cluster;

import com.alibaba.nacos.api.exception.NacosException;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Member node addressing mode.
 *
 * Nacos 寻址机制
 *
 *
 * 《Nacos架构与原理》
 *
 * 无论是单机模式，还是集群模式，其根本区别只是 Nacos 成员节点的个数是单个还是多个，
 * 并且，要能够感知到节点的变更情况：节点是增加了还是减少了；当前最新的成员列表信息是什么；
 * 以何种方式去管理成员列表信息；如何快速的支持新的、更优秀的成员列表管理模式等等。
 * 针对上述需求点，Nacos抽象出了⼀个 MemberLookup 接口
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public interface MemberLookup {
    
    /**
     * start.
     *
     * @throws NacosException NacosException
     */
    void start() throws NacosException;
    
    /**
     * is using address server.
     *
     * @return using address server or not.
     */
    boolean useAddressServer();
    
    /**
     * Inject the ServerMemberManager property.
     *
     * 将 ServerMemberManager 注入到 MemberLookup 中
     *
     * @param memberManager {@link ServerMemberManager}
     */
    void injectMemberManager(ServerMemberManager memberManager);
    
    /**
     * The addressing pattern finds cluster nodes.
     *
     * @param members {@link Collection}
     */
    void afterLookup(Collection<Member> members);
    
    /**
     * Addressing mode closed.
     *
     * @throws NacosException NacosException
     */
    void destroy() throws NacosException;
    
    /**
     * Some data information about the addressing pattern.
     *
     * @return {@link Map}
     */
    default Map<String, Object> info() {
        return Collections.emptyMap();
    }
    
}
