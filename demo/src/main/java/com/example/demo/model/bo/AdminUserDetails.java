package com.example.demo.model.bo;

import com.example.demo.model.entity.UmsAdmin;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Data
public class AdminUserDetails implements UserDetails {
    //后台用户
    private final UmsAdmin umsAdmin;

    public AdminUserDetails(UmsAdmin umsAdmin, UmsAdmin umsAdmin1){
        this.umsAdmin = umsAdmin1;
    }
    //暂时为空后面填权限
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    @Override
    public String getPassword() {
        return umsAdmin.getPassword();
    }

    @Override
    public String getUsername() {
        return umsAdmin.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return umsAdmin.getStatus().equals(1);
    }
}
