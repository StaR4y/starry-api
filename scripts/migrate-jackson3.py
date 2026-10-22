#!/usr/bin/env python3
"""Mechanical part of the Jackson 2 -> 3 migration for Starry API. Idempotent: running it twice changes nothing.

    python3 scripts/migrate-jackson3.py service/src service-api/src plugin-api/src

Run it on any branch that was started before the Spring Boot 4 / Jackson 3 migration, then fix what the compiler
reports. Delete this script once no such branch is left.

Only renames that are safe without type information are applied. Context-dependent changes (Iterator-returning
fields()/fieldNames(), IOException handling around ObjectMapper calls, ObjectMapper configuration, Spring wiring)
are left for the compiler to point out. Things the compiler does NOT point out and that need a manual look:
  * `catch (IOException ...)` around readTree/readValue/writeValue that still compiles because the try body does other
    I/O: Jackson 3 throws the unchecked JacksonException, so add `| JacksonException` or the failure escapes;
  * the strict single-value JsonNode accessors intValue()/longValue()/doubleValue()/decimalValue()/booleanValue()/
    textValue() now throw on a node of another type (they returned 0 / false / null): use the (default) overloads;
  * JsonNode.deepCopy() returns JsonNode, not the generic T: casts are needed where an ObjectNode is expected (compiler
    catches those);
  * ObjectMapper defaults changed (property order, enum names, null for primitives, trailing tokens): see JacksonConfig.
"""
import pathlib
import re
import sys

# Class renames, applied to fully qualified names first (imports and inline FQNs).
FQN = [
    ('com.fasterxml.jackson.core.JsonProcessingException', 'tools.jackson.core.JacksonException'),
    ('com.fasterxml.jackson.core.JsonParseException', 'tools.jackson.core.exc.StreamReadException'),
    ('com.fasterxml.jackson.core.JsonGenerationException', 'tools.jackson.core.exc.StreamWriteException'),
    ('com.fasterxml.jackson.databind.JsonMappingException', 'tools.jackson.databind.DatabindException'),
    ('com.fasterxml.jackson.databind.node.TextNode', 'tools.jackson.databind.node.StringNode'),
    ('com.fasterxml.jackson.databind.JsonSerializer', 'tools.jackson.databind.ValueSerializer'),
    ('com.fasterxml.jackson.databind.JsonDeserializer', 'tools.jackson.databind.ValueDeserializer'),
    ('com.fasterxml.jackson.databind.SerializerProvider', 'tools.jackson.databind.SerializationContext'),
]
# Simple names of the renamed classes (word boundaries; only in files that imported the Jackson class).
SIMPLE = [
    ('JsonProcessingException', 'JacksonException'),
    ('JsonParseException', 'StreamReadException'),
    ('JsonGenerationException', 'StreamWriteException'),
    ('JsonMappingException', 'DatabindException'),
    ('TextNode', 'StringNode'),
    ('SerializerProvider', 'SerializationContext'),
]
# JsonNode methods that Jackson 3 deprecates or removed, with unambiguous replacements.
#
# Jackson 3 made the no-argument conversions strict. asString() throws on objects and arrays (asText() gave ""),
# asInt()/asLong()/asDouble()/asBoolean() throw on anything that is not coercible (they gave 0 / false), and on a JSON
# null asString() gives "" where asText() gave "null". The variants that take a default never throw and match the old
# results for every node type (the one exception, a JSON null read through asText() with no default, now reads as ""),
# so the no-argument calls are rewritten to pass the old implicit default.
METHODS = [
    (r'\.asText\(\)', '.asString("")'),
    (r'\.asText\(', '.asString('),
    (r'\.asInt\(\)', '.asInt(0)'),
    (r'\.asLong\(\)', '.asLong(0L)'),
    (r'\.asDouble\(\)', '.asDouble(0.0)'),
    (r'\.asBoolean\(\)', '.asBoolean(false)'),
    (r'\.isTextual\(\)', '.isString()'),
    (r'\.isContainerNode\(\)', '.isContainer()'),
    (r'\.findValuesAsText\(', '.findValuesAsString('),
    (r'\.textNode\(', '.stringNode('),
    # jsr310, jdk8 and parameter-names support is built into Jackson 3, and mappers are immutable: there is nothing
    # left to register after construction. (A statement-level call, `mapper.findAndRegisterModules();`, fails to
    # compile afterwards and has to be fixed by hand.)
    (r'\.findAndRegisterModules\(\)', ''),
]


def place_jackson_imports(text):
    """Moves each `import tools.jackson...` to where it sorts among the other imports (the old com.fasterxml lines
    sorted near the top; the new package name belongs near the bottom). Blocks that were not sorted stay as they are."""
    lines = text.split('\n')
    moved = sorted({line for line in lines if line.startswith('import tools.jackson.')})
    if not moved:
        return text
    lines = [line for line in lines if not line.startswith('import tools.jackson.')]
    for new in moved:
        plain = [i for i, line in enumerate(lines) if line.startswith('import ') and not line.startswith('import static ')]
        if not plain:
            # no other plain import: put it where the first one used to be, right after the package / static imports
            at = max([i for i, line in enumerate(lines) if line.startswith('import ')] + [-1]) + 1
            if at == 0:
                at = next(i for i, line in enumerate(lines) if line.startswith('package ')) + 2
            lines.insert(at, new)
            continue
        after = next((i for i in plain if lines[i] > new), None)
        lines.insert(after if after is not None else plain[-1] + 1, new)
    return '\n'.join(lines)


def migrate(text):
    if 'com.fasterxml.jackson' not in text and 'tools.jackson' not in text:
        return text
    imported = {old.rsplit('.', 1)[1] for old, _ in FQN if old in text}
    for old, new in FQN:
        text = text.replace(old, new)
    # Everything else keeps its simple name; jackson-annotations stays in com.fasterxml.jackson.annotation.
    text = re.sub(r'\bcom\.fasterxml\.jackson\.(core|databind)\.', r'tools.jackson.\1.', text)
    for old, new in SIMPLE:
        if old in imported:
            text = re.sub(r'\b%s\b' % old, new, text)
    for pattern, new in METHODS:
        text = re.sub(pattern, new, text)
    # Two imports of the same renamed class (e.g. JsonProcessingException and JacksonException) collapse to one.
    seen, lines = set(), []
    for line in text.split('\n'):
        if line.startswith('import ') and line in seen:
            continue
        if line.startswith('import '):
            seen.add(line)
        lines.append(line)
    return place_jackson_imports('\n'.join(lines))


def main():
    changed = 0
    for root in sys.argv[1:]:
        for path in pathlib.Path(root).rglob('*.java'):
            old = path.read_text()
            new = migrate(old)
            if new != old:
                path.write_text(new)
                changed += 1
    print(f'{changed} files rewritten')


if __name__ == '__main__':
    main()
