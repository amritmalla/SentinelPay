package com.sentinelpay.payment.infrastructure.dev;

import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("dev")
@RestController
@RequestMapping("/dev/providers")
public class ProviderControlController {

    private final ProviderBehavior behavior;

    public ProviderControlController(ProviderBehavior behavior) {
        this.behavior = behavior;
    }

    @PostMapping("/{provider}/program")
    public void program(@PathVariable String provider, @RequestBody ProgramRequest request) {
        Provider resolved = Provider.fromDbValue(provider.toLowerCase());
        Outcome[] outcomes = request.outcomes().stream()
                .map(String::toUpperCase)
                .map(Outcome::valueOf)
                .toArray(Outcome[]::new);
        behavior.program(resolved, outcomes);
    }

    public record ProgramRequest(List<String> outcomes) {
    }
}
