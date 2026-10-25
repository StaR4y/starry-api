package tech.starryapi.proxy.tier;

/**
 * How the request is sent to the account a tier selected, fixed when the account is selected (design 7.5). Only the
 * native dispatch exists so far: it calls the same client with the same arguments as an untiered request does.
 */
public record TierDispatch(Kind kind) {

    public enum Kind {
        NATIVE
    }

    private static final TierDispatch NATIVE = new TierDispatch(Kind.NATIVE);

    public static TierDispatch nativeDispatch() {
        return NATIVE;
    }
}
