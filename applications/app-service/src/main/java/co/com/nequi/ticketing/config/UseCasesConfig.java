package co.com.nequi.ticketing.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@ComponentScan(basePackages = "co.com.nequi.ticketing.usecase",
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^.+UseCase$")
        },
        excludeFilters = {
                // Temporary: the order use cases are registered once every gateway of the orders has an adapter.
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "^co\\.com\\.nequi\\.ticketing\\.usecase\\."
                                + "(processorder|republishpendingorders)\\..+$")
        },
        useDefaultFilters = false)
public class UseCasesConfig {
}
