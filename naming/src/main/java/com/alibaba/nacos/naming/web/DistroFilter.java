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

package com.alibaba.nacos.naming.web;

import com.alibaba.nacos.common.constant.HttpHeaderConsts;
import com.alibaba.nacos.common.model.RestResult;
import com.alibaba.nacos.common.utils.ExceptionUtil;
import com.alibaba.nacos.common.utils.IoUtils;
import com.alibaba.nacos.core.code.ControllerMethodsCache;
import com.alibaba.nacos.core.utils.ReuseHttpServletRequest;
import com.alibaba.nacos.core.utils.WebUtils;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.misc.HttpClient;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import org.apache.commons.codec.Charsets;
import com.alibaba.nacos.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Distro filter.
 *
 * Distro 请求拦截算法
 *
 * 《Nacos架构与原理》
 *
 * 前置的 Filter 拦截请求，并根据请求中包含的 IP 和 port 信息计算其所属的Distro 责任节点，并将该请求转发到所属的 Distro 责任节点上。
 *
 * @author nacos
 */
public class DistroFilter implements Filter {
    
    private static final int PROXY_CONNECT_TIMEOUT = 2000;
    
    private static final int PROXY_READ_TIMEOUT = 2000;
    
    @Autowired
    private DistroMapper distroMapper;
    
    @Autowired
    private ControllerMethodsCache controllerMethodsCache;
    
    @Autowired
    private DistroTagGenerator distroTagGenerator;
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }
    
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        ReuseHttpServletRequest req = new ReuseHttpServletRequest((HttpServletRequest) servletRequest);
        HttpServletResponse resp = (HttpServletResponse) servletResponse;
        
        String urlString = req.getRequestURI();
        
        if (StringUtils.isNotBlank(req.getQueryString())) {
            urlString += "?" + req.getQueryString();
        }
        
        try {
            Method method = controllerMethodsCache.getMethod(req);
            
            String path = new URI(req.getRequestURI()).getPath();
            if (method == null) {
                throw new NoSuchMethodException(req.getMethod() + " " + path);
            }
            
            if (!method.isAnnotationPresent(CanDistro.class)) {
                filterChain.doFilter(req, resp);
                return;
            }
            String distroTag = distroTagGenerator.getResponsibleTag(req);

            //当前节点是否负责该请求
            if (distroMapper.responsible(distroTag)) {
                filterChain.doFilter(req, resp);
                return;
            }
            
            // 判断当前请求是否从 其他 nacos 节点发出。如果是 则直接响应报错，否则转发请求
            // 这里用来校验 转发请求只会产生一次，如果转发到对应的节点 当前节点依旧不负责当前请求，那么直接响应 400
            String userAgent = req.getHeader(HttpHeaderConsts.USER_AGENT_HEADER);
            
            if (StringUtils.isNotBlank(userAgent) && userAgent.contains(UtilsAndCommons.NACOS_SERVER_HEADER)) {
                // This request is sent from peer server, should not be redirected again:
                Loggers.SRV_LOG.error("receive invalid redirect request from peer {}", req.getRemoteAddr());
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "receive invalid redirect request from peer " + req.getRemoteAddr());
                return;
            }

            //选择集群中其他健康节点，用于转发请求
            final String targetServer = distroMapper.mapSrv(distroTag);
            
            List<String> headerList = new ArrayList<>(16);
            Enumeration<String> headers = req.getHeaderNames();
            while (headers.hasMoreElements()) {
                String headerName = headers.nextElement();
                headerList.add(headerName);
                headerList.add(req.getHeader(headerName));
            }
            
            final String body = IoUtils.toString(req.getInputStream(), Charsets.UTF_8.name());
            final Map<String, String> paramsValue = HttpClient.translateParameterMap(req.getParameterMap());
            //包装转发信息，调用转发
            RestResult<String> result = HttpClient
                    .request("http://" + targetServer + req.getRequestURI(), headerList, paramsValue, body,
                            PROXY_CONNECT_TIMEOUT, PROXY_READ_TIMEOUT, Charsets.UTF_8.name(), req.getMethod());
            String data = result.ok() ? result.getData() : result.getMessage();
            try {
                //将响应返回至客户端
                WebUtils.response(resp, data, result.getCode());
            } catch (Exception ignore) {
                Loggers.SRV_LOG.warn("[DISTRO-FILTER] request failed: " + distroMapper.mapSrv(distroTag) + urlString);
            }
        } catch (AccessControlException e) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "access denied: " + ExceptionUtil.getAllExceptionMsg(e));
        } catch (NoSuchMethodException e) {
            resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,
                    "no such api:" + req.getMethod() + ":" + req.getRequestURI());
        } catch (Exception e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Server failed," + ExceptionUtil.getAllExceptionMsg(e));
        }
        
    }
    
    @Override
    public void destroy() {
    
    }
}
