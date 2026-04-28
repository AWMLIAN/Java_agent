package com.example.demo.service;

import org.springframework.security.core.userdetails.UserDetails;

public interface UmsAdminService {
    /**
     * 加载用户
     */
    public UserDetails loadUserByUserName(String name);
}
