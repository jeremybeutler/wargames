package org.wargames;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The page itself must never be cached: it names the current build's hashed script and
 * style files, so a stale copy keeps running the previous version of the game after a
 * deploy. (The jar gives every file the same 1980 timestamp, so browsers would otherwise
 * treat it as unchanged indefinitely.) "/" is served by forwarding to index.html, so this
 * covers both. Other static files use the defaults in application.properties.
 */
@Configuration
public class PageCacheHeaders implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html")
            .addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.noStore());
    }
}
