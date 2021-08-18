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

package com.alibaba.nacos.console.controller;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.auth.annotation.Secured;
import com.alibaba.nacos.auth.common.ActionTypes;
import com.alibaba.nacos.auth.common.AuthConfigs;
import com.alibaba.nacos.auth.common.AuthSystemTypes;
import com.alibaba.nacos.auth.exception.AccessException;
import com.alibaba.nacos.common.model.RestResult;
import com.alibaba.nacos.common.model.RestResultUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.Objects;
import com.alibaba.nacos.config.server.auth.RoleInfo;
import com.alibaba.nacos.config.server.model.User;
import com.alibaba.nacos.config.server.utils.RequestUtil;
import com.alibaba.nacos.console.security.nacos.JwtTokenManager;
import com.alibaba.nacos.console.security.nacos.NacosAuthConfig;
import com.alibaba.nacos.console.security.nacos.NacosAuthManager;
import com.alibaba.nacos.console.security.nacos.roles.NacosRoleServiceImpl;
import com.alibaba.nacos.console.security.nacos.users.NacosUser;
import com.alibaba.nacos.console.security.nacos.users.NacosUserDetailsServiceImpl;
import com.alibaba.nacos.console.utils.PasswordEncoderUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User related methods entry.
 *
 * @author wfnuser
 * @author nkorange
 */
@RestController("user")
@RequestMapping({"/v1/auth", "/v1/auth/users"})
public class UserController {
    private Logger logger = LoggerFactory.getLogger(UserController.class);

    private final Map<String, Map<String, String>> errorLoginCnt = new ConcurrentHashMap<>(16);

    private static final int LEAST_LENGTH = 8;

    private static final char[] SPECIAL_CHARS = new char[]{'~', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '-', '_', '=', '+',
            '[', ']', '{', '}', '?'};

    public static final long DELAY_TIMES = 2 * 60 * 1000L;

    public static final int ALLOW_ERROR_TRY_TIMES = 10;

    @Autowired
    private JwtTokenManager jwtTokenManager;
    
    @Autowired
    private AuthenticationManager authenticationManager;
    
    @Autowired
    private NacosUserDetailsServiceImpl userDetailsService;
    
    @Autowired
    private NacosRoleServiceImpl roleService;
    
    @Autowired
    private AuthConfigs authConfigs;
    
    @Autowired
    private NacosAuthManager authManager;
    
