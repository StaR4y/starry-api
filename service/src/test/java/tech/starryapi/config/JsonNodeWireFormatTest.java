package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tech.starryapi.common.JsonNodes;

/**
 * JSON trees are what the proxy rewrites between the client and the upstream, so what goes in must come out in the
 * same order with the same numbers. Expected strings were recorded from the Jackson 2 application.
 *
 * <p>Numbers worth knowing: integers of any size are kept exactly (long, then BigInteger); every number with a
 * fraction or exponent is read as a double, so {@code 1e3} comes out as {@code 1000.0} and digits beyond double
 * precision are rounded. That is the existing behavior the clients already live with and it must not drift.
 *
 * <p>{@code wire} is what the WebFlux codecs write; {@code compact} is the char-based form of {@code toString()} and
 * {@code writeValueAsString()}. The only byte-level difference from Jackson 2 is that its UTF-8 generator wrote a
 * supplementary character (an emoji) as a pair of surrogate escapes, {@code \\uD83D\\uDE00}, where Jackson 3 writes the
 * character itself. Both are the same JSON string for every parser, so the wire forms are compared with such pairs
 * resolved; everything else is compared character for character.
 */
class JsonNodeWireFormatTest {

    private static final Pattern SURROGATE_PAIR = Pattern.compile("\\\\u(D[89AB][0-9A-Fa-f]{2})\\\\u(D[C-Fc-f][0-9A-Fa-f]{2})");

