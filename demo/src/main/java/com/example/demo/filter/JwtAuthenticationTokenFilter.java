package com.example.demo.filter;

import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.service.UmsAdminService;
import com.example.demo.utils.JwtTokenUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {
    @Autowired
    private JwtTokenUtil jwtTokenUtil;
    @Autowired
    private UmsAdminService umsAdminService;
    @Value("${jwt.tokenHead}")
    private String tokenHead;
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if(authHeader==null||!authHeader.startsWith(tokenHead.trim())){
            chain.doFilter(request,response);
            return;
        }
        String token = authHeader.substring(tokenHead.trim().length());
        String userName = jwtTokenUtil.getUserNameFromToken(token);
        if(userName==null|| SecurityContextHolder.getContext().getAuthentication()!=null){
            chain.doFilter(request,response);
            return;
        }
        AdminUserDetails userDetails = umsAdminService.loadUserByUserName(userName);
        if(jwtTokenUtil.validateToken(token,userDetails)){
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            //authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request,response);

    }
}
