package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.apikey.*;
import tech.starryapi.codingplan.*;
import tech.starryapi.codingplan.CodingPlanDtos.*;
import tech.starryapi.common.ApiException;
import tech.starryapi.payment.RedemptionCodeService;
import tech.starryapi.payment.PaymentDtos.*;

@SpringBootTest(classes = CodingPlanBillingContract.TestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=false", "starry.stats.aggregation-enabled=false",
    "starry.model-pricing.remote-refresh-enabled=false", "starry.model-ranking.remote-refresh-enabled=false"
})
@Timeout(60)
abstract class CodingPlanBillingContract {
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories(basePackageClasses = {ApiKeyRepository.class, tech.starryapi.payment.RedemptionCodeRepository.class})
    @org.springframework.context.annotation.Import({ApiKeyService.class, CodingPlanService.class, CodingPlanSubscriptionService.class, RedemptionCodeService.class})
    static class TestConfiguration {
        @org.springframework.context.annotation.Bean tech.starryapi.config.ProxyProperties proxyProperties() { return new tech.starryapi.config.ProxyProperties(); }
        @org.springframework.context.annotation.Bean tech.starryapi.model.ModelCatalogProvider modelCatalog() { return new tech.starryapi.model.DefaultModelCatalogProvider(); }
    }
    @Autowired DatabaseClient db;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired ApiKeyService keys;
    @Autowired CodingPlanService plans;
    @Autowired CodingPlanSubscriptionService subscriptions;
    @Autowired RedemptionCodeService codes;

    @Test void balanceKeyNeverConsumesSubscriptionAndRefundsUnusedHold() {
        var user=user("10"); var plan=plan("5", "Anthropic"); var sub=subscriptions.grant(user.userId(),plan.planId(),user).block();
        var key=key(user,"BALANCE","Anthropic");
        keys.reserveUsage(key,"balance",new BigDecimal("2"),"{}").block();
        assertThat(balance(user)).isEqualByComparingTo("8");
        keys.accountUsage(key,"balance",new BigDecimal("0.5"),3,4).block();
        keys.accountUsage(key,"balance",new BigDecimal("0.5"),3,4).block();
        assertThat(balance(user)).isEqualByComparingTo("9.5");
        assertThat(subscriptions.listByUser(user.userId()).blockFirst().usedCredits()).isEqualByComparingTo("0");
        assertThat(subscriptions.ledger(sub.subscriptionId(),user.userId(),0).collectList().block()).hasSize(1);
    }

    @Test void codingPlanUsesMultipleSubscriptionsWithoutBalanceFallback() {
        var user=user("10"); var first=plan("0.25","Anthropic"); var second=plan("0.5","Anthropic");
        subscriptions.grant(user.userId(),first.planId(),user).block(); subscriptions.grant(user.userId(),second.planId(),user).block();
        var key=key(user,"CODING_PLAN","Anthropic");
        keys.reserveUsage(key,"split",new BigDecimal("0.6"),"{}").block();
        keys.accountUsage(key,"split",new BigDecimal("0.5"),1,2).block();
        var current=subscriptions.listByUser(user.userId()).collectList().block();
        assertThat(current.stream().map(CodingPlanSubscriptionResponse::usedCredits).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.5");
        assertThat(balance(user)).isEqualByComparingTo("10");
        assertThatThrownBy(()->keys.reserveUsage(key,"too-much",BigDecimal.ONE,"{}").block())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("CODING_PLAN_QUOTA_EXCEEDED"));
    }

    @Test void simultaneousKeysCannotOverspendSharedBalance() {
        var user=user("1"); var a=key(user,"BALANCE","OpenAI"); var b=key(user,"BALANCE","OpenAI");
        var results=Flux.range(0,24).flatMap(i->keys.reserveUsage(i%2==0?a:b,"parallel-"+i,new BigDecimal("0.2"),"{}")
            .thenReturn(true).onErrorReturn(false),24).collectList().block();
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        assertThat(balance(user)).isEqualByComparingTo("0");
    }

    @Test void simultaneousCodingPlanReservationsCannotOverspend() {
        var user=user("10"); var plan=plan("1","OpenAI"); subscriptions.grant(user.userId(),plan.planId(),user).block();
        var a=key(user,"CODING_PLAN","OpenAI"); var b=key(user,"CODING_PLAN","OpenAI");
        var results=Flux.range(0,24).flatMap(i->keys.reserveUsage(i%2==0?a:b,"quota-"+i,new BigDecimal("0.2"),"{}")
            .thenReturn(true).onErrorReturn(false),24).collectList().block();
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        assertThat(subscriptions.listByUser(user.userId()).blockFirst().reservedCredits()).isEqualByComparingTo("1");
        assertThat(balance(user)).isEqualByComparingTo("10");
    }

