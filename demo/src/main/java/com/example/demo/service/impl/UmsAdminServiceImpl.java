package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.mapper.UmsAdminMapper;
import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.model.entity.UmsAdmin;
import com.example.demo.service.UmsAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.attribute.UserPrincipalNotFoundException;

@Service
public class UmsAdminServiceImpl implements UmsAdminService {

    @Autowired
    private UmsAdminMapper umsAdminMapper;

    @Override
    public UmsAdmin getUserByUserName(String name) {
        LambdaQueryWrapper<UmsAdmin> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UmsAdmin::getUsername,name);
        return umsAdminMapper.selectOne(wrapper);

    }

    @Override
    public AdminUserDetails loadUserByUserName(String name) {
        UmsAdmin umsAdmin = getUserByUserName(name);
        if(umsAdmin!=null){
            return new AdminUserDetails(umsAdmin);
        }
        return null;
    }
}
