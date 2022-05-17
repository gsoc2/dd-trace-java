package test.boot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import org.springframework.web.util.UrlPathHelper

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(scanBasePackages = "doesnotexist")
class AppConfig extends WebMvcConfigurerAdapter {

  @Override
  void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.urlPathHelper = new UrlPathHelper(
      removeSemicolonContent: false
      )
  }
}
