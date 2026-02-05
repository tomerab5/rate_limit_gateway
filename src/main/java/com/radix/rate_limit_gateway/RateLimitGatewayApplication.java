package com.radix.rate_limit_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;




@SpringBootApplication
public class RateLimitGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(RateLimitGatewayApplication.class, args);   //app.listen(8081) in Express
	}

}
