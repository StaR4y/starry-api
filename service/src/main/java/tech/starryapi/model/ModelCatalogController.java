package tech.starryapi.model;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.sourcetier.SourceTierModelListing;

@RestController
public class ModelCatalogController implements ModelCatalogApi {

    private final ModelCatalogProvider modelCatalog;
    private final ModelPricingService modelPricingService;
    private final PaymentSettingsService paymentSettingsService;
    private final SourceTierModelListing tierListing;

    public ModelCatalogController(
        ModelCatalogProvider modelCatalog,
        ModelPricingService modelPricingService,
        PaymentSettingsService paymentSettingsService,
        SourceTierModelListing tierListing
    ) {
        this.modelCatalog = modelCatalog;
        this.modelPricingService = modelPricingService;
        this.paymentSettingsService = paymentSettingsService;
        this.tierListing = tierListing;
    }

    @Override
    public Mono<ResponseEntity<ModelDtos.ModelListResponse>> models() {
        ModelDtos.ModelListResponse modelList = modelCatalog.modelList(Instant.now().getEpochSecond());
        return paymentSettingsService.config()
            .flatMap(config -> modelPricingService.applyPublicPricing(modelList, config.apiBillingMultiplier(), tierListing.current()))
            .map(ResponseEntity::ok);
    }
}
