package za.gov.govaccess.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class WebSecurityConfiguration {
    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/vendor/mazer/**").permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/api/admin/ai/**").hasAnyAuthority("AI_ADMIN", "ROLE_AI_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/policies/interpret",
                                "/policies/nsfas-2025/edit", "/policies/nsfas-2025/load", "/rules/catalogue/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                PolicyWorkspaceAccess.isEditing(authentication.get(), context.getRequest())))
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage("/login").permitAll().defaultSuccessUrl("/", true))
                .build();
    }
}
