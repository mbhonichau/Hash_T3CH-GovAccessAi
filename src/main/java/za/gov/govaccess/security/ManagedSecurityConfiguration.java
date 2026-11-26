package za.gov.govaccess.security;

import java.util.logging.Logger;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class ManagedSecurityConfiguration {
    @Bean
    PasswordEncoder govAccessPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService govAccessUsers(SecurityProperties properties, ManagedUserService users, PasswordEncoder encoder) {
        var bootstrap = properties.getUser();
        if (bootstrap.isPasswordGenerated()) {
            Logger.getLogger(ManagedSecurityConfiguration.class.getName())
                    .info("Using generated security password: " + bootstrap.getPassword());
        }
        String password = bootstrap.getPassword();
        String hash = password.startsWith("{bcrypt}") ? password.substring("{bcrypt}".length())
                : password.startsWith("$2") ? password : encoder.encode(password);
        var fallback = User.withUsername(bootstrap.getName()).password(hash)
                .roles(bootstrap.getRoles().toArray(String[]::new)).build();
        return username -> {
            if (fallback.getUsername().equals(username)) {
                return User.withUserDetails(fallback).build();
            }
            return users.principal(username).orElseThrow(() ->
                    new org.springframework.security.core.userdetails.UsernameNotFoundException("Account not found"));
        };
    }
}
