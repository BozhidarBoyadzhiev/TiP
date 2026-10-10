package dev.tip.shared;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Font files, icons and the link preview image never change, so browsers keep them for 30 days. CSS (fonts.css included) and JS change
 * between deploys and their names are not versioned, so they are {@code no-cache}: a browser may store
 * them but revalidates with {@code Last-Modified} and gets a 304 when nothing changed. Setting the header
 * here keeps Spring Security from writing its {@code no-store} default. HTML pages stay {@code no-store},
 * set by their controllers, because each carries its request's nonce.
 */
@Configuration(proxyBeanMethods = false)
class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/fonts/*/*.woff2")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        // Icons and the preview image never change either: a new version gets a new file name.
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        registry.addResourceHandler("/icons/**")
                .addResourceLocations("classpath:/static/icons/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        registry.addResourceHandler("/fonts/*.css")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/")
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(CacheControl.noCache());
    }
}
