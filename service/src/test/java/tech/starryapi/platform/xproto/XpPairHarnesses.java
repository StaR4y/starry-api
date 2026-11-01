package tech.starryapi.platform.xproto;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.starryapi.platform.xproto.CrossProtocolContractTest.PairHarness;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.proxy.XpPairHarness;

/**
 * The harnesses of the pairs this release offers (design 7.14): one per pair, each playing a case through the gateway's own
 * entry, planner, dispatcher and relay client against a relay that answers as the case says (see {@link XpPairHarness}).
 * The contract test replays the fixtures with them and the recorder rewrites what they are expected to produce.
 */
final class XpPairHarnesses {

    private XpPairHarnesses() {
    }

    /** A harness for every pair the registry of this release offers. */
    static Map<PairKey, PairHarness> forTheOfferedPairs() {
        Map<PairKey, PairHarness> harnesses = new LinkedHashMap<>();
        for (PairKey pair : PairRegistry.defaults().offeredPairs()) {
            harnesses.put(pair, fixture -> XpPairHarness.run(pair, fixture.id(), fixture.inputs()));
        }
        return harnesses;
    }
}
