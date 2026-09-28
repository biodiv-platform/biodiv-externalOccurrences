package com.strandls.externalOccurrences.controller;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

public class ControllerModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(GBIFObservationController.class).in(Scopes.SINGLETON);
	}
}
