package tech.starryapi.egress;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;

/**
 * Where the administrator's choice to use Mihomo is kept: the system setting {@value #KEY}, {@code true} or
 * {@code false}. It does not exist until the switch is first used, which is how "no choice made yet" is told apart
 * from "switched off".
 */
@Component
public class MihomoSwitchStore {
    public static final String KEY = "egress_mihomo_enabled";
    private static final String LOCK = "starry_api:system_settings:egress_mihomo_enabled";

    private final SystemSettingRepository repository;

    public MihomoSwitchStore(SystemSettingRepository repository) {
        this.repository = repository;
    }

    /** The stored choice; empty when none was made or the stored text is not a boolean. */
    public Mono<Boolean> read() {
        return repository.findById(KEY).map(SystemSettingEntity::getSettingValue).flatMap(value -> {
            String text = value == null ? "" : value.trim();
            if (text.equalsIgnoreCase("true")) return Mono.just(true);
            if (text.equalsIgnoreCase("false")) return Mono.just(false);
            return Mono.empty();
        });
    }

    /** Stores the choice; concurrent writers are serialized so that the last one to commit is what everyone reads. */
    @Transactional
    public Mono<Void> write(boolean enabled) {
        return repository.acquireTransactionLock(LOCK)
            .then(Mono.defer(() -> repository.upsertSetting(KEY, Boolean.toString(enabled))))
            .then();
    }
}
