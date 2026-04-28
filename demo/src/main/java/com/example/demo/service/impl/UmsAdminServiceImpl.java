package com.example.demo.service.impl;

import com.example.demo.service.UmsAdminService;
import org.springframework.security.core.userdetails.UserDetails;

public class UmsAdminServiceImpl implements UmsAdminService {

    @Override
    public UserDetails loadUserByUserName(String name) {
        return null;
    }
}