    @Test void purchaseIsIdempotentAcrossConcurrentRetries() {
        var user=user("10"); var plan=plan("5","OpenAI");
        var results=Flux.range(0,12).flatMap(i->subscriptions.purchase(user,plan.planId(),plan.version(),"same-purchase"),12).collectList().block();
        assertThat(results.stream().map(r->r.subscription().subscriptionId()).distinct().count()).isEqualTo(1);
        assertThat(balance(user)).isEqualByComparingTo("9");
        var another=plan("5","OpenAI");
        assertThatThrownBy(()->subscriptions.purchase(user,another.planId(),another.version(),"same-purchase").block()).isInstanceOf(ApiException.class);
    }

    @Test void oldSubscriptionAndRedemptionKeepPublishedTerms() {
        var user=user("10"); var plan=plan("5","Anthropic");
        var purchase=subscriptions.purchase(user,plan.planId(),plan.version(),"original").block();
        var code=codes.create(user,new CreateRedemptionCodeRequest(null,plan.planId(),1,null)).block().codes().getFirst();
        var revised=plans.update(plan.planId(),new UpdateCodingPlanRequest("Changed",null,null,null,new BigDecimal("99"),List.of("OpenAI"),null,false)).block();
        assertThat(revised.version()).isEqualTo(2);
        assertThat(subscriptions.listByUser(user.userId()).blockFirst().allowedProviders()).containsExactly("Anthropic");
        var other=user("0"); var redeemed=codes.redeem(other,new RedeemCodeRequest(code.code())).block();
        assertThat(redeemed.subscriptionId()).isNotBlank();
        assertThat(subscriptions.listByUser(other.userId()).blockFirst().quotaCredits()).isEqualByComparingTo("5");
        assertThatThrownBy(()->subscriptions.purchase(user,plan.planId(),plan.version(),"new-purchase").block()).isInstanceOf(ApiException.class);
        assertThat(purchase.subscription().planVersion()).isEqualTo(1);
    }

    @Test void exhaustionActivatesQueuedTermAndAuditsIt() {
        var user=user("10"); var plan=plan("1","Anthropic");
        subscriptions.purchase(user,plan.planId(),plan.version(),"first").block();
        var second=subscriptions.purchase(user,plan.planId(),plan.version(),"second").block();
        assertThat(second.subscription().status()).isEqualTo("SCHEDULED");
        var key=key(user,"CODING_PLAN","Anthropic");
        keys.reserveUsage(key,"exhaust",BigDecimal.ONE,"{}").block(); keys.accountUsage(key,"exhaust",BigDecimal.ONE,1,1).block();
        assertThat(subscriptions.listByUser(user.userId()).filter(s->s.subscriptionId().equals(second.subscription().subscriptionId())).blockFirst().status()).isEqualTo("ACTIVE");
        assertThat(subscriptions.ledger(second.subscription().subscriptionId(),user.userId(),0).collectList().block())
            .anyMatch(e->e.entryType().equals("TERM_ADVANCED"));
    }

