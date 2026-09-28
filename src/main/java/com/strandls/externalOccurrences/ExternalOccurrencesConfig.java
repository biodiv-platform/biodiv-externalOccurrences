package com.strandls.externalOccurrences;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalOccurrencesConfig {

	private static final Properties properties;

	private static final Logger logger = LoggerFactory.getLogger(ExternalOccurrencesConfig.class);

	private ExternalOccurrencesConfig() {
	}

	static {
		properties = new Properties();
		try (InputStream in = Thread.currentThread().getContextClassLoader()
				.getResourceAsStream("config.properties")) {
			if (in == null) {
				logger.error("config.properties not found on classpath");
			} else {
				properties.load(in);
			}
		} catch (IOException e) {
			logger.error(e.getMessage());
		}
	}

	public static Properties getProperties() {
		return properties;
	}

	public static String getProperty(String property) {
		return properties.getProperty(property);
	}

	public static String getProperty(String property, String defaultValue) {
		String value = properties.getProperty(property);
		return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
	}

	public static int getIntProperty(String property, int defaultValue) {
		String value = getProperty(property, null);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			logger.warn("Invalid integer for {}: {}, using default {}", property, value, defaultValue);
			return defaultValue;
		}
	}
}
