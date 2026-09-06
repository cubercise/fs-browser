package io.cubercise.fsbrowser.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA routing: the client router owns `/` and `/browse` (with `?path=`), so
 * those URLs must serve the app shell rather than 404 — that is what makes
 * deep links and refreshes work when the WAR serves the bundled frontend.
 * API routes are untouched.
 */
@Configuration(proxyBeanMethods = false)
public class SpaRoutingConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/browse").setViewName("forward:/index.html");
    }
}