    @Test void cancellationReleasesUnsentButRetainsUnknownDispatchedCost() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        keys.reserveUsage(key,"unsent",BigDecimal.ONE,"{}").block(); keys.releaseUsage(key,"unsent",false,"cancel").block();
        keys.releaseUsage(key,"unsent",false,"cancel").block(); assertThat(balance(user)).isEqualByComparingTo("10");
        keys.reserveUsage(key,"sent",BigDecimal.ONE,"{}").block(); keys.markUsageDispatched(key,"sent").block();
        keys.releaseUsage(key,"sent",false,"cancel").block(); assertThat(balance(user)).isEqualByComparingTo("9");
        assertThatThrownBy(()->keys.reserveUsage(key,"blocked",BigDecimal.ONE,"{}").block()).isInstanceOf(ApiException.class);
        keys.accountUsage(key,"sent",new BigDecimal("0.4"),1,1).block(); assertThat(balance(user)).isEqualByComparingTo("9.6");
    }

    @Test void quotaLimitIncludesInFlightReservations() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        db.sql("UPDATE api_keys SET credits_limit=1 WHERE key_id=:id").bind("id",key.keyId()).fetch().rowsUpdated().block();
        keys.reserveUsage(key,"held",new BigDecimal("0.75"),"{}").block();
        assertThatThrownBy(()->keys.reserveUsage(key,"over",new BigDecimal("0.5"),"{}").block()).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->keys.update(user,key.keyId(),new ApiKeyDtos.UpdateApiKeyRequest(null,null,null,null,"CODING_PLAN")).block())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
    }

    @Test void settlementCanTopUpOnlyTheSelectedSource() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        keys.reserveUsage(key,"topup",BigDecimal.ONE,"{}").block(); keys.accountUsage(key,"topup",new BigDecimal("2"),1,1).block();
        assertThat(balance(user)).isEqualByComparingTo("8");
        keys.reserveUsage(key,"short",BigDecimal.ONE,"{}").block();
        assertThatThrownBy(()->keys.accountUsage(key,"short",new BigDecimal("20"),1,1).block()).isInstanceOf(ApiException.class);
        assertThat(balance(user)).isEqualByComparingTo("7");
    }

    @Test void wrongProviderCannotUseCodingPlanOrFallbackToBalance() {
        var user=user("10"); var plan=plan("100","Anthropic"); subscriptions.grant(user.userId(),plan.planId(),user).block();
        var key=key(user,"CODING_PLAN","OpenAI");
        assertThatThrownBy(()->keys.reserveUsage(key,"scope",BigDecimal.ONE,"{}").block()).isInstanceOf(ApiException.class);
        assertThat(balance(user)).isEqualByComparingTo("10");
    }

    @Test void explicitEmptyProvidersClearsScopeAndInvalidUpdatesAreRejected() {
        var plan=plan("5","Anthropic");
        assertThat(plans.update(plan.planId(),new UpdateCodingPlanRequest(null,null,null,null,null,List.of(),null,null)).block().allowedProviders()).isEmpty();
        assertThatThrownBy(()->plans.update(plan.planId(),new UpdateCodingPlanRequest(" ",null,null,null,null,null,null,null))).isInstanceOf(ApiException.class);
    }

    @Test void cannotDeleteOrChangeSourceOfZeroCostPendingRequest() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        keys.reserveUsage(key,"free",BigDecimal.ZERO,"{}").block();
        assertThatThrownBy(()->keys.delete(user,key.keyId()).block())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("API_KEY_BILLING_BUSY"));
        keys.releaseUsage(key,"free",true,"unsent").block();
        keys.delete(user,key.keyId()).block();
    }

    @Test void revokedAdmittedSubscriptionStillSettlesAndCannotOverlapAdvancedTerm() {
        var user=user("10"); var plan=plan("5","OpenAI");
        var first=subscriptions.purchase(user,plan.planId(),plan.version(),"first").block().subscription();
        subscriptions.purchase(user,plan.planId(),plan.version(),"second").block();
        var key=key(user,"CODING_PLAN","OpenAI");
        keys.reserveUsage(key,"revoked-hold",BigDecimal.ONE,"{}").block();
        keys.markUsageDispatched(key,"revoked-hold").block();
        subscriptions.updateStatus(first.subscriptionId(),"REVOKED",user.userId(),"review").block();
        assertThatThrownBy(()->subscriptions.updateStatus(first.subscriptionId(),"ACTIVE",user.userId(),"restore").block())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("SUBSCRIPTION_TERM_OVERLAP"));
        keys.accountUsage(key,"revoked-hold",new BigDecimal("0.4"),1,1).block();
        var history=subscriptions.ledger(first.subscriptionId(),user.userId(),0).collectList().block();
        assertThat(history.stream().map(SubscriptionLedgerEntry::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("4.6");
        assertThat(history.stream().map(SubscriptionLedgerEntry::reservedDelta).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0");
    }

    @Test void expiredAdmittedHoldSettlesWithoutUsingNewSource() {
        var user=user("10"); var plan=plan("5","OpenAI");
        var sub=subscriptions.grant(user.userId(),plan.planId(),user).block();
        var key=key(user,"CODING_PLAN","OpenAI");
        keys.reserveUsage(key,"expires",BigDecimal.ONE,"{}").block();
        db.sql("UPDATE coding_plan_subscriptions SET starts_at=clock_timestamp()-interval '2 days', ends_at=clock_timestamp()-interval '1 day' WHERE subscription_id=:id")
            .bind("id",sub.subscriptionId()).fetch().rowsUpdated().block();
        assertThat(subscriptions.summary(user.userId(),"OpenAI").block().reservedCredits()).isEqualTo("1.000000");
        keys.accountUsage(key,"expires",new BigDecimal("0.5"),1,1).block();
        assertThat(subscriptions.summary(user.userId(),"OpenAI").block().availableCredits()).isEqualTo("0");
        assertThat(balance(user)).isEqualByComparingTo("10");
    }

    @Test void administrativeWaiverIsAuditedAndCannotBeRepeated() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        keys.reserveUsage(key,"review",BigDecimal.ONE,"{}").block(); keys.markUsageDispatched(key,"review").block();
        keys.releaseUsage(key,"review",false,"unknown").block();
        String hash=tech.starryapi.common.Hashing.sha256(key.keyId().length()+":"+key.keyId()+"review");
        var result=db.sql("SELECT starry_api.resolve_billing_review(:hash,:actor,'WAIVE','Confirmed no charge',0) AS result")
            .bind("hash",hash).bind("actor",user.userId()).map((r,m)->r.get("result",String.class)).one();
        assertThat(result.block()).isEqualTo("ACCOUNTED");
        assertThat(result.block()).isEqualTo("BILLING_REQUEST_FINISHED");
        assertThat(balance(user)).isEqualByComparingTo("10");
        assertThat(db.sql("SELECT count(*) AS n FROM billing_review_actions WHERE request_hash=:hash")
            .bind("hash",hash).map((r,m)->r.get("n",Long.class)).one().block()).isEqualTo(1);
    }

    @Test void missingReservationAndChangedSettlementAreRejected() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        assertThatThrownBy(()->keys.accountUsage(key,"absent",BigDecimal.ONE,1,1).block())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("BILLING_RESERVATION_REQUIRED"));
        keys.reserveUsage(key,"known",BigDecimal.ONE,"{}").block(); keys.accountUsage(key,"known",BigDecimal.ONE,1,1).block();
        assertThatThrownBy(()->keys.accountUsage(key,"known",new BigDecimal("2"),1,1).block())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(balance(user)).isEqualByComparingTo("9");
    }

    @Test void summariesAreOwnerAndProviderScopedAndLedgerIsPrivate() {
        var user=user("10"); var other=user("10");
        var sub=subscriptions.grant(user.userId(),plan("5","OpenAI").planId(),user).block();
        subscriptions.grant(user.userId(),plan("3","Anthropic").planId(),user).block();
        assertThat(subscriptions.summary(user.userId(),"").block().availableCredits()).isEqualTo("8.000000");
        assertThat(subscriptions.summary(user.userId(),"OpenAI").block().availableCredits()).isEqualTo("5.000000");
        assertThat(subscriptions.summary(other.userId(),"").block().activeCount()).isZero();
        assertThat(subscriptions.ledger(sub.subscriptionId(),other.userId(),0).collectList().block()).isEmpty();
    }

    @Test void keySourcePersistsAndCreditLimitCanBeExplicitlyCleared() {
        var user=user("10"); var key=key(user,"BALANCE","OpenAI");
        var capped=keys.update(user,key.keyId(),new ApiKeyDtos.UpdateApiKeyRequest(null,null,BigDecimal.ONE,null,"CODING_PLAN")).block();
        assertThat(capped.getBillingSource()).isEqualTo("CODING_PLAN");
        assertThat(capped.getCreditsLimit()).isEqualByComparingTo("1");
        var uncapped=keys.update(user,key.keyId(),new ApiKeyDtos.UpdateApiKeyRequest(null,null,null,null,null,true)).block();
        assertThat(uncapped.getCreditsLimit()).isNull();
        assertThat(uncapped.getBillingSource()).isEqualTo("CODING_PLAN");
    }

    private AdminPrincipal user(String balance) {
        String id=UUID.randomUUID().toString();
        db.sql("INSERT INTO admin_users(user_id,username,role,balance,password_hash,password_salt,enabled,first_login) VALUES(:id,:id,'USER',:balance,repeat('a',64),repeat('b',32),true,false)")
            .bind("id",id).bind("balance",new BigDecimal(balance)).fetch().rowsUpdated().block();
        return new AdminPrincipal(id,id,"USER",Instant.now().plusSeconds(3600),false);
    }
    private CodingPlanResponse plan(String quota,String provider) {
        return plans.create(new CreateCodingPlanRequest("Plan "+UUID.randomUUID(),null,BigDecimal.ONE,30,new BigDecimal(quota),List.of(provider),0)).block();
    }
    private ApiKeyPrincipal key(AdminPrincipal user,String source,String provider) {
        var created=keys.create(user,new ApiKeyDtos.CreateApiKeyRequest("Test",null,provider,source)).block();
        return new ApiKeyPrincipal(created.keyId(),user.userId(),"USER","Test",null,BigDecimal.ZERO,balance(user),provider,true,source);
    }
    private BigDecimal balance(AdminPrincipal user) {
        return db.sql("SELECT balance FROM admin_users WHERE user_id=:id").bind("id",user.userId())
            .map((r,m)->r.get("balance",BigDecimal.class)).one().block();
    }
}
