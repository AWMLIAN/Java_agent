package com.example.demo.controller;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.mapper.UmsAdminMapper;
import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.model.dto.LoginRequest;
import com.example.demo.model.entity.UmsAdmin;
import com.example.demo.service.impl.UmsAdminServiceImpl;
import com.example.demo.utils.JwtTokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private UmsAdminMapper umsAdminMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UmsAdminServiceImpl umsAdminService;
    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @PostMapping("/login")
    public String login(@RequestBody LoginRequest request){
        LambdaQueryWrapper<UmsAdmin> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(UmsAdmin::getUsername,request.getUsername());
        UmsAdmin admin = umsAdminMapper.selectOne(wrapper);
        if(admin==null){
            throw new RuntimeException("用户不存在");
        }
        if(!passwordEncoder.matches(request.getPassword(),admin.getPassword())){
            throw new RuntimeException("密码不正确");
        }
        AdminUserDetails userDetails = umsAdminService.loadUserByUserName(request.getUsername());
        if(!userDetails.isEnabled()){
            throw new RuntimeException("用户被禁用");
        }
        return jwtTokenUtil.generateToken(userDetails);
    }

}