    @Test
    void propertyOrderEscapesAndNumbersSurviveTheProxy() {
        ProductionJson.run(json -> {
            tree(json, "chatRequest",
                """
                {"model":"gpt-5","messages":[{"role":"user","content":"你好 \\uD83D\\uDE00 \\"q\\" \\\\ \\/ \\n\\t\\u0001"}],"stream":true,"temperature":0.7,"max_tokens":4096,"top_p":1.0,"n":1,"stop":null,"logit_bias":{"50256":-100}}""",
                "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"你好 😀 \\\"q\\\" \\\\ / \\n\\t\\u0001\"}],\"stream\":true,\"temperature\":0.7,\"max_tokens\":4096,\"top_p\":1.0,\"n\":1,\"stop\":null,\"logit_bias\":{\"50256\":-100}}", "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"你好 \\uD83D\\uDE00 \\\"q\\\" \\\\ / \\n\\t\\u0001\"}],\"stream\":true,\"temperature\":0.7,\"max_tokens\":4096,\"top_p\":1.0,\"n\":1,\"stop\":null,\"logit_bias\":{\"50256\":-100}}");
            tree(json, "propertyOrder",
                """
                {"z":1,"a":2,"m":{"y":1,"b":2},"k":[{"q":1,"c":2}],"10":1,"2":2,"B":3,"a1":4,"A":5}""",
                "{\"z\":1,\"a\":2,\"m\":{\"y\":1,\"b\":2},\"k\":[{\"q\":1,\"c\":2}],\"10\":1,\"2\":2,\"B\":3,\"a1\":4,\"A\":5}", "{\"z\":1,\"a\":2,\"m\":{\"y\":1,\"b\":2},\"k\":[{\"q\":1,\"c\":2}],\"10\":1,\"2\":2,\"B\":3,\"a1\":4,\"A\":5}");
            tree(json, "integers",
                """
                [0,-0,1,-1,2147483647,2147483648,-2147483648,-2147483649,9223372036854775807,9223372036854775808,-9223372036854775808,-9223372036854775809,123456789012345678901234567890]""",
                "[0,0,1,-1,2147483647,2147483648,-2147483648,-2147483649,9223372036854775807,9223372036854775808,-9223372036854775808,-9223372036854775809,123456789012345678901234567890]", "[0,0,1,-1,2147483647,2147483648,-2147483648,-2147483649,9223372036854775807,9223372036854775808,-9223372036854775808,-9223372036854775809,123456789012345678901234567890]");
            tree(json, "floats",
                """
                [0.0,-0.0,1.0,1.5,0.1,100.0,1e3,1E3,1e-7,1E+3,1e+3,1.5e300,1e22,1e23,123456789.123456789,0.30000000000000004,0.1000000000000000055511151231257827]""",
                "[0.0,-0.0,1.0,1.5,0.1,100.0,1000.0,1000.0,1.0E-7,1000.0,1000.0,1.5E300,1.0E22,1.0E23,1.2345678912345679E8,0.30000000000000004,0.1]", "[0.0,-0.0,1.0,1.5,0.1,100.0,1000.0,1000.0,1.0E-7,1000.0,1000.0,1.5E300,1.0E22,1.0E23,1.2345678912345679E8,0.30000000000000004,0.1]");
            tree(json, "floatEdges",
                """
                [12345678901234567890.12345678901234567890,1.7976931348623157e308,4.9e-324,5e-324,3.4028235e38,1.401298464324817e-45,2.2250738585072014E-308,9007199254740993,9007199254740993.0,0.000001,0.0000001,1234567.0,12345678.0,10000000.0,1.0E7,100000000000000000000.0,1e21,1e20]""",
                "[1.2345678901234567E19,1.7976931348623157E308,4.9E-324,4.9E-324,3.4028235E38,1.401298464324817E-45,2.2250738585072014E-308,9007199254740993,9.007199254740992E15,1.0E-6,1.0E-7,1234567.0,1.2345678E7,1.0E7,1.0E7,1.0E20,1.0E21,1.0E20]", "[1.2345678901234567E19,1.7976931348623157E308,4.9E-324,4.9E-324,3.4028235E38,1.401298464324817E-45,2.2250738585072014E-308,9007199254740993,9.007199254740992E15,1.0E-6,1.0E-7,1234567.0,1.2345678E7,1.0E7,1.0E7,1.0E20,1.0E21,1.0E20]");
            tree(json, "trailingZeros",
                """
                [0.10,1.50,100.00,1E+2,1.0,10.0,0.00,0e0,-0.00,3.14159265358979323846264338327950288]""",
                "[0.1,1.5,100.0,100.0,1.0,10.0,0.0,0.0,-0.0,3.141592653589793]", "[0.1,1.5,100.0,100.0,1.0,10.0,0.0,0.0,-0.0,3.141592653589793]");
            tree(json, "nullsAndEmpties",
                """
                {"a":null,"b":true,"c":false,"d":{},"e":[],"f":[[]],"g":{"h":{}},"i":""}""",
                "{\"a\":null,\"b\":true,\"c\":false,\"d\":{},\"e\":[],\"f\":[[]],\"g\":{\"h\":{}},\"i\":\"\"}", "{\"a\":null,\"b\":true,\"c\":false,\"d\":{},\"e\":[],\"f\":[[]],\"g\":{\"h\":{}},\"i\":\"\"}");
            tree(json, "duplicateKeysKeepTheLastValue",
                """
                {"a":1,"a":2}""",
                "{\"a\":2}", "{\"a\":2}");
            tree(json, "pretty",
                "  {\n  \"pretty\" : [ 1 , 2 ,\n 3 ] }  ",
                "{\"pretty\":[1,2,3]}", "{\"pretty\":[1,2,3]}");
            tree(json, "trailingTokensAreIgnored",
                """
                {"a":1} garbage""",
                "{\"a\":1}", "{\"a\":1}");
            tree(json, "twoDocuments",
                """
                {"a":1}{"b":2}""",
                "{\"a\":1}", "{\"a\":1}");
            tree(json, "overflowingExponent",
                """
                [1e400,-1e400]""",
                "[\"Infinity\",\"-Infinity\"]", "[\"Infinity\",\"-Infinity\"]");
            tree(json, "toolSchema",
                """
                {"tool":{"input_schema":{"type":"object","properties":{"x":{"type":["string","null"],"default":0.5,"minimum":-1e-3,"enum":[1,2.0,"3"]}},"required":["x"],"additionalProperties":false}}}""",
                "{\"tool\":{\"input_schema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":[\"string\",\"null\"],\"default\":0.5,\"minimum\":-0.001,\"enum\":[1,2.0,\"3\"]}},\"required\":[\"x\"],\"additionalProperties\":false}}}", "{\"tool\":{\"input_schema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":[\"string\",\"null\"],\"default\":0.5,\"minimum\":-0.001,\"enum\":[1,2.0,\"3\"]}},\"required\":[\"x\"],\"additionalProperties\":false}}}");
            tree(json, "usageWithEpoch",
                """
                {"usage":{"input_tokens":25,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"service_tier":"standard"},"created":1761200665,"created_at":1761200665.123,"prompt_tokens_details":{"cached_tokens":0}}""",
                "{\"usage\":{\"input_tokens\":25,\"output_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"service_tier\":\"standard\"},\"created\":1761200665,\"created_at\":1.761200665123E9,\"prompt_tokens_details\":{\"cached_tokens\":0}}", "{\"usage\":{\"input_tokens\":25,\"output_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"service_tier\":\"standard\"},\"created\":1761200665,\"created_at\":1.761200665123E9,\"prompt_tokens_details\":{\"cached_tokens\":0}}");
            tree(json, "scalarRoots", "12345678901234567890", "12345678901234567890", "12345678901234567890");
            tree(json, "unicode",
                """
                {"unicode":"\\u4f60\\u597d \\ud83d\\ude00 \\u2028\\u2029 \\u007f \\u0080","esc":"<>&'\\""}""",
                "{\"unicode\":\"你好 😀 \u2028\u2029 \u007f \u0080\",\"esc\":\"<>&'\\\"\"}", "{\"unicode\":\"你好 \\uD83D\\uDE00 \u2028\u2029 \u007f \u0080\",\"esc\":\"<>&'\\\"\"}");
            tree(json, "loneSurrogate",
                """
                {"k":"\\ud800"}""",
                "{\"k\":\"\ud800\"}", "{\"k\":\"\\uD800\"}");
        });
    }

    @Test
    void malformedBodiesAreRejected() {
        ProductionJson.run(json -> {
            for (String body : new String[] {"{\"a\": 01}", "{\"a\": +1}", "{\"a\": .5}", "{\"a\": NaN}", "{\"a\": 'x'}", "{a: 1}",
                "{\"a\": 1,}", "[1,]", "{\"a\":\"\t\"}", "{", "[1,2", "{\"a\":\"unterminated}", ""}) {
                assertThat(json.echoTree(body)).as("body %s", body).isEqualTo("HTTP 400");
            }
        });
    }

    @Test
    void treesBuiltInCodeWriteTheirNumbersTheSameWay() {
        ProductionJson.run(json -> {
            var mapper = json.mapper();
            var node = mapper.createObjectNode();
            node.put("bd100", new BigDecimal("100"));
            node.put("bd150", new BigDecimal("1.50"));
            node.put("bd0", BigDecimal.ZERO);
            node.put("bd0scaled", new BigDecimal("0.000"));
            node.put("bdBig", new BigDecimal("123456789012345678901234567890.123456789"));
            node.put("bdSci", new BigDecimal("1E+3"));
            node.put("bdSmall", new BigDecimal("0.00000001"));
            node.put("d1", 1.0d);
            node.put("d15", 1.5d);
            node.put("d1e10", 1.0e10d);
            node.put("d1e7", 1.0e7d);
            node.put("d1em7", 1.0e-7d);
            node.put("dNaN", Double.NaN);
            node.put("dInf", Double.POSITIVE_INFINITY);
            node.put("f15", 1.5f);
            node.put("f01", 0.1f);
            node.put("l", Long.MAX_VALUE);
            node.put("i", Integer.MIN_VALUE);
            node.put("s", (short) 3);
            node.put("bi", new BigInteger("123456789012345678901234567890"));
            node.put("bool", true);
            node.put("str", "x");
            node.putNull("nul");
            node.put("nullStr", (String) null);
            node.put("nullInt", (Integer) null);
            node.put("nullBd", (BigDecimal) null);
            node.putArray("arr").add(1).add(2L).add(1.5).add(new BigDecimal("2.50")).add("s").addNull().add(true);
            node.putObject("obj").put("z", 1).put("a", 2);
            node.putPOJO("pojo", Map.of("k", 1));

            String expected = "{\"bd100\":100,\"bd150\":1.50,\"bd0\":0,\"bd0scaled\":0.000,\"bdBig\":123456789012345678901234567890.123456789,\"bdSci\":1E+3,\"bdSmall\":1E-8,\"d1\":1.0,\"d15\":1.5,\"d1e10\":1.0E10,\"d1e7\":1.0E7,\"d1em7\":1.0E-7,\"dNaN\":\"NaN\",\"dInf\":\"Infinity\",\"f15\":1.5,\"f01\":0.1,\"l\":9223372036854775807,\"i\":-2147483648,\"s\":3,\"bi\":123456789012345678901234567890,\"bool\":true,\"str\":\"x\",\"nul\":null,\"nullStr\":null,\"nullInt\":null,\"nullBd\":null,\"arr\":[1,2,1.5,2.50,\"s\",null,true],\"obj\":{\"z\":1,\"a\":2},\"pojo\":{\"k\":1}}";
            assertThat(node.toString()).isEqualTo(expected);
            assertThat(mapper.writeValueAsString(node)).isEqualTo(expected);
            assertThat(json.write(node)).isEqualTo(expected);
            assertThat(new String(JsonNodes.toUtf8Bytes(node), StandardCharsets.UTF_8)).isEqualTo(expected);
        });
    }

    @Test
    void objectsAndMapsKeepNumberTypesAndOrder() {
        ProductionJson.run(json -> {
            String body = "{\"a\":1,\"b\":1.5,\"c\":12345678901234567890,\"d\":[1,2.5,\"x\",null,true],\"e\":{\"z\":1,\"a\":2},"
                + "\"f\":9223372036854775807,\"g\":2147483648,\"h\":1e3,\"i\":0.1000000000000000055511151231257827}";
            Object value = json.mapper().readValue(body, Object.class);
            assertThat(describe(value)).isEqualTo("{a=1:Integer, b=1.5:Double, c=12345678901234567890:BigInteger, d=[1:Integer, 2.5:Double, x:String, null, true:Boolean, ], e={z=1:Integer, a=2:Integer, }, f=9223372036854775807:Long, g=2147483648:Long, h=1000.0:Double, i=0.1:Double, }");
            assertThat(json.read(body, java.util.LinkedHashMap.class)).isEqualTo("{a=1, b=1.5, c=12345678901234567890, d=[1, 2.5, x, null, true], e={z=1, a=2}, f=9223372036854775807, g=2147483648, h=1000.0, i=0.1}");
        });
    }

    private static String describe(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            map.forEach((k, v) -> out.append(k).append("=").append(describe(v)).append(", "));
            return out.append("}").toString();
        }
        if (value instanceof java.util.List<?> list) {
            StringBuilder out = new StringBuilder("[");
            list.forEach(v -> out.append(describe(v)).append(", "));
            return out.append("]").toString();
        }
        return value + (value == null ? "" : ":" + value.getClass().getSimpleName());
    }

    private static String resolveSurrogatePairs(String json) {
        return SURROGATE_PAIR.matcher(json).replaceAll(match ->
            Matcher.quoteReplacement(new String(new char[] {(char) Integer.parseInt(match.group(1), 16),
                (char) Integer.parseInt(match.group(2), 16)})));
    }

    /** Checks every route a tree can take through the application against the recorded forms. */
    private static void tree(ProductionJson.Session json, String name, String input, String compact, String wire) throws Exception {
        var mapper = json.mapper();
        var parsed = mapper.readTree(input);
        String viaToString = parsed.toString();
        String viaMapper = mapper.writeValueAsString(parsed);
        String viaUtf8 = new String(JsonNodes.toUtf8Bytes(parsed), StandardCharsets.UTF_8);
        String viaServer = json.echoTree(input);
        var upstream = json.upstream(input, input);
        assertThat(viaToString).as(name + " toString").isEqualTo(compact);
        assertThat(viaMapper).as(name + " writeValueAsString").isEqualTo(compact);
        // a UTF-8 round trip turns an unpaired surrogate into '?', which is all toUtf8Bytes can do with one
        assertThat(viaUtf8).as(name + " JsonNodes.toUtf8Bytes")
            .isEqualTo(new String(compact.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(resolveSurrogatePairs(viaServer)).as(name + " WebFlux echo").isEqualTo(resolveSurrogatePairs(wire));
        assertThat(resolveSurrogatePairs(upstream.sentOnWire())).as(name + " sent to upstream")
            .isEqualTo(resolveSurrogatePairs(wire));
        assertThat(upstream.receivedTree()).as(name + " read from upstream").isEqualTo(compact);
    }
}
