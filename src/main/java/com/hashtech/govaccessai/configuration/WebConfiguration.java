package com.hashtech.govaccessai.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("auth/login");
    }
}

@ControllerAdvice
class SharedViewModel {
    @ModelAttribute("currentPath")
    String currentPath(HttpServletRequest request) { return request.getRequestURI(); }

    @ModelAttribute("username")
    String username(Authentication authentication) {
        return authentication == null || !authentication.isAuthenticated()
                || authentication.getName().equals("anonymousUser") ? "User" : authentication.getName();
    }
}
