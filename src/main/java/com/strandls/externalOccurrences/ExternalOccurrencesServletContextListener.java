package com.strandls.externalOccurrences;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContextEvent;

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

public class ExternalOccurrencesServletContextListener extends GuiceServletContextListener {

	private static final Logger logger = LoggerFactory.getLogger(ExternalOccurrencesServletContextListener.class);

	@Override
	protected Injector getInjector() {

		return Guice.createInjector(new ServletModule() {
			@Override
			protected void configureServlets() {

				bind(ObjectMapper.class).toInstance(new ObjectMapper());

				Map<String, String> props = new HashMap<>();
				props.put("javax.ws.rs.Application", ApplicationConfig.class.getName());
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
		logger.info("Application shutdown complete");
	}
}
