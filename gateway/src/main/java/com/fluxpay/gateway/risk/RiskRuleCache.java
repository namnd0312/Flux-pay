package com.fluxpay.gateway.risk;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fluxpay.gateway.risk.RiskRuleStore.RuleRow;

/**
 * The rules every request is evaluated against, compiled once and held in memory, so the
 * request path reads no rule from the database.
 *
 * <p>The list is immutable and replaced as a whole: {@link #refresh()} builds a complete new
 * list and then assigns one {@code volatile} reference. A request therefore sees either the
 * old rules or the new rules, never a half-loaded mix, and no lock is taken on the read side.
 *
 * <p>A rule edited by SQL takes effect at the next refresh, so for up to one
 * {@code fluxpay.risk.refresh-interval} the gateway decides with the previous rules.
 *
 * <p>Two different failures are handled differently:
 * <ul>
 *   <li>one row does not compile: that rule is logged at ERROR and left out, the others load;</li>
 *   <li>the rules cannot be read at all (database error): the refresh is abandoned and the
 *       list loaded earlier stays in use. An empty list would silently allow everything.</li>
 * </ul>
 * The first load happens before the web server accepts requests, and a failure there stops
 * the application: there is no earlier list to fall back on.
 */
@Component
public class RiskRuleCache implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleCache.class);

    private final RiskRuleStore store;
    private final RuleCompiler compiler;

    private volatile List<CompiledRule> rules;

    public RiskRuleCache(RiskRuleStore store, RuleCompiler compiler) {
        this.store = store;
        this.compiler = compiler;
    }

    /** First load. Runs once all beans exist (so migrations are applied) and before requests arrive. */
    @Override
    public void afterSingletonsInstantiated() {
        rules = load();
        log.info("Risk rules loaded: {} active", rules.size());
    }

    /** The active rules in evaluation order. */
    public List<CompiledRule> current() {
        List<CompiledRule> snapshot = rules;
        if (snapshot == null) {
            throw new IllegalStateException("Risk rules have not been loaded yet");
        }
        return snapshot;
    }

    /**
     * Reloads the rules from the database. Returns false, and keeps the rules loaded earlier,
     * if they could not be read.
     */
    public boolean refresh() {
        List<CompiledRule> previous = rules;
        List<CompiledRule> fresh;
        try {
            fresh = load();
        } catch (RuntimeException ex) {
            log.error("Risk rule refresh failed; keeping the {} rule(s) loaded earlier",
                    previous == null ? 0 : previous.size(), ex);
            return false;
        }
        rules = fresh;
        if (!fresh.equals(previous)) {
            log.info("Risk rules changed: {} active", fresh.size());
        }
        return true;
    }

    /** Inert unless scheduling is enabled ({@code fluxpay.scheduling.enabled}); tests call {@link #refresh()}. */
    @Scheduled(fixedDelayString = "${fluxpay.risk.refresh-interval:10s}")
    void scheduledRefresh() {
        refresh();
    }

    private List<CompiledRule> load() {
        return compile(store.findEnabled());
    }

    /** Compiles rows in the given order, leaving out the ones that are not valid rules. */
    List<CompiledRule> compile(List<RuleRow> rows) {
        List<CompiledRule> compiled = new ArrayList<>();
        for (RuleRow row : rows) {
            try {
                compiled.add(new CompiledRule(row.id(), row.merchantId(), row.priority(),
                        compiler.compile(row.condition()), outcome(row.action()), row.reasonCode()));
            } catch (InvalidRuleException ex) {
                log.error("Risk rule {} is invalid and was skipped: {}", row.id(), ex.getMessage());
            }
        }
        return List.copyOf(compiled);
    }

    private static RiskDecision.Outcome outcome(String action) {
        if ("REJECT".equals(action)) {
            return RiskDecision.Outcome.REJECT;
        }
        throw new InvalidRuleException("unknown action \"" + action + "\"");
    }
}
