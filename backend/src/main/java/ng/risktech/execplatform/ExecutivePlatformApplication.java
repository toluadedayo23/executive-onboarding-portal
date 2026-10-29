package ng.risktech.execplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExecutivePlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExecutivePlatformApplication.class, args);
    }
}
