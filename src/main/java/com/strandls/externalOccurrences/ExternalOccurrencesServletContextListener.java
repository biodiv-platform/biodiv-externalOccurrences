package com.strandls.externalOccurrences;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Scopes;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.servlet.ServletModule;
import com.strandls.externalOccurrences.controller.ControllerModule;
import com.strandls.externalOccurrences.service.impl.ServiceModule;
import com.strandls.externalOccurrences.util.DuckDBUtil;

import jakarta.servlet.ServletContextEvent;

public class ExternalOccurrencesServletContextListener extends GuiceServletContextListener {

	private static final Logger logger = LoggerFactory.getLogger(ExternalOccurrencesServletContextListener.class);

	@Override
	protected Injector getInjector() {

		return Guice.createInjector(new ServletModule() {
			@Override
			protected void configureServlets() {

				bind(ObjectMapper.class).toInstance(new ObjectMapper());

				Map<String, String> props = new HashMap<>();
				props.put("jakarta.ws.rs.Application", ApplicationConfig.class.getName());
				props.put("jersey.config.server.provider.packages", "com");
				props.put("jersey.config.server.wadl.disableWadl", "true");

				bind(ServletContainer.class).in(Scopes.SINGLETON);

				serve("/api/*").with(ServletContainer.class, props);
			}
		}, new ControllerModule(), new ServiceModule());
	}

	@Override
	public void contextDestroyed(ServletContextEvent servletContextEvent) {
		logger.info("Application shutting down...");
		DuckDBUtil.shutdown();

		super.contextDestroyed(servletContextEvent);

		// Deregister JDBC drivers (DuckDB) loaded by this webapp's ClassLoader so a
		// redeploy does not leak it
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		Enumeration<Driver> drivers = DriverManager.getDrivers();
		while (drivers.hasMoreElements()) {
			Driver driver = drivers.nextElement();
			if (driver.getClass().getClassLoader() == cl) {
				try {
					logger.info("Deregistering JDBC driver {}", driver);
					DriverManager.deregisterDriver(driver);
				} catch (SQLException ex) {
					logger.error("Error deregistering JDBC driver {}", driver, ex);
				}
			} else {
				logger.trace("Not deregistering JDBC driver {} as it does not belong to this webapp's ClassLoader",
						driver);
			}
		}

		logger.info("Application shutdown complete");
	}
}
