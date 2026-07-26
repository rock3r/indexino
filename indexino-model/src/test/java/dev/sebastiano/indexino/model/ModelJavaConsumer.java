package dev.sebastiano.indexino.model;

import java.util.List;

final class ModelJavaConsumer {
    private ModelJavaConsumer() {}

    static QueryPage<Symbol> compileAgainstPublicModel() {
        SourceOriginId origin = SourceOriginId.of("workspace");
        SourceFile file = SourceFile.of(origin, "src/Panel.kt", "src/Panel.kt");
        SourceLocation location = SourceLocation.of(file, 3, 2, 24);
        SourceRange range = SourceRange.of(location, location);
        SymbolId symbolId = SymbolId.of("sample.Panel");
        Symbol symbol =
                new Symbol(
                        symbolId,
                        "Panel",
                        "class",
                        "kotlin",
                        location,
                        range,
                        null,
                        "sample.Panel",
                        null,
                        List.of("Panel"));
        SymbolQuery query = SymbolQuery.named("Panel").withMatch(NameMatchMode.EXACT);
        ReferenceQuery references = ReferenceQuery.to(symbolId);

        if (!"Panel".equals(query.getName()) || !symbolId.equals(references.getSymbolId())) {
            throw new AssertionError("Java getters must expose query values");
        }
        return new QueryPage<>(List.of(symbol), 0, 1, false, null, 1);
    }
}
