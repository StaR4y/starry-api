package tech.starryapi.model;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tech.starryapi.payment.PaymentSettingsService;

@RestController
public class ModelCatalogController implements ModelCatalogApi {

    private final ModelCatalogProvider modelCatalog;
    private final ModelPricingService modelPricingService;
    private final PaymentSettingsService paymentSettingsService;

    public ModelCatalogController(
        ModelCatalogProvider modelCatalog,
        ModelPricingService modelPricingService,
        PaymentSettingsService paymentSettingsService
    ) {
        this.modelCatalog = modelCatalog;
        this.modelPricingService = modelPricingService;
        this.paymentSettingsService = paymentSettingsService;
    }

    @Override
    public Mono<ResponseEntity<ModelDtos.ModelListResponse>> models() {
        ModelDtos.ModelListResponse modelList = modelCatalog.modelList(Instant.now().getEpochSecond());
        return paymentSettingsService.config()
            .flatMap(config -> modelPricingService.applyPublicPricing(modelList, config.apiBillingMultiplier()))
            .map(ResponseEntity::ok);
    }
}
