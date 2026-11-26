package com.hashtech.govaccessai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.hashtech.govaccessai", "za.gov.govaccess"})
@EntityScan("za.gov.govaccess")
@EnableJpaRepositories("za.gov.govaccess")
public class GovaccessaiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GovaccessaiApplication.class, args);
	}

}
