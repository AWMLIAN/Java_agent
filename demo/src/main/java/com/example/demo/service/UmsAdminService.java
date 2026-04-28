package com.example.demo.service;

import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.model.entity.UmsAdmin;
import org.springframework.security.core.userdetails.UserDetails;


public interface UmsAdminService {
    /**
     * 加载后台用户
     */
    public UmsAdmin getUserByUserName(String name);
    /**
     * springSecurity 用户
     */
    public AdminUserDetails loadUserByUserName(String name);
}
