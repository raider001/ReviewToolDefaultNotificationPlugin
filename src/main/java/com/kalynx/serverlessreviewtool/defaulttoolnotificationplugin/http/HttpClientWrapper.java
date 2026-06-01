package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http;

import java.net.http.HttpClient;

/**
 * Concrete wrapper around {@link HttpClient} so it can be registered with the
 * LWDI dependency injector by its own class type.
 *
 * <p>{@link HttpClient} is abstract; the injector cannot resolve it from the registry
 * when it is added as a bare instance. Wrapping it in a concrete class lets
 * {@code DependencyInjector.add(new HttpClientWrapper())} register it under
 * {@code HttpClientWrapper.class}, which the injector can then resolve normally.
 */
public class HttpClientWrapper implements HttpClientW {

    private final HttpClient client;

    public HttpClientWrapper(HttpClient client) {
        this.client = client;
    }

    public HttpClient get() {
        return client;
    }
}