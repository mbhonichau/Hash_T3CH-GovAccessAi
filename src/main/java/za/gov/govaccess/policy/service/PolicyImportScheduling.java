package za.gov.govaccess.policy.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "govaccess.policy-import", name = "worker-enabled", matchIfMissing = true)
class PolicyImportScheduling {}
