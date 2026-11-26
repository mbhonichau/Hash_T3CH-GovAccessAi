package za.gov.govaccess.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class WebSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        return http
                .addFilterBefore(new ManagedAccountFilter(users), AuthorizationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/login", "/vendor/mazer/**").permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/admin/ai/**").hasAnyAuthority("AI_ADMIN", "ROLE_AI_ADMIN")
                        .requestMatchers("/api/assessments", "/api/assessments/**")
                        .hasAnyAuthority("ASSESSMENT_RUN", "ROLE_ASSESSMENT_RUN")
                        .requestMatchers("/admin/users/**").hasAnyAuthority("USER_MANAGE", "ROLE_USER_MANAGE")
                        .requestMatchers("/audit").hasAnyAuthority("AUDIT_VERIFY", "ROLE_AUDIT_VERIFY")
                        .requestMatchers("/audit/history").hasAnyAuthority("AUDIT_VIEW_ALL", "ROLE_AUDIT_VIEW_ALL",
                                "AUDIT_VIEW_LIMITED", "ROLE_AUDIT_VIEW_LIMITED")
                        .requestMatchers(HttpMethod.POST, "/policies/lifecycle/*/test")
                        .hasAnyAuthority("POLICY_TEST", "ROLE_POLICY_TEST")
                        .requestMatchers(HttpMethod.POST, "/policies/lifecycle/*/publish",
                                "/policies/lifecycle/*/transition").hasAnyAuthority("POLICY_PUBLISH", "ROLE_POLICY_PUBLISH")
                        .requestMatchers(HttpMethod.POST, "/api/policies/interpret",
                                "/policies/nsfas-2025/edit", "/policies/nsfas-2025/load", "/rules/catalogue/**",
                                "/policies/imports", "/policies/imports/*/edit", "/policies/lifecycle/*/review",
                                "/policies/lifecycle/*/fork", "/policies/lifecycle/*/edit")
                        .access((authentication, context) -> new AuthorizationDecision(
                                PolicyWorkspaceAccess.isEditing(authentication.get(), context.getRequest())))
                        .requestMatchers("/policies/lifecycle/**").hasAnyAuthority("POLICY_EDIT", "ROLE_POLICY_EDIT",
                                "POLICY_TEST", "ROLE_POLICY_TEST", "POLICY_PUBLISH", "ROLE_POLICY_PUBLISH")
                        .requestMatchers("/policies/**", "/api/policies/**", "/rules/**", "/workspace/**")
                        .access((authentication, context) -> {
                            var actor = authentication.get();
                            return new AuthorizationDecision(actor.isAuthenticated()
                                    && !(actor instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                                    && (!(actor.getPrincipal() instanceof ManagedPrincipal)
                                        || ActorIdentity.has(actor, "POLICY_VIEW")));
                        })
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage("/login").permitAll().defaultSuccessUrl("/", true))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"))
                .build();
    }
}
