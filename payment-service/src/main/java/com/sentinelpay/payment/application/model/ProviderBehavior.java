package com.sentinelpay.payment.application.model;

import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

@Component
public class ProviderBehavior {

    private final Map<Provider, Deque<ProgrammedOutcome>> queues = new EnumMap<>(Provider.class);

    public void program(Provider provider, Outcome... outcomes) {
        Deque<ProgrammedOutcome> queue = new ArrayDeque<>();
        for (Outcome outcome : outcomes) {
            queue.add(ProgrammedOutcome.of(outcome));
        }
        queues.put(provider, queue);
    }

    public void program(Provider provider, ProgrammedOutcome... outcomes) {
        Deque<ProgrammedOutcome> queue = new ArrayDeque<>();
        for (ProgrammedOutcome outcome : outcomes) {
            queue.add(outcome);
        }
        queues.put(provider, queue);
    }

    public ProgrammedOutcome nextOutcome(Provider provider) {
        Deque<ProgrammedOutcome> queue = queues.get(provider);
        if (queue == null || queue.isEmpty()) {
            return ProgrammedOutcome.of(Outcome.AUTHORIZED);
        }
        return queue.removeFirst();
    }

    public void reset() {
        queues.clear();
    }

    public record ProgrammedOutcome(Outcome outcome, boolean storeOnAmbiguous) {

        public static ProgrammedOutcome of(Outcome outcome) {
            return new ProgrammedOutcome(outcome, true);
        }

        public static ProgrammedOutcome ambiguousWithoutStore() {
            return new ProgrammedOutcome(Outcome.AMBIGUOUS_TIMEOUT, false);
        }
    }
}
