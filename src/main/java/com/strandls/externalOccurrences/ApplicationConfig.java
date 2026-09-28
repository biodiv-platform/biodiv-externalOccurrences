package com.strandls.externalOccurrences;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import javax.ws.rs.core.Application;

import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.server.spi.Container;
import org.glassfish.jersey.server.spi.ContainerLifecycleListener;
import org.glassfish.jersey.servlet.ServletContainer;
import org.jvnet.hk2.guice.bridge.api.GuiceBridge;
import org.jvnet.hk2.guice.bridge.api.GuiceIntoHK2Bridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Injector;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiModel;
import io.swagger.jaxrs.config.BeanConfig;

public class ApplicationConfig extends Application {

	private static final Logger logger = LoggerFactory.getLogger(ApplicationConfig.class);

	public ApplicationConfig() {
		Properties properties = ExternalOccurrencesConfig.getProperties();

		BeanConfig beanConfig = new BeanConfig();
		beanConfig.setVersion(properties.getProperty("version"));
		beanConfig.setTitle(properties.getProperty("title"));
		beanConfig.setSchemes(properties.getProperty("schemes").split(","));
		beanConfig.setHost(properties.getProperty("host"));
		beanConfig.setBasePath(properties.getProperty("basePath"));
		beanConfig.setResourcePackage(properties.getProperty("resourcePackage"));
		beanConfig.setPrettyPrint(Boolean.parseBoolean(properties.getProperty("prettyPrint")));
		beanConfig.setScan(Boolean.parseBoolean(properties.getProperty("scan")));
	}

	@Override
	public Set<Object> getSingletons() {

		Set<Object> singletons = new HashSet<>();
		singletons.add(new ContainerLifecycleListener() {

			@Override
			public void onStartup(Container container) {
				ServletContainer servletContainer = (ServletContainer) container;
				ServiceLocator serviceLocator = container.getApplicationHandler().getInjectionManager()
						.getInstance(ServiceLocator.class);
				GuiceBridge.getGuiceBridge().initializeGuiceBridge(serviceLocator);
				GuiceIntoHK2Bridge guiceBridge = serviceLocator.getService(GuiceIntoHK2Bridge.class);
				Injector injector = (Injector) servletContainer.getServletContext()
						.getAttribute(Injector.class.getName());
				guiceBridge.bridgeGuiceInjector(injector);
			}

			@Override
			public void onShutdown(Container container) {
				/*
				 *
				 */
			}

			@Override
			public void onReload(Container container) {
				/*
				 *
				 */
			}
		});

		return singletons;
	}

	@Override
	public Set<Class<?>> getClasses() {
		Set<Class<?>> resource = new HashSet<>();

		try {
			List<Class<?>> swaggerClass = getSwaggerAnnotationClassesFromPackage("com");
			resource.addAll(swaggerClass);
		} catch (ClassNotFoundException | URISyntaxException | IOException e) {
			logger.error(e.getMessage());
		}

		resource.add(io.swagger.jaxrs.listing.SwaggerSerializers.class);
		resource.add(io.swagger.jaxrs.listing.ApiListingResource.class);


		return resource;
	}

	protected List<Class<?>> getSwaggerAnnotationClassesFromPackage(String packageName)
			throws URISyntaxException, IOException, ClassNotFoundException {

		List<String> classNames = getClassNamesFromPackage(packageName);
		List<Class<?>> classes = new ArrayList<>();
		for (String className : classNames) {
			Class<?> cls = Class.forName(className);
			Annotation[] annotations = cls.getAnnotations();

			for (Annotation annotation : annotations) {
				if (annotation instanceof Api || annotation instanceof ApiModel) {
					classes.add(cls);
				}
			}
		}

		return classes;
	}

	private static ArrayList<String> getClassNamesFromPackage(final String packageName)
			throws URISyntaxException, IOException {

		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		ArrayList<String> names = new ArrayList<>();
		URL packageURL = classLoader.getResource(packageName);

		URI uri = new URI(packageURL.toString());
		File folder = new File(uri.getPath());

		try (Stream<Path> files = Files.find(Paths.get(folder.getAbsolutePath()), 999,
				(p, bfa) -> bfa.isRegularFile())) {
			files.forEach(file -> {
				String name = file.toFile().getAbsolutePath()
						.replaceAll(folder.getAbsolutePath() + File.separatorChar, "").replace(File.separatorChar, '.');
				if (name.indexOf('.') != -1) {
					name = packageName + '.' + name.substring(0, name.lastIndexOf('.'));
					names.add(name);
				}
			});
		}

		return names;
	}
}
