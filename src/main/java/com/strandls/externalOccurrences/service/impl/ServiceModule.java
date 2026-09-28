package com.strandls.externalOccurrences.service.impl;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.strandls.externalOccurrences.service.GBIFObservationService;

public class ServiceModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(GBIFObservationService.class).to(GBIFObservationServiceImpl.class).in(Scopes.SINGLETON);
	}
}
