package com.example.whatsapp.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    private final AppProperties appProperties;

    public RestTemplateConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    public RestTemplate restTemplate() {
        log.info("Creating pooled RestTemplate with connectTimeout={}ms, readTimeout={}ms",
                appProperties.getRest().getConnectTimeout(),
                appProperties.getRest().getReadTimeout());

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(appProperties.getRest().getMaxConnTotal());
        connectionManager.setDefaultMaxPerRoute(appProperties.getRest().getMaxConnPerRoute());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(appProperties.getRest().getConnectTimeout()))
                .setResponseTimeout(Timeout.ofMilliseconds(appProperties.getRest().getReadTimeout()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return new RestTemplate(requestFactory);
    }
}