    /**
     * Create a new user.
     *
     * @param username username
     * @param password password
     * @return ok if create succeed
     * @throws IllegalArgumentException if user already exist
     * @since 1.2.0
     */
    @Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.WRITE)
    @PostMapping
    public Object createUser(@RequestParam String username, @RequestParam String password) {
        
        User user = userDetailsService.getUserFromDatabase(username);
        if (user != null) {
            throw new IllegalArgumentException("user '" + username + "' already exist!");
        }
        userDetailsService.createUser(username, PasswordEncoderUtil.encode(password));
        return RestResultUtils.success("create user ok!");
    }
    
    /**
     * Delete an existed user.
     *
     * @param username username of user
     * @return ok if deleted succeed, keep silent if user not exist
     * @since 1.2.0
     */
    @DeleteMapping
    @Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.WRITE)
    public Object deleteUser(@RequestParam String username) {
        List<RoleInfo> roleInfoList = roleService.getRoles(username);
        if (roleInfoList != null) {
            for (RoleInfo roleInfo : roleInfoList) {
                if (roleInfo.getRole().equals(NacosRoleServiceImpl.GLOBAL_ADMIN_ROLE)) {
                    throw new IllegalArgumentException("cannot delete admin: " + username);
                }
            }
        }
        userDetailsService.deleteUser(username);
        return RestResultUtils.success("delete user ok!");
    }
    
    /**
     * Update an user.
     *
     * @param username    username of user
     * @param newPassword new password of user
     * @param response http response
     * @param request http request
     * @return ok if update succeed
     * @throws IllegalArgumentException if user not exist or oldPassword is incorrect
     * @since 1.2.0
     */
    @PutMapping
    @Secured(resource = NacosAuthConfig.UPDATE_PASSWORD_ENTRY_POINT, action = ActionTypes.WRITE)
    public Object updateUser(@RequestParam String username, @RequestParam String newPassword,
            HttpServletResponse response, HttpServletRequest request) throws IOException {
        // admin or same user
        if (!hasPermission(username, request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "authorization failed!");
        }

        isAllowedPasswd(newPassword);

        User user = userDetailsService.getUserFromDatabase(username);
        if (user == null) {
            throw new IllegalArgumentException("user " + username + " not exist!");
        }
        
        userDetailsService.updateUserPassword(username, PasswordEncoderUtil.encode(newPassword));
        
        return RestResultUtils.success("update user ok!");
    }

    private void isAllowedPasswd(String password) throws IllegalArgumentException {
        if (password.length() < LEAST_LENGTH) {
            logger.info("new password's length is too short:{}", password);
            throw new IllegalArgumentException("密码长度必须 >= 8");
        }

        boolean hasSpecialChar = false;
        for (char c : password.toCharArray()) {
            if (hasSpecialChar) {
                break;
            }

            for (char sc : SPECIAL_CHARS) {
                if (c == sc) {
                    hasSpecialChar = true;
                    break;
                }
            }
        }

        if (!hasSpecialChar) {
            logger.info("new password don't has special char:{}", password);
            throw new IllegalArgumentException("必须含有特殊字符('~', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '-', '_', "
                    +
                    "'=', '+', '[', ']', '{', '}', '?')");
        }

        boolean hasUppderChar = false;
        boolean hasLowerChar = false;
        boolean hasNumber = false;
        for (char c : password.toCharArray()) {
            if (!hasUppderChar && c == Character.toUpperCase(c) && c >= 'A' && c <= 'Z') {
                hasUppderChar = true;
                continue;
            }

            if (!hasLowerChar && c == Character.toLowerCase(c) && c >= 'a' && c <= 'z') {
                hasLowerChar = true;
                continue;
            }

            if (!hasNumber && c >= '0' && c <= '9') {
                hasNumber = true;
                continue;
            }
        }

        if (!hasUppderChar) {
            logger.info("new password don't has Upper char:{}", password);
            throw new IllegalArgumentException("必须含有大写字母！");
        }

        if (!hasLowerChar) {
            logger.info("new password don't has Lower char:{}", password);
            throw new IllegalArgumentException("必须含有小写字母！");
        }

        if (!hasNumber) {
            logger.info("new password don't has number:{}", password);
            throw new IllegalArgumentException("必须含有数字！");
        }
    }

    private boolean hasPermission(String username, HttpServletRequest request) {
        if (!authConfigs.isAuthEnabled()) {
            return true;
        }
        if (Objects.isNull(request.getAttribute(RequestUtil.NACOS_USER_KEY))) {
            return false;
        }

        NacosUser user = (NacosUser) request.getAttribute(RequestUtil.NACOS_USER_KEY);
        // admin
        if (user.isGlobalAdmin()) {
            return true;
        }
        // same user
        return user.getUserName().equals(username);
    }
    
    /**
     * Get paged users.
     *
     * @param pageNo   number index of page
     * @param pageSize size of page
     * @return A collection of users, empty set if no user is found
     * @since 1.2.0
     */
    @GetMapping
    @Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.READ)
    public Object getUsers(@RequestParam int pageNo, @RequestParam int pageSize) {
        return userDetailsService.getUsersFromDatabase(pageNo, pageSize);
    }
    
    /**
     * Login to Nacos
     *
     * <p>This methods uses username and password to require a new token.
     *
     * @param username username of user
     * @param password password
     * @param response http response
     * @param request  http request
     * @return new token of the user
     * @throws AccessException if user info is incorrect
     */
    @PostMapping("/login")
    public Object login(@RequestParam String username, @RequestParam String password, HttpServletResponse response,
            HttpServletRequest request) throws AccessException {

        Map<String, String> currMap = errorLoginCnt.get(username);
        long currTime = System.currentTimeMillis();
        if (null == currMap) {
            currMap = Maps.newHashMap();
            currMap.put("errorCnt", "0");
            currMap.put("lastTryTime", String.valueOf(currTime));
            currMap.put("nextAllowTryTime", "-1");
            errorLoginCnt.put(username, currMap);
        }

        long lastTryTime = Long.parseLong(currMap.get("lastTryTime"));
        if (lastTryTime + DELAY_TIMES <= currTime) {
            currMap.put("errorCnt", "0");
            currMap.put("lastTryTime", String.valueOf(currTime));
            currMap.put("nextAllowTryTime", "-1");
            errorLoginCnt.put(username, currMap);
        }

        long nextAllowTryTime = Long.parseLong(currMap.get("nextAllowTryTime"));
        if (nextAllowTryTime > currTime) {
            logger.info("forbid to try login now:{}", username);
            throw new AccessException("试错次数太多，2分钟内禁止再次登录！");
        }

        logger.error("currMap: {}", currMap);

        try {
            if (AuthSystemTypes.NACOS.name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType()) || AuthSystemTypes.LDAP
                    .name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType())) {
                NacosUser user = (NacosUser) authManager.login(request);

                response.addHeader(NacosAuthConfig.AUTHORIZATION_HEADER, NacosAuthConfig.TOKEN_PREFIX + user.getToken());

                ObjectNode result = JacksonUtils.createEmptyJsonNode();
                result.put(Constants.ACCESS_TOKEN, user.getToken());
                result.put(Constants.TOKEN_TTL, authConfigs.getTokenValidityInSeconds());
                result.put(Constants.GLOBAL_ADMIN, user.isGlobalAdmin());
                result.put(Constants.USERNAME, user.getUserName());

                // login success
                currMap.put("errorCnt", "0");
                currMap.put("lastTryTime", String.valueOf(currTime));
                currMap.put("nextAllowTryTime", "-1");
                errorLoginCnt.put(username, currMap);

                logger.error("currMap: {}", currMap);
                return result;
            }
        } catch (Exception e) {
            Map<String, String> tmpMap = errorLoginCnt.get(username);
            int errorCode = Integer.parseInt(tmpMap.get("errorCnt"));
            if (errorCode < ALLOW_ERROR_TRY_TIMES) {
                tmpMap.put("errorCnt", String.valueOf(++errorCode));
            } else {
                tmpMap.put("nextAllowTryTime", String.valueOf(System.currentTimeMillis() + DELAY_TIMES));
                errorLoginCnt.put(username, tmpMap);
            }
            tmpMap.put("lastTryTime", String.valueOf(System.currentTimeMillis()));
            errorLoginCnt.put(username, tmpMap);

            logger.error("currMap: {}", currMap);
            throw e;
        }

        // create Authentication class through username and password, the implement class is UsernamePasswordAuthenticationToken
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username,
                password);
        
        try {
            // use the method authenticate of AuthenticationManager(default implement is ProviderManager) to valid Authentication
            Authentication authentication = authenticationManager.authenticate(authenticationToken);
            // bind SecurityContext to Authentication
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // generate Token
            String token = jwtTokenManager.createToken(authentication);
            // write Token to Http header
            response.addHeader(NacosAuthConfig.AUTHORIZATION_HEADER, "Bearer " + token);

            // login success
            currMap.put("errorCnt", "0");
            currMap.put("lastTryTime", String.valueOf(currTime));
            currMap.put("nextAllowTryTime", "-1");
            errorLoginCnt.put(username, currMap);

            logger.error("currMap: {}", currMap);
            return RestResultUtils.success("Bearer " + token);
        } catch (BadCredentialsException authentication) {
            Map<String, String> tmpMap = errorLoginCnt.get(username);
            int errorCode = Integer.parseInt(tmpMap.get("errorCnt"));
            if (errorCode < ALLOW_ERROR_TRY_TIMES) {
                tmpMap.put("errorCnt", String.valueOf(++errorCode));
            } else {
                tmpMap.put("nextAllowTryTime", String.valueOf(System.currentTimeMillis() + DELAY_TIMES));
                errorLoginCnt.put(username, tmpMap);
            }
            tmpMap.put("lastTryTime", String.valueOf(System.currentTimeMillis()));
            errorLoginCnt.put(username, tmpMap);

            logger.error("currMap: {}", currMap);
            return RestResultUtils.failed(HttpStatus.UNAUTHORIZED.value(), null, "Login failed");
        }
    }
    
    /**
     * Update password.
     *
     * @param oldPassword old password
     * @param newPassword new password
     * @return Code 200 if update successfully, Code 401 if old password invalid, otherwise 500
     */
    @PutMapping("/password")
    @Deprecated
    public RestResult<String> updatePassword(@RequestParam(value = "oldPassword") String oldPassword,
            @RequestParam(value = "newPassword") String newPassword) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String username = ((UserDetails) principal).getUsername();
        User user = userDetailsService.getUserFromDatabase(username);
        String password = user.getPassword();
        
        // TODO: throw out more fine grained exceptions
        try {
            if (PasswordEncoderUtil.matches(oldPassword, password)) {
                userDetailsService.updateUserPassword(username, PasswordEncoderUtil.encode(newPassword));
                return RestResultUtils.success("Update password success");
            }
            return RestResultUtils.failed(HttpStatus.UNAUTHORIZED.value(), "Old password is invalid");
        } catch (Exception e) {
            return RestResultUtils.failed(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Update userpassword failed");
        }
    }


    /**
     * Fuzzy matching username.
     *
     * @param username username
     * @return Matched username
     */
    @GetMapping("/search")
    @Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.WRITE)
    public List<String> searchUsersLikeUsername(@RequestParam String username) {
        return userDetailsService.findUserLikeUsername(username);
    }
}
