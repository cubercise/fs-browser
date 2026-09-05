package io.cubercise.fsbrowser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * fs-browser: browses one configured directory (the Root) over HTTP.
 *
 * <p>Extends {@link SpringBootServletInitializer} so the WAR deploys to an external
 * Tomcat 10.1 while remaining runnable standalone via {@code java -jar}.
 */
@SpringBootApplication
public class FsBrowserApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(FsBrowserApplication.class, args);
    }
}
