package tech.starryapi.apikey;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

@Service
public class BillingMaintenanceService {
    private final DatabaseClient db;
    private final SystemMaintenanceTaskGate gate;

    public BillingMaintenanceService(DatabaseClient db, SystemMaintenanceTaskGate gate) { this.db = db; this.gate = gate; }

    @Scheduled(fixedDelayString = "${starry.billing.recovery-delay-ms:60000}")
    public void recover() {
        gate.whenOperational(() -> db.sql("SELECT request_hash FROM billing_reservations WHERE status IN ('RESERVED','DISPATCHED') AND expires_at < clock_timestamp() ORDER BY expires_at LIMIT 100")
            .map((row, metadata) -> row.get("request_hash", String.class)).all()
            .concatMap(hash -> db.sql("SELECT starry_api.release_api_reservation(:hash, FALSE, 'Reservation lease expired')")
                .bind("hash", hash).fetch().one())
            .then()).doOnError(error -> org.slf4j.LoggerFactory.getLogger(getClass()).warn("Billing reservation recovery failed", error))
            .subscribe(ignored -> {}, error -> {});
    }
}
