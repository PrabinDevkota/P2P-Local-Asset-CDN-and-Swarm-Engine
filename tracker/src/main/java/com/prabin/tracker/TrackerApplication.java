package com.prabin.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Control-plane entrypoint (future tracker-service).
 * Discovers and ranks peers; never proxies asset bytes; never authorizes release content.
 */
@SpringBootApplication
public class TrackerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TrackerApplication.class, args);
	}

}
